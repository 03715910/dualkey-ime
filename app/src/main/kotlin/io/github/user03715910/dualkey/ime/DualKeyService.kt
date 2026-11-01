package io.github.user03715910.dualkey.ime

import android.content.*
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import android.widget.Toast
import androidx.core.view.WindowCompat
import io.github.dualkey.core.*
import io.github.user03715910.dualkey.*
import io.github.user03715910.dualkey.data.*
import io.github.user03715910.dualkey.settings.MainActivity
import io.github.user03715910.dualkey.translation.TranslationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DualKeyService : InputMethodService(), InputManager.InputDeviceListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as DualKeyApp
    private val settings get() = app.settings.state.value
    private val composer = Composer()
    private val visibility = VisibilityController()
    private val conversion get() = app.japaneseDictionary.engine.value
    private data class CandidateQuery(val reading: String, val prediction: Boolean, val limit: Int,
        val dictionary: Map<String, List<String>>, val engine: ConversionEngine, val learning: LearningSnapshot,
        val semantic: JapaneseLexicon?)
    private var candidateQuery: CandidateQuery? = null
    private var candidateJob: Job? = null
    private var candidateLoading=false
    private var requestedReading=""
    private var requestedLimit=0
    private val translator = TranslationEngine()
    private var ui: KeyboardUi? = null
    private var english = false
    private var shift = 0
    private var lastShift = 0L
    private var symbols = false
    private var sensitive = false
    private var predictionsAllowed = true
    private var learningAllowed = false
    private var numeric = false
    private var candidates = emptyList<String>()
    private var candidateRecords = emptyList<Candidate>()
    private var selected = -1
    private var converting = false
    private var englishWord = ""
    private var session = 0L
    private var revision = 0L
    private var translationJob: Job? = null
    private var variant: CompositionVariant? = null
    private var variantJob: Job? = null
    private val consumed = mutableSetOf<Int>()
    private lateinit var inputManager: InputManager
    private var inputStarted = false
    private var lastPolicy: HardwarePolicy? = null

    override fun onCreate() {
        super.onCreate()
        inputManager = getSystemService(InputManager::class.java)
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        scope.launch { app.japaneseDictionary.engine.collect { refreshCandidates() } }
        scope.launch { app.semanticDictionary.lexicon.collect { refreshCandidates() } }
        scope.launch { app.japaneseDictionary.message.collect { ui?.dictionaryStatus(it) } }
        scope.launch { app.learning.state.collect { refreshCandidates() } }
        scope.launch { app.settings.state.collect {
            if (lastPolicy!=null && lastPolicy!=it.policy) visibility.manual=null
            lastPolicy=it.policy
            visibility.policy = it.policy
            detectHardware()
            rebuild()
            refreshCandidates()
            applyVisibility(false)
        } }
        scope.launch { ImeControl.requests.collect { mode -> if (mode != null) {
            chooseDisplay(mode)
            ImeControl.requests.value = null
        } } }
    }
    override fun onCreateInputView(): View = KeyboardUi(this,::onScreenKey,::feedback).also { ui=it; it.dictionaryStatus(app.japaneseDictionary.message.value); rebuild(); refreshCandidates(); updateNavigationAppearance() }.root
    override fun onWindowShown() { super.onWindowShown(); updateNavigationAppearance() }
    private fun updateNavigationAppearance() {
        window?.window?.let { w -> WindowCompat.getInsetsController(w,w.decorView).isAppearanceLightNavigationBars=
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES }
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        // Explicit DualKey visibility must override the system hardware-keyboard default.
        return visibility.mode != DisplayMode.HIDDEN
    }
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        return visibility.mode != DisplayMode.HIDDEN
    }
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute,restarting)
        session++; inputStarted=true; ImeControl.inputActive=true
        clearState()
        val type = attribute.inputType
        val klass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        sensitive = (klass == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        numeric = klass == InputType.TYPE_CLASS_NUMBER || klass == InputType.TYPE_CLASS_PHONE || klass == InputType.TYPE_CLASS_DATETIME
        predictionsAllowed = !sensitive && !numeric && type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        learningAllowed = predictionsAllowed && klass==InputType.TYPE_CLASS_TEXT &&
            attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING==0 &&
            variation !in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_URI)
        english = settings.initialEnglish || sensitive || numeric || (klass == InputType.TYPE_CLASS_TEXT &&
            variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_URI))
        shift=0; symbols=false
        detectHardware(); rebuild(); refreshCandidates()
        Handler(Looper.getMainLooper()).post { applyVisibility(false) }
    }
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) { super.onStartInputView(info,restarting); rebuild(); refreshCandidates() }
    override fun onFinishInput() {
        commitComposition(); finishEnglish()
        currentInputConnection?.finishComposingText()
        clearState(); inputStarted=false; ImeControl.inputActive=false; session++
        super.onFinishInput()
    }
    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(this)
        translator.close(); scope.cancel(); ImeControl.inputActive=false
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); detectHardware(); rebuild(); applyVisibility(true) }
    override fun onInputDeviceAdded(deviceId: Int) = hardwareChanged()
    override fun onInputDeviceRemoved(deviceId: Int) = hardwareChanged()
    override fun onInputDeviceChanged(deviceId: Int) = hardwareChanged()
    private fun hardwareChanged() { detectHardware(); rebuild(); applyVisibility(true) }
    private fun detectHardware() {
        val connected = inputManager.inputDeviceIds.any { id -> inputManager.getInputDevice(id)?.let {
            !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && it.supportsSource(InputDevice.SOURCE_KEYBOARD)
        } == true } || (resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY && resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO)
        val remembered = if (settings.rememberManual) runCatching { DisplayMode.valueOf(settings.savedManual ?: "") }.getOrNull() else null
        visibility.connectionChanged(connected,remembered)
    }
    private fun chooseDisplay(mode: DisplayMode) {
        visibility.manual=mode
        if (settings.rememberManual && visibility.connected) scope.launch { app.settings.set(Prefs.savedManual, mode.name) }
        rebuild(); applyVisibility(true)
    }
    private fun applyVisibility(request: Boolean) {
        setCandidatesViewShown(false)
        updateInputViewShown()
        if (visibility.mode == DisplayMode.HIDDEN) requestHideSelf(0)
        else if (request && inputStarted && currentInputConnection != null) {
            if (Build.VERSION.SDK_INT >= 28) requestShowSelf(0) else showLegacy()
        }
    }
    @Suppress("DEPRECATION")
    private fun showLegacy() { window?.window?.attributes?.token?.let { getSystemService(InputMethodManager::class.java).showSoftInputFromInputMethod(it,0) } }
    private fun rebuild() { ui?.rebuild(settings,english,shift,visibility.mode,symbols,sensitive) }
    private fun feedback(view: View) {
        if (settings.vibration) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        if (settings.sound) getSystemService(AudioManager::class.java).playSoundEffect(AudioManager.FX_KEY_CLICK,0.3f)
    }
    private fun invalidateTranslation() {
        revision++; translationJob?.cancel(); translationJob=null; variantJob?.cancel(); variantJob=null; ui?.hideTranslation()
    }
    private fun resetVariant() { variantJob?.cancel(); variantJob=null; variant=null }
    private fun clearState() {
        candidateJob?.cancel(); candidateQuery=null; candidateLoading=false; requestedReading=""; requestedLimit=0
        invalidateTranslation(); resetVariant(); composer.clear(); englishWord=""; candidates=emptyList(); candidateRecords=emptyList(); selected=-1; converting=false
        ui?.googleComposition(null)
        ui?.candidates(emptyList(),-1,{})
    }
    private fun resetConversion() { selected=-1; converting=false }
    private fun refreshCandidates() {
        if (predictionsAllowed && !english && !composer.isEmpty && (converting || settings.japanesePrediction)) {
            if (requestedReading!=composer.reading) { requestedReading=composer.reading; requestedLimit=settings.candidateCount }
            val query=CandidateQuery(composer.reading,!converting && settings.japanesePrediction,
                maxOf(settings.candidateCount,requestedLimit),settings.dictionary,conversion,
                if (app.settings.isLoaded && settings.learningEnabled && learningAllowed) app.learning.state.value else LearningSnapshot(),
                app.semanticDictionary.lexicon.value)
            if (candidateQuery!=query) {
                val retain=candidateQuery?.let { it.reading==query.reading && it.prediction==query.prediction }==true
                candidateJob?.cancel(); candidateQuery=query; candidateLoading=false
                if (converting) {
                    // Space/Enter stays ordered even when pressed immediately after typing.
                    setCandidateRecords(personalized(query))
                } else {
                    if (!retain) setCandidateRecords(emptyList())
                    candidateLoading=true
                    candidateJob=scope.launch {
                        delay(12)
                        val values=withContext(Dispatchers.Default) { personalized(query) }
                        if (candidateQuery==query && inputStarted && !sensitive) {
                            setCandidateRecords(values); candidateLoading=false; showCandidates()
                        }
                    }
                }
            }
        } else {
            candidateJob?.cancel(); candidateQuery=null; candidateLoading=false; requestedReading=""; requestedLimit=0
            setCandidateRecords(if (predictionsAllowed && english && settings.englishPrediction)
                EnglishPrediction.candidates(englishWord,settings.candidateCount).map { Candidate(it,it.lowercase()) } else emptyList())
        }
        showCandidates()
    }
    private fun personalized(query: CandidateQuery): List<Candidate> = Personalization.candidates(query.reading,
        SemanticConversion.augment(query.reading,query.engine.candidates(query.reading,query.prediction,query.limit),query.semantic,query.limit),
        query.learning.entries,query.prediction,query.limit)
    private fun setCandidateRecords(values: List<Candidate>) {
        val chosen=if (converting) candidateRecords.getOrNull(selected)?.text else null
        candidateRecords=values; candidates=values.map { it.text }
        if (chosen!=null) selected=candidates.indexOf(chosen)
    }
    private fun showCandidates() {
        if (selected >= candidates.size) selected=-1
        val canExpand=candidateQuery?.let { it.limit<1000 && candidates.size>=it.limit }==true
        ui?.googleComposition(variant?.takeIf { it.googlePowered }?.text)
        ui?.candidates(candidates,selected,::commitCandidate,canExpand,candidateLoading)
    }
    private fun moreCandidates() {
        if (candidateLoading || composer.isEmpty || sensitive || english) return
        requestedLimit=(maxOf(requestedLimit,settings.candidateCount)+100).coerceAtMost(1000)
        refreshCandidates()
    }
    private fun syncComposition() {
        currentInputConnection?.setComposingText(variant?.text ?: if (converting && selected in candidates.indices) candidates[selected] else composer.text,1)
        refreshCandidates()
    }
    private fun commitComposition(learn: Boolean=false) {
        if (!composer.isEmpty) {
            val candidate=variant?.let { Candidate(it.text,it.reading) } ?:
                if (converting && selected in candidateRecords.indices) candidateRecords[selected] else Candidate(composer.reading,composer.reading)
            if (currentInputConnection?.commitText(candidate.text,1)==true && learn && variant?.googlePowered!=true) learnConfirmed(candidate)
        }
        resetVariant(); composer.clear(); resetConversion(); refreshCandidates()
    }
    private fun commitCandidate(index: Int) {
        if (index !in candidates.indices) return
        invalidateTranslation()
        resetVariant()
        if (english) {
            // English prediction owns a composing span, so no unrelated committed text is removed.
            currentInputConnection?.commitText(candidates[index],1); englishWord=""
        } else {
            val candidate=candidateRecords[index]
            if (currentInputConnection?.commitText(candidate.text,1)==true) learnConfirmed(candidate)
            composer.clear()
        }
        resetConversion(); refreshCandidates()
    }
    private fun learnConfirmed(candidate: Candidate) {
        if (learningAllowed && settings.learningEnabled && !sensitive && !numeric && !english)
            app.learning.record(candidate.reading,candidate.text)
    }
    private fun type(value: String) {
        invalidateTranslation()
        if (sensitive || numeric) { currentInputConnection?.commitText(value,1); return }
        if (english) {
            if (value.all { it.isLetter() || it == '\'' } && predictionsAllowed && settings.englishPrediction) {
                englishWord += value
                currentInputConnection?.setComposingText(englishWord,1)
            } else { finishEnglish(); currentInputConnection?.commitText(value,1) }
        } else {
            if (converting || variant!=null) commitComposition(true)
            composer.append(value); resetConversion(); currentInputConnection?.setComposingText(composer.text,1)
        }
        refreshCandidates()
    }
    private fun finishEnglish() { if (englishWord.isNotEmpty()) currentInputConnection?.commitText(englishWord,1); englishWord=""; refreshCandidates() }
    private fun switchMode(toEnglish: Boolean = !english) {
        invalidateTranslation(); commitComposition(); finishEnglish(); english=toEnglish; shift=0; resetConversion(); rebuild(); refreshCandidates()
    }
    private fun space() {
        invalidateTranslation()
        resetVariant()
        if (english || sensitive || numeric || composer.isEmpty) { finishEnglish(); currentInputConnection?.commitText(" ",1); refreshCandidates(); return }
        if (!converting) { converting=true; refreshCandidates(); selected=0 }
        else if (candidates.isNotEmpty()) selected=(selected+1)%candidates.size
        syncComposition()
    }
    private fun enter() {
        invalidateTranslation()
        if (!composer.isEmpty) { commitComposition(true); return }
        finishEnglish(); refreshCandidates()
        val info=currentInputEditorInfo
        if (info?.actionLabel != null && info.actionId != 0) currentInputConnection?.performEditorAction(info.actionId)
        else if (!sendDefaultEditorAction(true)) currentInputConnection?.commitText("\n",1)
    }
    private fun delete() {
        invalidateTranslation()
        if (variant!=null) { resetVariant(); resetConversion(); syncComposition(); return }
        if (converting) { resetConversion(); syncComposition(); return }
        if (!composer.isEmpty) { composer.delete(); currentInputConnection?.setComposingText(composer.text,1); if (composer.isEmpty) currentInputConnection?.finishComposingText() }
        else if (englishWord.isNotEmpty()) { englishWord=englishWord.dropLast(1); currentInputConnection?.setComposingText(englishWord,1); if (englishWord.isEmpty()) currentInputConnection?.finishComposingText() }
        else {
            val ic=currentInputConnection ?: return
            if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("",1) else ic.deleteSurroundingTextInCodePoints(1,0)
        }
        refreshCandidates()
    }
    private fun modifyKana() {
        if (sensitive || composer.isEmpty) return
        val s=composer.text; val last=s.last()
        val cycles=listOf("かが","きぎ","くぐ","けげ","こご","さざ","しじ","すず","せぜ","そぞ","ただ","ちぢ","つっづ","てで","とど",
            "はばぱ","ひびぴ","ふぶぷ","へべぺ","ほぼぽ","あぁ","いぃ","うぅゔ","えぇ","おぉ","やゃ","ゆゅ","よょ","わゎ")
        val cycle=cycles.firstOrNull { last in it } ?: return
        invalidateTranslation(); resetVariant(); composer.replace(s.dropLast(1)+cycle[(cycle.indexOf(last)+1)%cycle.length]); resetConversion(); syncComposition()
    }
    private fun moveCursor(step: Int) {
        invalidateTranslation()
        if (converting && candidates.isNotEmpty()) {
            selected=(selected+step+candidates.size)%candidates.size; syncComposition()
        } else {
            commitComposition(); finishEnglish()
            sendDownUpKeyEvents(if (step<0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }
    private fun onScreenKey(key: String) {
        when (key) {
            "more_candidates" -> moreCandidates()
            "cycle_script" -> cycleScript()
            "mode" -> switchMode()
            "full" -> chooseDisplay(DisplayMode.FULL)
            "compact" -> chooseDisplay(DisplayMode.COMPACT)
            "hide" -> chooseDisplay(DisplayMode.HIDDEN)
            "settings" -> startActivity(Intent(this, MainActivity::class.java).putExtra("settings",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            "translate" -> translationMenu()
            "symbols" -> { symbols=!symbols; rebuild() }
            "space" -> space()
            "enter" -> enter()
            "delete" -> delete()
            "cursor_left" -> moveCursor(-1)
            "cursor_right" -> moveCursor(1)
            "modify" -> modifyKana()
            "punctuation" -> type(if (composer.text.endsWith("、")) "。" else "、")
            "shift" -> {
                val now=SystemClock.uptimeMillis()
                shift=if (shift==2) 0 else if (now-lastShift<350 && shift==1) 2 else if (shift==1) 0 else 1
                lastShift=now; rebuild()
            }
            else -> { type(key); if (english && shift==1 && key.any(Char::isLetter)) { shift=0; rebuild() } }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        var handled=true
        when {
            settings.shortcut && keyCode==KeyEvent.KEYCODE_K && event.isCtrlPressed && event.isAltPressed -> {
                if (event.repeatCount==0) chooseDisplay(if (visibility.mode==DisplayMode.FULL) DisplayMode.HIDDEN else DisplayMode.FULL)
            }
            settings.ctrlSpace && keyCode==KeyEvent.KEYCODE_SPACE && event.isCtrlPressed && !event.isAltPressed -> { if (event.repeatCount==0) switchMode() }
            keyCode in setOf(KeyEvent.KEYCODE_ZENKAKU_HANKAKU,KeyEvent.KEYCODE_LANGUAGE_SWITCH) -> { if (event.repeatCount==0) switchMode() }
            keyCode==KeyEvent.KEYCODE_EISU -> switchMode(true)
            keyCode==KeyEvent.KEYCODE_KANA || keyCode==KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> switchMode(false)
            keyCode==KeyEvent.KEYCODE_HENKAN && !english -> space()
            keyCode==KeyEvent.KEYCODE_MUHENKAN && !english && !composer.isEmpty -> { invalidateTranslation(); resetVariant(); resetConversion(); syncComposition() }
            event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> { commitComposition(); finishEnglish(); handled=false }
            keyCode==KeyEvent.KEYCODE_SPACE -> space()
            keyCode==KeyEvent.KEYCODE_ENTER || keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER -> enter()
            keyCode==KeyEvent.KEYCODE_DEL -> delete()
            keyCode==KeyEvent.KEYCODE_ESCAPE && (!composer.isEmpty || englishWord.isNotEmpty()) -> {
                invalidateTranslation()
                if (variant!=null) { resetVariant(); resetConversion(); syncComposition() }
                else if (converting) { resetConversion(); syncComposition() } else { composer.clear(); englishWord=""; currentInputConnection?.setComposingText("",1); currentInputConnection?.finishComposingText(); refreshCandidates() }
            }
            keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN) -> {
                if (converting && candidates.isNotEmpty()) {
                    val step=if (keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    selected=(selected+step+candidates.size)%candidates.size; syncComposition()
                } else { commitComposition(); finishEnglish(); handled=false }
            }
            else -> {
                val unicode=event.unicodeChar
                if (unicode != 0 && unicode and KeyCharacterMap.COMBINING_ACCENT == 0 && !Character.isISOControl(unicode)) type(String(Character.toChars(unicode))) else handled=false
            }
        }
        if (handled) { consumed += keyCode; return true }
        return super.onKeyDown(keyCode,event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = if (consumed.remove(keyCode)) true else super.onKeyUp(keyCode,event)
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        val ownsComposition=!composer.isEmpty || englishWord.isNotEmpty()
        val moved=oldSelStart!=newSelStart || oldSelEnd!=newSelEnd
        // Delayed composing-end notifications are normal; cancel only when the cursor leaves
        // that span, or an explicitly selected source changes.
        if (ownsComposition && ((candidatesEnd>=0 && (newSelStart!=candidatesEnd || newSelEnd!=candidatesEnd)) || (candidatesEnd<0 && moved))) {
            // Editor clear/replace notifications can arrive after the next key has
            // already begun a new composing span. Do not commit that new romaji as
            // a literal because of an obsolete cursor report.
            if (selectionReportIsStale(newSelStart,newSelEnd)) return
            currentInputConnection?.finishComposingText(); clearState()
        } else if (!ownsComposition && moved && translationJob!=null) invalidateTranslation()
    }
    private fun selectionReportIsStale(start: Int,end: Int): Boolean = runCatching {
        val connection=currentInputConnection ?: return@runCatching false
        if (Build.VERSION.SDK_INT>=31) {
            val position=connection.getSurroundingText(0,0,0)
            if (position!=null && position.offset>=0) return@runCatching (
                position.offset+position.selectionStart!=start || position.offset+position.selectionEnd!=end)
        }
        val extracted=connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars=1; hintMaxLines=1 },0)
            ?: return@runCatching false
        extracted.startOffset+extracted.selectionStart!=start || extracted.startOffset+extracted.selectionEnd!=end
    }.getOrDefault(false)
    private fun translationMenu() {
        if (sensitive || numeric) return
        invalidateTranslation()
        val directions=listOf<Pair<String,()->Unit>>("Googleで日→英" to { startTranslation(true) }, "Googleで英→日" to { startTranslation(false) })
        ui?.showTranslation("Googleで翻訳する方向を選んでください。未確定の文章、または選択中の文章が対象です。",
            (if (settings.toEnglish) directions else directions.reversed()) + ("閉じる" to { invalidateTranslation() }))
    }
    private fun startTranslation(toEnglish: Boolean, allowDownload: Boolean = false) {
        if (sensitive || numeric) return
        val ic=currentInputConnection ?: return
        val own=if (english) englishWord else variant?.text ?: composer.reading
        val fromSelection=own.isEmpty()
        val source=if (fromSelection) ic.getSelectedText(0)?.toString().orEmpty() else own
        if (source.isBlank() || source.length>5000) { ui?.showTranslation("未確定の文章か選択範囲を用意してください（最大5,000文字）。",listOf("閉じる" to { invalidateTranslation() })); return }
        translationJob?.cancel()
        val sessionId=session; val sourceRevision=revision
        translationJob=scope.launch {
            try {
                if (!translator.ready()) {
                    if (!allowDownload) {
                        ui?.showTranslation("翻訳には日本語モデル（約30MB）の取得が必要です。Wi-Fi接続でダウンロードします。文章は端末内で処理します。",listOf("取得して翻訳" to { startTranslation(toEnglish,true) }, "キャンセル" to { invalidateTranslation() }))
                        return@launch
                    }
                    ui?.showTranslation("翻訳モデルを取得しています…",listOf("閉じる" to { invalidateTranslation() }))
                    translator.download(toEnglish)
                }
                val result=translator.translate(source,toEnglish)
                if (session!=sessionId || revision!=sourceRevision || sensitive) return@launch
                ui?.showTranslation("Google翻訳（端末内）\n$result\n翻訳には誤りが含まれる場合があります。",listOf("この訳を入力" to {
                    if (session==sessionId && revision==sourceRevision && !sensitive && (!fromSelection || currentInputConnection?.getSelectedText(0)?.toString()==source)) {
                        currentInputConnection?.commitText(result,1); resetVariant(); composer.clear(); englishWord=""; resetConversion(); refreshCandidates(); invalidateTranslation()
                    } else { invalidateTranslation(); Toast.makeText(this@DualKeyService,"入力先が変わりました。翻訳し直してください。",Toast.LENGTH_SHORT).show() }
                }, "キャンセル" to { invalidateTranslation() }), attribution=true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (session==sessionId) ui?.showTranslation("翻訳できませんでした。モデル取得時のWi-Fi接続を確認してください。通常の入力は続けられます。",listOf("閉じる" to { invalidateTranslation() })) }
        }
    }
    private fun cycleScript() {
        if (sensitive || numeric || !predictionsAllowed || english || composer.isEmpty) return
        invalidateTranslation()
        val reading=composer.reading
        if (reading.length>5000) { Toast.makeText(this,"表記切り替えは5,000文字までです。",Toast.LENGTH_SHORT).show(); return }
        val japanese=if (converting) candidateRecords.getOrNull(selected)?.text ?: reading
            else conversion.candidates(reading,false,2).firstOrNull()?.text ?: reading
        val request=variant?.takeIf { it.reading==reading }?.next() ?: CompositionVariant.start(reading,japanese)
        variant=request; resetConversion(); syncComposition()
        if (request.script!=CompositionScript.ENGLISH) return
        val inputSession=session; val inputRevision=revision
        fun current() = variant===request && session==inputSession && revision==inputRevision && inputStarted && !sensitive
        variantJob=scope.launch {
            try {
                val lexicon=app.semanticDictionary.lexicon.value ?: withTimeoutOrNull(3000) { app.semanticDictionary.lexicon.first { it!=null } }
                val word=withContext(Dispatchers.Default) { SemanticConversion.english(lexicon,request.reading,request.japanese) }
                if (!current()) return@launch
                if (word==null && !translator.ready()) {
                    if (current()) Toast.makeText(this@DualKeyService,"この文章の英語変換には「設定 → 翻訳」のモデル取得が必要です。もう一度押すとひらがなへ戻ります。",Toast.LENGTH_LONG).show()
                    return@launch
                }
                val translated=word ?: withTimeout(15_000) { translator.translate(request.japanese,true) }
                if (current() && translated.isNotBlank() && translated.length<=5000) {
                    variant=request.copy(text=translated,googlePowered=word==null)
                    syncComposition()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current()) Toast.makeText(this@DualKeyService,"英語へ切り替えられませんでした。もう一度押すとひらがなへ戻ります。",Toast.LENGTH_SHORT).show()
            }
        }
    }
}
