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

class DualKeyService : InputMethodService(), InputManager.InputDeviceListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as DualKeyApp
    private val settings get() = app.settings.state.value
    private val composer = Composer()
    private val visibility = VisibilityController()
    private val conversion = LocalConversionEngine { settings.dictionary }
    private val translator = TranslationEngine()
    private var ui: KeyboardUi? = null
    private var english = false
    private var shift = 0
    private var lastShift = 0L
    private var symbols = false
    private var sensitive = false
    private var predictionsAllowed = true
    private var numeric = false
    private var candidates = emptyList<String>()
    private var selected = -1
    private var converting = false
    private var englishWord = ""
    private var session = 0L
    private var revision = 0L
    private var translationJob: Job? = null
    private val consumed = mutableSetOf<Int>()
    private lateinit var inputManager: InputManager
    private var inputStarted = false
    private var lastPolicy: HardwarePolicy? = null

    override fun onCreate() {
        super.onCreate()
        inputManager = getSystemService(InputManager::class.java)
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
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
    override fun onCreateInputView(): View = KeyboardUi(this,::onScreenKey,::feedback).also { ui=it; rebuild(); refreshCandidates(); updateNavigationAppearance() }.root
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
    private fun invalidateTranslation() { revision++; translationJob?.cancel(); translationJob=null; ui?.hideTranslation() }
    private fun clearState() {
        invalidateTranslation(); composer.clear(); englishWord=""; candidates=emptyList(); selected=-1; converting=false
        ui?.candidates(emptyList(),-1) {}
    }
    private fun resetConversion() { selected=-1; converting=false }
    private fun refreshCandidates() {
        candidates = when {
            !predictionsAllowed -> emptyList()
            english -> if (settings.englishPrediction) EnglishPrediction.candidates(englishWord,settings.candidateCount) else emptyList()
            composer.isEmpty -> emptyList()
            converting || settings.japanesePrediction -> conversion.candidates(composer.reading,!converting && settings.japanesePrediction,settings.candidateCount).map { it.text }
            else -> emptyList()
        }
        if (selected >= candidates.size) selected=-1
        ui?.candidates(candidates,selected,::commitCandidate)
    }
    private fun syncComposition() {
        currentInputConnection?.setComposingText(if (converting && selected in candidates.indices) candidates[selected] else composer.text,1)
        refreshCandidates()
    }
    private fun commitComposition() {
        if (!composer.isEmpty) currentInputConnection?.commitText(if (converting && selected in candidates.indices) candidates[selected] else composer.reading,1)
        composer.clear(); resetConversion(); refreshCandidates()
    }
    private fun commitCandidate(index: Int) {
        if (index !in candidates.indices) return
        invalidateTranslation()
        if (english) {
            // English prediction owns a composing span, so no unrelated committed text is removed.
            currentInputConnection?.commitText(candidates[index],1); englishWord=""
        } else {
            currentInputConnection?.commitText(candidates[index],1); composer.clear()
        }
        resetConversion(); refreshCandidates()
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
            if (converting) commitComposition()
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
        if (english || sensitive || numeric || composer.isEmpty) { finishEnglish(); currentInputConnection?.commitText(" ",1); refreshCandidates(); return }
        if (!converting) { converting=true; refreshCandidates(); selected=0 }
        else if (candidates.isNotEmpty()) selected=(selected+1)%candidates.size
        syncComposition()
    }
    private fun enter() {
        invalidateTranslation()
        if (!composer.isEmpty) { commitComposition(); return }
        finishEnglish(); refreshCandidates()
        val info=currentInputEditorInfo
        if (info?.actionLabel != null && info.actionId != 0) currentInputConnection?.performEditorAction(info.actionId)
        else if (!sendDefaultEditorAction(true)) currentInputConnection?.commitText("\n",1)
    }
    private fun delete() {
        invalidateTranslation()
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
        invalidateTranslation(); composer.replace(s.dropLast(1)+cycle[(cycle.indexOf(last)+1)%cycle.length]); resetConversion(); syncComposition()
    }
    private fun onScreenKey(key: String) {
        when (key) {
            "mode" -> switchMode()
            "full" -> chooseDisplay(DisplayMode.FULL)
            "compact" -> chooseDisplay(DisplayMode.COMPACT)
            "hide" -> chooseDisplay(DisplayMode.HIDDEN)
            "settings" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "picker" -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            "translate" -> translationMenu()
            "symbols" -> { symbols=!symbols; rebuild() }
            "space" -> space()
            "enter" -> enter()
            "delete" -> delete()
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
            keyCode==KeyEvent.KEYCODE_MUHENKAN && !english && !composer.isEmpty -> { resetConversion(); syncComposition() }
            event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> { commitComposition(); finishEnglish(); handled=false }
            keyCode==KeyEvent.KEYCODE_SPACE -> space()
            keyCode==KeyEvent.KEYCODE_ENTER || keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER -> enter()
            keyCode==KeyEvent.KEYCODE_DEL -> delete()
            keyCode==KeyEvent.KEYCODE_ESCAPE && (!composer.isEmpty || englishWord.isNotEmpty()) -> {
                invalidateTranslation()
                if (converting) { resetConversion(); syncComposition() } else { composer.clear(); englishWord=""; currentInputConnection?.setComposingText("",1); currentInputConnection?.finishComposingText(); refreshCandidates() }
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
            currentInputConnection?.finishComposingText(); clearState()
        } else if (!ownsComposition && moved && translationJob!=null) invalidateTranslation()
    }
    private fun translationMenu() {
        if (sensitive || numeric) return
        val directions=listOf<Pair<String,()->Unit>>("Googleで日→英" to { startTranslation(true) }, "Googleで英→日" to { startTranslation(false) })
        ui?.showTranslation("Googleで翻訳する方向を選んでください。未確定の文章、または選択中の文章が対象です。",
            (if (settings.toEnglish) directions else directions.reversed()) + ("閉じる" to { invalidateTranslation() }))
    }
    private fun startTranslation(toEnglish: Boolean, allowDownload: Boolean = false) {
        if (sensitive || numeric) return
        val ic=currentInputConnection ?: return
        val own=if (english) englishWord else composer.reading
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
                        currentInputConnection?.commitText(result,1); composer.clear(); englishWord=""; resetConversion(); refreshCandidates(); invalidateTranslation()
                    } else { invalidateTranslation(); Toast.makeText(this@DualKeyService,"入力先が変わりました。翻訳し直してください。",Toast.LENGTH_SHORT).show() }
                }, "キャンセル" to { invalidateTranslation() }), attribution=true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (session==sessionId) ui?.showTranslation("翻訳できませんでした。モデル取得時のWi-Fi接続を確認してください。通常の入力は続けられます。",listOf("閉じる" to { invalidateTranslation() })) }
        }
    }
}
