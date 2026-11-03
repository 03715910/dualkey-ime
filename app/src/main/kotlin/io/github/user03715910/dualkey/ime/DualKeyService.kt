package io.github.user03715910.dualkey.ime

import android.content.*
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.hardware.input.InputManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.*
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
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
        val semantic: JapaneseLexicon?,val symbols: Map<String,List<String>>,val context: Candidate?,
        val expressions: ExpressionCandidates,val emoji: Boolean,val face: Boolean)
    private var candidateQuery: CandidateQuery? = null
    private var candidateJob: Job? = null
    private var candidateLoading=false
    private var requestedReading=""
    private var requestedLimit=0
    private val translator = TranslationEngine()
    private var ui: KeyboardUi? = null
    private val hardwarePanel by lazy { HardwareCandidatePanel(this,::commitCandidate) }
    private var hardwareCandidates=false
    private var candidateHostRequested=false
    private var windowShown=false
    private var displayEpoch=0L
    private var displayRetry: Job?=null
    // A display preference is not a request to open a window. The OS owns that request.
    private var editorRequested=false
    private var ownHidePending=false
    private var ownHideSettlesAt=0L
    private var changingConfiguration=false
    private var updatingVisibility=false
    private data class EditorKey(val packageName: String?,val fieldId: Int,val type: Int,val options: Int)
    private var editorKey: EditorKey?=null
    private data class RotationComposition(val editor: EditorKey,val text: String,val end: Int,val expires: Long)
    // A second framework restart can arrive several seconds after the configuration callback.
    // Restoration still requires the same editor, cursor, and owned text.
    private val rotationRestoreWindow=6_000L
    private var rotationComposition: RotationComposition?=null
    private var composingEnd: Int?=null
    private var compositionRestoreJob: Job?=null
    private val lifecycleStates=ArrayDeque<String>()
    private fun lifecycleState(value: String) { lifecycleStates.addLast(value); while (lifecycleStates.size>8) lifecycleStates.removeFirst() }
    private var english = false
    private var shift = 0
    private var lastShift = 0L
    private var symbols = false
    private var sensitive = false
    private var predictionsAllowed = true
    private var japaneseConversionAllowed = false
    private var learningAllowed = false
    private var contextAllowed = false
    private data class CommittedTail(val word: Candidate,val position: Int?)
    private var committedTail: CommittedTail? = null
    private var continuationCandidates = false
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
    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private var clipboardShowing=false
    private var clipboardHistoryWasEnabled=false
    private var mediaPasteJob: Job?=null
    private var mediaHistory=emptyList<MediaHistoryEntry>()
    private var mediaHistoryEpoch=0L
    private var clipboardPreviewJob: Job?=null
    private var screenshotJob: Job?=null
    private var screenshotObserverRegistered=false
    private var displayedScreenshot: RecentScreenshot?=null
    private var screenshotThumbnail: android.graphics.Bitmap?=null
    private var dismissedScreenshot: RecentScreenshot?=null
    private val screenshotObserver=object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshScreenshot() }
    }
    private var previewTimestamp=-1L
    private var dismissedPreview=-1L
    private var observedMedia=-1L
    private var observedText=-1L
    private var selectionEpoch=0L
    private var blockedClipTimestamp=-1L
    private val clipboardListener=ClipboardManager.OnPrimaryClipChangedListener {
        if (hasEditor() && !clipboardEligible()) {
            blockedClipTimestamp=runCatching { clipboardManager.primaryClipDescription?.timestamp }.getOrNull() ?: -1L
        } else if (windowShown && clipboardEligible()) {
            captureClipboard()
            refreshClipboardPreview()
        }
    }

    // Explicit support diagnostics contain state only, never composing or committed text.
    override fun dump(fd: java.io.FileDescriptor?,writer: java.io.PrintWriter?,args: Array<out String>?) {
        writer?.println("DualKey: requested=$hardwareCandidates, converting=$converting, continuation=$continuationCandidates, selected=$selected, choices=${candidates.size}")
        writer?.println("started=$inputStarted, sensitive=$sensitive, english=$english, predictions=$predictionsAllowed, mode=${visibility.mode}")
        writer?.println("action=${(currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION}")
        writer?.println("fieldId=${currentInputEditorInfo?.fieldId}")
        writer?.println("editor=${hasEditor()}, windowShown=$windowShown, editorRequested=$editorRequested, candidateHost=$candidateHostRequested")
        writer?.println("lifecycle=${lifecycleStates.joinToString(" / ")}, ownHide=$ownHidePending")
        writer?.println("attributeToken=${window?.window?.attributes?.token!=null}, decorToken=${window?.window?.decorView?.windowToken!=null}, panel=${hardwarePanel.status}")
        if (Build.VERSION.SDK_INT>=30) writer?.println("captionVisible=${window?.window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.captionBar())}")
    }

    override fun onCreate() {
        super.onCreate()
        inputManager = getSystemService(InputManager::class.java)
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        scope.launch { app.symbols.entries.collect { refreshCandidates() } }
        scope.launch { app.expressions.entries.collect { refreshCandidates() } }
        scope.launch { app.clipboard.state.collect { if (clipboardShowing) clipboardHistory() } }
        scope.launch { app.clipboard.clearEvents.collect { clearMediaHistory(); if (clipboardShowing) clipboardHistory() } }
        scope.launch { app.japaneseDictionary.engine.collect { refreshCandidates() } }
        scope.launch { app.semanticDictionary.lexicon.collect { refreshCandidates() } }
        scope.launch { app.japaneseDictionary.message.collect { ui?.dictionaryStatus(it) } }
        scope.launch { app.learning.state.collect { refreshCandidates() } }
        scope.launch { app.settings.state.collect {
            if (!it.clipboardHistory) clearMediaHistory()
            if (it.clipboardHistory && !clipboardHistoryWasEnabled) {
                previewTimestamp=-1L
                refreshClipboardPreview()
            }
            clipboardHistoryWasEnabled=it.clipboardHistory
            if (lastPolicy!=null && lastPolicy!=it.policy) visibility.manual=null
            lastPolicy=it.policy
            visibility.policy = it.policy
            detectHardware()
            rebuild()
            refreshCandidates()
            applyVisibility(false)
            refreshScreenshot()
        } }
        scope.launch { ImeControl.requests.collect { request -> if (request!=null && hasEditor()) applyDisplayRequest(request) } }
    }
    override fun onCreateInputView(): View = KeyboardUi(this,::onScreenKey,::feedback).also { ui=it; it.dictionaryStatus(app.japaneseDictionary.message.value); rebuild(); refreshCandidates(); updateNavigationAppearance() }.root
    override fun onWindowShown() {
        super.onWindowShown(); windowShown=true
        lifecycleState("shown")
        if (!hasEditor() || (visibility.mode==DisplayMode.HIDDEN && !candidateHostRequested)) { applyVisibility(false); return }
        if (isInputViewShown) editorRequested=true
        if (!ownHidePending && SystemClock.uptimeMillis()>=ownHideSettlesAt) displayRetry?.cancel()
        updateNavigationAppearance(); publishVisibility()
        captureClipboard()
        refreshClipboardPreview()
        refreshScreenshot()
        window?.window?.decorView?.post { updateCaptionVisibility() }
    }
    override fun onWindowHidden() {
        val wasShown=windowShown
        val own=ownHidePending || updatingVisibility
        val expected=own || (!wasShown && SystemClock.uptimeMillis()<ownHideSettlesAt)
        if (own && wasShown) ownHideSettlesAt=maxOf(ownHideSettlesAt,SystemClock.uptimeMillis()+350)
        ownHidePending=false
        lifecycleState("hidden(own=$expected)")
        windowShown=false; clearClipboardPreview(); hardwareCandidates=false; candidateHostRequested=false; hardwarePanel.dismiss()
        stopScreenshot()
        if (!changingConfiguration && !expected) systemDismissed()
        publishVisibility(); super.onWindowHidden()
    }
    private fun systemDismissed() {
        editorRequested=false; displayEpoch++; displayRetry?.cancel()
    }
    override fun onCreateCandidatesView(): View = object: View(this) {
        override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
            // InputMethodService supplies WRAP_CONTENT, so the host must explicitly measure to zero.
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),0)
        }
    }
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        if (!hasEditor() || visibility.mode==DisplayMode.HIDDEN || (candidateHostRequested && !editorRequested)) {
            // The empty candidates host keeps the IME's subwindow available without reserving keyboard space.
            val height=window?.window?.decorView?.height ?: 0
            outInsets.contentTopInsets=height; outInsets.visibleTopInsets=height
            outInsets.touchableInsets=InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
        }
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        stopScreenshot()
        // Hiding only the UI must not finish the composing span owned by the still-active IME.
        if (finishingInput) super.onFinishInputView(true)
    }
    override fun onFinishCandidatesView(finishingInput: Boolean) {
        if (finishingInput) super.onFinishCandidatesView(true)
    }
    private fun updateNavigationAppearance() {
        window?.window?.let { w -> WindowCompat.getInsetsController(w,w.decorView).isAppearanceLightNavigationBars=
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES }
        updateCaptionVisibility()
    }
    private fun updateCaptionVisibility() {
        if (Build.VERSION.SDK_INT<30) return
        window?.window?.let { w ->
            val controller=WindowCompat.getInsetsController(w,w.decorView)
            if (settings.hideImeCaption && (!hasEditor() || visibility.mode==DisplayMode.HIDDEN || (candidateHostRequested && !editorRequested))) controller.hide(WindowInsetsCompat.Type.captionBar())
            else controller.show(WindowInsetsCompat.Type.captionBar())
            ViewCompat.requestApplyInsets(w.decorView)
        }
    }
    private fun publishVisibility() {
        val active=hasEditor()
        ImeControl.state.value=ImeControl.UiState(active,active && windowShown && isInputViewShown && visibility.mode!=DisplayMode.HIDDEN,visibility.mode)
    }
    private fun hasEditor() = inputStarted && currentInputConnection!=null &&
        (currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL) and InputType.TYPE_MASK_CLASS != InputType.TYPE_NULL
    private fun applyDisplayRequest(request: ImeControl.DisplayRequest) {
        if (request.notification && !app.settings.state.value.visibilityNotification) {
            ImeControl.requests.compareAndSet(request,null)
            scope.launch { app.settings.clearNotificationDisplay(request.mode) }
            return
        }
        chooseDisplay(request.mode)
        ImeControl.requests.compareAndSet(request,null)
        scope.launch { app.settings.awaitLoaded(); app.settings.clearNotificationDisplay(if (request.notification) request.mode else null) }
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        // This evaluates capability only; it must never open the IME window itself.
        return hasEditor() && visibility.mode != DisplayMode.HIDDEN && !(candidateHostRequested && !editorRequested)
    }
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (!hasEditor()) return false
        if (configChange && !editorRequested && !candidateHostRequested) return false
        // Remember a real editing request even when physical-keyboard policy hides the keys.
        if (!configChange && !candidateHostRequested) editorRequested=true
        return (visibility.mode != DisplayMode.HIDDEN && editorRequested) || candidateHostRequested
    }
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        stopScreenshot()
        val key=EditorKey(attribute.packageName,attribute.fieldId,attribute.inputType,attribute.imeOptions)
        val owned=if (english) englishWord else displayedComposition()
        val same=restarting && inputStarted && editorKey==key
        val rotation=rotationComposition?.takeIf { same && it.editor==key && it.expires>=SystemClock.uptimeMillis() }
        val before=if (same && !changingConfiguration && rotation==null && owned.isNotEmpty()) runCatching {
            currentInputConnection?.getTextBeforeCursor(owned.length,0)?.toString()
        }.getOrNull() else null
        val preserve=restarting && inputStarted && editorKey==key &&
            // The framework restarts the same connection during configuration changes.
            // A synchronous client read can be temporarily empty while its layout is rebuilding.
            (changingConfiguration || owned.isEmpty() || before?.endsWith(owned)==true || rotation!=null)
        lifecycleState("start(restart=$restarting,config=$changingConfiguration,same=$same,preserve=$preserve,restore=${rotation!=null})")
        val wanted=editorRequested && (preserve || windowShown)
        displayEpoch++; displayRetry?.cancel()
        super.onStartInput(attribute,restarting)
        clearClipboardPreview()
        session++; inputStarted=attribute.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_NULL
        editorKey=if (inputStarted) key else null
        if (!preserve) clearState() else invalidateTranslation()
        if (preserve && !changingConfiguration && (owned.isNotEmpty() || rotation!=null)) {
            val origin=rotation ?: composingEnd?.let { RotationComposition(key,owned,it,SystemClock.uptimeMillis()+2000) }
            if (origin!=null) restoreComposition(origin,session)
        }
        editorRequested=inputStarted && wanted
        ImeControl.inputActive=hasEditor()
        val type = attribute.inputType
        val klass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        sensitive = (klass == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        numeric = klass == InputType.TYPE_CLASS_NUMBER || klass == InputType.TYPE_CLASS_PHONE || klass == InputType.TYPE_CLASS_DATETIME
        // Editors may provide their own completions. Kana-to-kanji conversion still
        // needs local candidates to turn the composing reading into Japanese text.
        japaneseConversionAllowed = inputStarted && !sensitive && klass == InputType.TYPE_CLASS_TEXT
        predictionsAllowed = japaneseConversionAllowed && type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        learningAllowed = predictionsAllowed && klass==InputType.TYPE_CLASS_TEXT &&
            attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING==0 &&
            variation !in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_URI)
        contextAllowed=predictionsAllowed && klass==InputType.TYPE_CLASS_TEXT &&
            variation !in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_URI)
        if (!preserve) {
            english = settings.initialEnglish || sensitive || numeric || (klass == InputType.TYPE_CLASS_TEXT &&
                variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_URI))
            shift=0; symbols=false
        }
        detectHardware(); rebuild(); refreshCandidates()
        val initialRequest=if (hasEditor()) ImeControl.requests.value else null
        initialRequest?.let(::applyDisplayRequest)
        val startedSession=session; val startedCommand=displayEpoch
        if (hasEditor() && initialRequest==null) scope.launch {
            app.settings.awaitLoaded()
            if (hasEditor() && session==startedSession && displayEpoch==startedCommand) {
                val pending=app.settings.consumeNotificationDisplay()
                if (pending!=null && hasEditor() && session==startedSession && displayEpoch==startedCommand) chooseDisplay(pending)
            }
        }
        publishVisibility()
        if (!sensitive && predictionsAllowed) runCatching {
            val mode=InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
            if (Build.VERSION.SDK_INT>=33) currentInputConnection?.requestCursorUpdates(mode,InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER)
            else currentInputConnection?.requestCursorUpdates(mode)
        }
        Handler(Looper.getMainLooper()).post { if (session==startedSession) applyVisibility(false) }
    }
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info,restarting)
        if (hasEditor() && visibility.mode!=DisplayMode.HIDDEN) editorRequested=true
        rebuild(); refreshCandidates(); publishVisibility(); captureClipboard(); refreshClipboardPreview(); refreshScreenshot()
    }
    override fun onFinishInput() {
        lifecycleState("finish")
        clearClipboardPreview()
        stopScreenshot()
        runCatching { currentInputConnection?.requestCursorUpdates(0) }
        commitComposition(); finishEnglish()
        currentInputConnection?.finishComposingText()
        clearState(); inputStarted=false; editorKey=null; editorRequested=false
        ImeControl.inputActive=false; session++; displayEpoch++; displayRetry?.cancel(); publishVisibility()
        super.onFinishInput()
    }
    override fun onUnbindInput() {
        lifecycleState("unbind")
        clearClipboardPreview()
        stopScreenshot()
        // The old connection can outlive its client; never let a delayed show use it.
        inputStarted=false; editorKey=null; editorRequested=false; ImeControl.inputActive=false
        session++; displayEpoch++; displayRetry?.cancel(); clearState(); applyVisibility(false)
        super.onUnbindInput()
    }
    override fun onDestroy() {
        stopScreenshot()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        hardwarePanel.dismiss()
        inputManager.unregisterInputDeviceListener(this)
        translator.close(); scope.cancel(); ImeControl.inputActive=false; inputStarted=false; windowShown=false; publishVisibility()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        val owned=if (english) englishWord else displayedComposition()
        val key=editorKey; val end=composingEnd
        if (hasEditor() && !sensitive && owned.isNotEmpty() && key!=null && end!=null && end>=owned.length &&
            (rotationComposition?.expires ?: 0)<SystemClock.uptimeMillis()) {
            rotationComposition=RotationComposition(key,owned,end,SystemClock.uptimeMillis()+rotationRestoreWindow)
        }
        changingConfiguration=true
        try {
            hardwarePanel.clearCursor()
            // The framework restores only the UI that the OS was actually showing.
            super.onConfigurationChanged(newConfig)
            detectHardware(); rebuild(); applyVisibility(false)
            if (windowShown) showHardwareCandidates()
        } finally { changingConfiguration=false }
    }
    override fun onInputDeviceAdded(deviceId: Int) = hardwareChanged()
    override fun onInputDeviceRemoved(deviceId: Int) = hardwareChanged()
    override fun onInputDeviceChanged(deviceId: Int) = hardwareChanged()
    private fun hardwareChanged() {
        val connected=visibility.connected
        detectHardware(); rebuild()
        // Mouse/device updates are not text-entry requests. Resume only an ongoing editing session.
        applyVisibility(connected && !visibility.connected && hasEditor() && editorRequested)
    }
    private fun detectHardware() {
        val connected = inputManager.inputDeviceIds.any { id -> inputManager.getInputDevice(id)?.let {
            !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && it.supportsSource(InputDevice.SOURCE_KEYBOARD)
        } == true } || (resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY && resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO)
        val remembered = if (settings.rememberManual) runCatching { DisplayMode.valueOf(settings.savedManual ?: "") }.getOrNull() else null
        visibility.connectionChanged(connected,remembered)
    }
    private fun chooseDisplay(mode: DisplayMode) {
        displayEpoch++; displayRetry?.cancel()
        hardwareCandidates=false; closeHardwarePanel(true)
        visibility.manual=mode
        if (mode!=DisplayMode.HIDDEN && hasEditor()) editorRequested=true
        if (settings.rememberManual && visibility.connected) scope.launch { app.settings.set(Prefs.savedManual, mode.name) }
        rebuild(); applyVisibility(true)
        if (mode==DisplayMode.FULL && hasEditor()) {
            val epoch=displayEpoch; val editorSession=session
            displayRetry=scope.launch {
                // A hide animation or notification shade may still be closing.
                // Retry every manual show with official APIs; a newer command cancels this job.
                for (wait in listOf(180L,350L,650L)) {
                    delay(wait)
                    if (!hasEditor() || session!=editorSession || displayEpoch!=epoch || !editorRequested || visibility.mode!=DisplayMode.FULL) break
                    if (ownHidePending) continue
                    if (SystemClock.uptimeMillis()<ownHideSettlesAt) continue
                    if (isInputViewShown && !ownHidePending) break
                    applyVisibility(true)
                }
            }
        }
    }
    private fun applyVisibility(request: Boolean) {
        updateCaptionVisibility()
        // Settings writes can arrive after Space has opened the zero-height candidate host.
        // Keep that host until an explicit display change or the input session releases it.
        val active=hasEditor()
        val candidateHost=active && candidateHostRequested && (visibility.mode==DisplayMode.HIDDEN || !editorRequested)
        updatingVisibility=true
        try { setCandidatesViewShown(candidateHost); updateInputViewShown() }
        finally { updatingVisibility=false }
        if (!active || (visibility.mode == DisplayMode.HIDDEN && !candidateHost)) {
            if (!ownHidePending && (windowShown || isInputViewShown)) {
                ownHidePending=true
                ownHideSettlesAt=SystemClock.uptimeMillis()+350
                requestHideSelf(0)
            }
        } else if (request && editorRequested && !ownHidePending && SystemClock.uptimeMillis()>=ownHideSettlesAt) {
            if (Build.VERSION.SDK_INT >= 28) requestShowSelf(0) else showLegacy()
        }
        publishVisibility()
    }
    @Suppress("DEPRECATION")
    private fun showLegacy() { window?.window?.attributes?.token?.let { getSystemService(InputMethodManager::class.java).showSoftInputFromInputMethod(it,0) } }
    private fun rebuild() { ui?.rebuild(settings,english,shift,visibility.mode,symbols,sensitive) }
    private fun feedback(view: View) {
        if (settings.vibration) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        if (settings.sound) getSystemService(AudioManager::class.java).playSoundEffect(AudioManager.FX_KEY_CLICK,0.3f)
    }
    private fun invalidateTranslation() {
        clipboardShowing=false
        revision++; translationJob?.cancel(); translationJob=null; variantJob?.cancel(); variantJob=null; ui?.hideTranslation()
    }
    private fun resetVariant() { variantJob?.cancel(); variantJob=null; variant=null }
    private fun clearState() {
        compositionRestoreJob?.cancel(); compositionRestoreJob=null
        rotationComposition=null; composingEnd=null
        hardwareCandidates=false; closeHardwarePanel(true); hardwarePanel.clearCursor()
        committedTail=null; continuationCandidates=false
        candidateJob?.cancel(); candidateQuery=null; candidateLoading=false; requestedReading=""; requestedLimit=0
        invalidateTranslation(); resetVariant(); composer.clear(); englishWord=""; candidates=emptyList(); candidateRecords=emptyList(); selected=-1; converting=false
        ui?.googleComposition(null)
        ui?.candidates(emptyList(),-1,{})
    }
    private fun resetConversion() { selected=-1; converting=false }
    private fun refreshCandidates() {
        continuationCandidates=false
        if (japaneseConversionAllowed && !english && !composer.isEmpty && (converting || settings.japanesePrediction)) {
            if (requestedReading!=composer.reading) { requestedReading=composer.reading; requestedLimit=maxOf(64,settings.candidateCount) }
            val query=CandidateQuery(composer.reading,!converting && predictionsAllowed && settings.japanesePrediction,
                maxOf(settings.candidateCount,requestedLimit),settings.dictionary,conversion,
                if (app.settings.isLoaded && settings.learningEnabled && learningAllowed) app.learning.state.value else LearningSnapshot(),
                app.semanticDictionary.lexicon.value,app.symbols.entries.value,
                committedTail?.takeIf { contextAllowed }?.word,
                app.expressions.entries.value,settings.emojiPrediction,settings.facePrediction)
            if (candidateQuery!=query || (converting && candidateLoading)) {
                val retain=candidateQuery?.let { it.reading==query.reading && it.prediction==query.prediction }==true
                candidateJob?.cancel(); candidateQuery=query; candidateLoading=false
                if (converting && query.limit<=100) {
                    // Space/Enter stays ordered even when pressed immediately after typing.
                    setCandidateRecords(personalized(query))
                } else {
                    if (!retain) setCandidateRecords(if (converting) personalized(query.copy(limit=64)) else emptyList())
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
            val tail=committedTail
            val next=if (contextAllowed && inputStarted && !english && composer.isEmpty && settings.japanesePrediction && tail!=null && validTail(tail)) {
                continuationCandidates=true
                NextWordPrediction.candidates(tail.word,
                    if (settings.learningEnabled && learningAllowed) app.learning.state.value.continuations.filter { c ->
                        tail.word.text.endsWith(c.context) && c.context.length<=32 && c.text.length<=32 && c.reading.length<=32 &&
                            ((conversion as? DictionaryConversionEngine)?.knownChoice(c.reading,c.text)==true ||
                                c.text in PhrasePrediction.candidates(c.reading,false).map { it.text } ||
                                c.text in SemanticConversion.equivalents(app.semanticDictionary.lexicon.value,c.reading,c.reading))
                    } else emptyList(),settings.candidateCount)
            } else emptyList()
            setCandidateRecords(if (predictionsAllowed && english && settings.englishPrediction) {
                val words=EnglishPrediction.candidates(englishWord,10_000).map { Candidate(it,it.lowercase()) }
                (words.take(5)+app.expressions.entries.value.find(englishWord,true,true,settings.emojiPrediction,settings.facePrediction)+words.drop(5))
                    .distinctBy { it.text }
            } else next)
        }
        showCandidates()
    }
    private fun personalized(query: CandidateQuery): List<Candidate> {
        val converted=(query.engine as? DictionaryConversionEngine)?.candidates(query.reading,query.prediction,query.limit,query.learning.entries,query.learning.continuations)
            ?: query.engine.candidates(query.reading,query.prediction,query.limit)
        val reading=Personalization.normalize(query.reading)
        val counterForms=query.expressions.words[reading].orEmpty().map { Candidate(it,reading) }
        val general=(counterForms+PhrasePrediction.candidates(query.reading,query.prediction)+converted).distinctBy { it.text }
        val aliases=SymbolCandidates.candidates(query.reading,query.expressions.symbols,query.prediction)
        val symbols=(aliases+SymbolCandidates.candidates(query.reading,query.symbols,query.prediction))
            .distinctBy { it.text }.map { it.copy(kind=CandidateKind.SYMBOL) }
        val expressions=query.expressions.find(query.reading,false,query.prediction,query.emoji,query.face,query.limit==10000).ifEmpty {
            // CLDR also indexes words written in kanji (e.g. 自動車). Try just the
            // strongest dictionary matches, keeping the original reading for selection.
            general.asSequence().take(4).filter { it.reading==reading && it.text!=reading }
                .flatMap { query.expressions.find(it.text,false,false,query.emoji,query.face,query.limit==10000).asSequence() }
                .distinctBy { it.text }.take(if (query.limit==10000) 10000 else 6).map { it.copy(reading=reading) }.toList()
        }
        val equivalents=SemanticConversion.augment(query.reading,general,query.semantic,query.limit)
        val context=query.context?.let { NextWordPrediction.candidates(it,query.learning.continuations,64) }.orEmpty()
            .filter { it.reading==query.reading || query.prediction && it.reading.startsWith(query.reading) }
        val leading=equivalents.filter { it.directlyMatches(reading) }.take(5)
        val words=(leading+context.map { it.copy(kind=CandidateKind.RELATED) }+equivalents).distinctBy { it.text }
        val early=if (aliases.any { it.reading==Personalization.normalize(query.reading) }) aliases.take(6) else emptyList()
        val combined=(words.take(5)+early+expressions.take(10)+
            words.drop(5).take((query.limit-symbols.size-expressions.size-7).coerceAtLeast(0))+symbols+expressions+
            listOf(Candidate(reading,reading),Candidate(SemanticConversion.katakana(reading),reading))).distinctBy { it.text }
        // Legacy whole-input records are considered only if still supported by the dictionaries.
        val history=query.learning.entries.filter { h ->
            (h.reading==query.reading || query.prediction && h.reading.startsWith(query.reading)) &&
                (query.engine as? DictionaryConversionEngine)?.knownChoice(h.reading,h.text)==true ||
                (h.reading==query.reading && (h.text in symbols.map { it.text } || h.text in SemanticConversion.equivalents(query.semantic,h.reading,h.reading) ||
                    h.text in PhrasePrediction.candidates(h.reading,false).map { it.text }))
        }
        return Personalization.candidates(query.reading,combined,history,query.prediction,query.limit,context.map { it.text }.toSet())
    }
    private fun setCandidateRecords(values: List<Candidate>) {
        val chosen=if (converting || continuationCandidates) candidateRecords.getOrNull(selected)?.text else null
        candidateRecords=values; candidates=values.map { it.text }
        if (chosen!=null) selected=candidates.indexOf(chosen)
    }
    private fun showCandidates() {
        if (selected >= candidates.size) selected=-1
        val canExpand=candidateQuery?.let { it.limit<10000 && candidates.size>=it.limit }==true
        ui?.googleComposition(variant?.takeIf { it.googlePowered }?.text)
        val reading=if (english) englishWord else composer.reading
        ui?.candidates(candidates,selected,::commitCandidate,canExpand,candidateLoading,candidateRecords,reading)
        showHardwareCandidates()
    }
    private fun showHardwareCandidates() {
        if (!hardwareCandidates || !hasEditor() || sensitive || english || !japaneseConversionAllowed ||
            (!converting && !continuationCandidates) || selected !in candidates.indices) {
            closeHardwarePanel(); return
        }
        if ((visibility.mode==DisplayMode.HIDDEN || !editorRequested) && !candidateHostRequested) {
            candidateHostRequested=true
            setCandidatesViewShown(true)
            // A fresh official show request is also needed after a previous hide request.
            // onEvaluateInputViewShown still keeps the letter keys hidden.
            if (Build.VERSION.SDK_INT>=28) requestShowSelf(0) else showLegacy()
        }
        val top=ui?.root?.takeIf { visibility.mode!=DisplayMode.HIDDEN && isInputViewShown && it.isShown }
            ?.let { view -> IntArray(2).also(view::getLocationOnScreen)[1] }
        hardwarePanel.show(window?.window?.decorView?.windowToken,candidates,selected,continuationCandidates,top)
    }
    private fun closeHardwarePanel(releaseHost: Boolean=false) {
        hardwarePanel.dismiss()
        // Keep the zero-height host during this input session so Enter -> Tab cannot race an old hide.
        if (releaseHost && candidateHostRequested) { candidateHostRequested=false; setCandidatesViewShown(false) }
    }
    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(info)
        hardwarePanel.cursor(info); showHardwareCandidates()
    }
    private fun moreCandidates(all: Boolean=false) {
        if ((!all && candidateLoading) || composer.isEmpty || sensitive || english || requestedLimit>=10000) return
        requestedLimit=if (all) 10000 else (maxOf(requestedLimit,settings.candidateCount)+1000).coerceAtMost(10000)
        refreshCandidates()
    }
    private fun syncComposition() {
        if (compositionRestoreJob?.isActive!=true) {
            currentInputConnection?.setComposingText(displayedComposition(),1)
            rememberComposingEnd()
        }
        refreshCandidates()
    }
    private fun displayedComposition() = variant?.text ?: if (converting && selected in candidates.indices) candidates[selected] else composer.text
    private fun cursorPosition(): Int? = runCatching {
        val connection=currentInputConnection ?: return@runCatching null
        if (Build.VERSION.SDK_INT>=31) {
            val around=connection.getSurroundingText(0,0,0)
            if (around!=null && around.offset>=0 && around.selectionStart==around.selectionEnd)
                return@runCatching around.offset+around.selectionEnd
        }
        val extracted=connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars=1; hintMaxLines=1 },0)
        if (extracted!=null && extracted.selectionStart==extracted.selectionEnd) extracted.startOffset+extracted.selectionEnd else null
    }.getOrNull()
    private fun validTail(tail: CommittedTail,composing: String=""): Boolean = runCatching {
        val connection=currentInputConnection ?: return@runCatching false
        if (!connection.getSelectedText(0).isNullOrEmpty()) return@runCatching false
        val suffix=tail.word.text+composing
        val position=cursorPosition()
        tail.position!=null && position!=null && position==tail.position+composing.length &&
            connection.getTextBeforeCursor(suffix.length,0)?.toString()==suffix
    }.getOrDefault(false)
    private fun confirmed(candidate: Candidate,previous: CommittedTail?,learn: Boolean) {
        if (learn && learningAllowed && settings.learningEnabled && !sensitive && !numeric && !english) {
            val generation=app.learning.generation
            val engine=conversion as? DictionaryConversionEngine
            val semantic=app.semanticDictionary.lexicon.value
            val symbolEntries=app.symbols.entries.value
            scope.launch(Dispatchers.Default) {
                fun units(word: Candidate): List<Candidate> {
                    val parts=engine?.learningUnits(word.reading,word.text).orEmpty()
                    if (parts.isNotEmpty()) return parts
                    return if (word.text in symbolEntries[Personalization.normalize(word.reading)].orEmpty() ||
                        word.text in SemanticConversion.equivalents(semantic,word.reading,word.reading) ||
                        word in PhrasePrediction.candidates(word.reading,false)) listOf(word) else emptyList()
                }
                val parts=units(candidate)
                val before=previous?.let { units(it.word).lastOrNull() }
                if (generation==app.learning.generation && settings.learningEnabled) {
                    parts.forEach { app.learning.record(it.reading,it.text,generation) }
                    (listOfNotNull(before)+parts).zipWithNext().forEach { (a,b) -> app.learning.recordContinuation(a.text,b.reading,b.text,generation) }
                }
            }
        }
        committedTail=if (contextAllowed && !english && NextWordPrediction.validContext(candidate.text))
            CommittedTail(candidate,cursorPosition()) else null
    }
    private fun commitComposition(learn: Boolean=false) {
        compositionRestoreJob?.cancel(); compositionRestoreJob=null
        rotationComposition=null; composingEnd=null
        hardwareCandidates=false
        if (!composer.isEmpty) {
            val candidate=variant?.let { Candidate(it.text,it.reading) } ?:
                if (converting && selected in candidateRecords.indices) candidateRecords[selected] else Candidate(composer.reading,composer.reading)
            val previous=committedTail?.takeIf { validTail(it,displayedComposition()) }
            if (currentInputConnection?.commitText(candidate.text,1)==true) {
                if (variant?.googlePowered==true) committedTail=null
                else confirmed(candidate,previous,learn)
            }
        }
        resetVariant(); composer.clear(); resetConversion(); refreshCandidates()
    }
    private fun commitCandidate(index: Int) {
        if (!hasEditor() || index !in candidates.indices) return
        compositionRestoreJob?.cancel(); compositionRestoreJob=null
        rotationComposition=null; composingEnd=null
        hardwareCandidates=false
        if (continuationCandidates) {
            val tail=committedTail
            if (tail==null || !validTail(tail) || !composer.isEmpty) { committedTail=null; refreshCandidates(); return }
            val candidate=candidateRecords[index]
            invalidateTranslation()
            if (currentInputConnection?.commitText(candidate.text,1)==true) confirmed(candidate,tail,true)
            resetConversion(); refreshCandidates(); return
        }
        val composing=displayedComposition()
        invalidateTranslation()
        resetVariant()
        if (english) {
            // English prediction owns a composing span, so no unrelated committed text is removed.
            currentInputConnection?.commitText(candidates[index],1); englishWord=""
        } else {
            val candidate=candidateRecords[index]
            val previous=committedTail?.takeIf { validTail(it,composing) }
            if (currentInputConnection?.commitText(candidate.text,1)==true) confirmed(candidate,previous,true)
            composer.clear()
        }
        resetConversion(); refreshCandidates()
    }
    private fun type(value: String) {
        if (!hasEditor()) return
        hardwareCandidates=false
        invalidateTranslation()
        if (sensitive || numeric) { currentInputConnection?.commitText(value,1); return }
        if (english) {
            if (value.all { it.isLetter() || it == '\'' } && predictionsAllowed && settings.englishPrediction) {
                englishWord += value
                if (compositionRestoreJob?.isActive!=true) currentInputConnection?.setComposingText(englishWord,1)
            } else { finishEnglish(); currentInputConnection?.commitText(value,1) }
        } else {
            if (converting || variant!=null) commitComposition(true)
            committedTail?.let { if (composer.isEmpty && !validTail(it)) committedTail=null }
            composer.append(value); resetConversion()
            if (compositionRestoreJob?.isActive!=true) currentInputConnection?.setComposingText(composer.text,1)
        }
        if (compositionRestoreJob?.isActive!=true) rememberComposingEnd()
        refreshCandidates()
    }
    private fun restoreComposition(origin: RotationComposition,editorSession: Long) {
        compositionRestoreJob?.cancel()
        compositionRestoreJob=scope.launch {
            // The client's replacement connection may not yet accept commands inside onStartInput.
            for (wait in listOf(16L,32L,64L,128L,256L,350L,500L,750L)) {
                delay(wait)
                if (session!=editorSession || !hasEditor() || editorKey!=origin.editor || sensitive) return@launch
                if (SystemClock.uptimeMillis()>origin.expires) { clearState(); return@launch }
                val connection=currentInputConnection ?: return@launch
                val owned=if (english) englishWord else displayedComposition()
                val end=cursorPosition() ?: continue
                if (!connection.getSelectedText(0).isNullOrEmpty()) { clearState(); return@launch }
                val before=connection.getTextBeforeCursor(origin.text.length+owned.length,0)?.toString() ?: continue
                val start=origin.end-origin.text.length
                val original=when {
                    end==origin.end && before.endsWith(origin.text) -> origin.text
                    owned.isNotEmpty() && end==start+owned.length && before.endsWith(owned) -> owned
                    // A key may have reached the replacement client before its restart callback.
                    // Both adjacent pieces must be our known unconfirmed text at the known cursor.
                    owned.isNotEmpty() && end==origin.end+owned.length && before.endsWith(origin.text+owned) -> origin.text+owned
                    else -> continue
                }
                connection.beginBatchEdit()
                val restored=try {
                    if (!connection.setComposingRegion(end-original.length,end)) false
                    else if (owned!=original) connection.setComposingText(owned,1) else true
                } finally { connection.endBatchEdit() }
                if (restored) {
                    composingEnd=end-original.length+owned.length; rotationComposition=null
                    lifecycleState("composition-restored"); return@launch
                }
            }
            // Unverifiable or unsupported clients must not receive old text on a later key.
            if (session==editorSession) clearState()
        }
    }
    private fun rememberComposingEnd() { composingEnd=if (!composer.isEmpty || englishWord.isNotEmpty()) cursorPosition() else null }
    private fun finishEnglish() { if (englishWord.isNotEmpty()) { compositionRestoreJob?.cancel(); compositionRestoreJob=null; rotationComposition=null; composingEnd=null; currentInputConnection?.commitText(englishWord,1) }; englishWord=""; refreshCandidates() }
    private fun switchMode(toEnglish: Boolean = !english) {
        invalidateTranslation(); commitComposition(); committedTail=null; finishEnglish(); english=toEnglish; shift=0; resetConversion(); rebuild(); refreshCandidates()
    }
    private fun space() {
        invalidateTranslation()
        resetVariant()
        if (english || sensitive || numeric || composer.isEmpty) { committedTail=null; finishEnglish(); currentInputConnection?.commitText(" ",1); refreshCandidates(); return }
        if (!converting) { converting=true; refreshCandidates(); selected=0 }
        else if (candidates.isNotEmpty()) selected=(selected+1)%candidates.size
        syncComposition()
    }
    private fun enter(physical: Boolean=false,shiftPressed: Boolean=false) {
        invalidateTranslation()
        val info=currentInputEditorInfo
        val action=(info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val type=info?.inputType ?: InputType.TYPE_NULL
        val variation=type and InputType.TYPE_MASK_VARIATION
        val multiline=type and InputType.TYPE_TEXT_FLAG_MULTI_LINE!=0
        val privateField=sensitive || numeric || variation in setOf(InputType.TYPE_TEXT_VARIATION_URI,InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) ||
            action in setOf(EditorInfo.IME_ACTION_SEARCH,EditorInfo.IME_ACTION_GO,EditorInfo.IME_ACTION_NEXT,EditorInfo.IME_ACTION_PREVIOUS) ||
            (!multiline && action==EditorInfo.IME_ACTION_DONE)
        if (physical && shiftPressed && !privateField) {
            if (!composer.isEmpty) commitComposition(true)
            finishEnglish(); committedTail=null; resetConversion(); currentInputConnection?.commitText("\n",1); refreshCandidates(); return
        }
        if (continuationCandidates && selected in candidates.indices) { commitCandidate(selected); return }
        if (!composer.isEmpty) { commitComposition(true); return }
        committedTail=null; finishEnglish(); refreshCandidates()
        val behavior=if (physical) settings.physicalEnter else settings.screenEnter
        val newline=!privateField && (behavior=="NEWLINE" || (!physical && behavior=="SMART_NEWLINE" && (multiline || action==EditorInfo.IME_ACTION_SEND)))
        if (newline) { currentInputConnection?.commitText("\n",1); return }
        if (behavior=="ACTION" && action !in setOf(EditorInfo.IME_ACTION_NONE,EditorInfo.IME_ACTION_UNSPECIFIED)) {
            currentInputConnection?.performEditorAction(if (info?.actionLabel!=null && info.actionId!=0) info.actionId else action); return
        }
        // SEND is invoked only when the editor advertises it; raw key events never guess a send operation.
        if (physical && behavior=="SMART_SEND" && action==EditorInfo.IME_ACTION_SEND && !privateField) {
            if (currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)!=true) currentInputConnection?.commitText("\n",1)
        } else if (info?.actionLabel != null && info.actionId != 0) currentInputConnection?.performEditorAction(info.actionId)
        else if (!sendDefaultEditorAction(true) && !privateField) currentInputConnection?.commitText("\n",1)
    }
    private fun delete() {
        invalidateTranslation()
        if (variant!=null) { resetVariant(); resetConversion(); syncComposition(); return }
        if (converting) { resetConversion(); syncComposition(); return }
        if (!composer.isEmpty) { composer.delete(); currentInputConnection?.setComposingText(composer.text,1); if (composer.isEmpty) currentInputConnection?.finishComposingText() }
        else if (englishWord.isNotEmpty()) { englishWord=englishWord.dropLast(1); currentInputConnection?.setComposingText(englishWord,1); if (englishWord.isEmpty()) currentInputConnection?.finishComposingText() }
        else {
            committedTail=null
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
    private fun deleteChunk() {
        val connection=currentInputConnection ?: return
        invalidateTranslation(); committedTail=null
        connection.beginBatchEdit()
        try {
            if (!connection.getSelectedText(0).isNullOrEmpty()) {
                connection.finishComposingText(); connection.commitText("",1)
            } else if (!composer.isEmpty || englishWord.isNotEmpty()) {
                connection.setComposingText("",1); connection.finishComposingText()
            } else if (sensitive || numeric) {
                connection.deleteSurroundingTextInCodePoints(1,0)
            } else {
                val before=connection.getTextBeforeCursor(EditingPolicy.CONTEXT_LIMIT+1,0)?.toString().orEmpty()
                val count=EditingPolicy.chunkBeforeCursor(before)
                if (count>0) connection.deleteSurroundingTextInCodePoints(count,0)
            }
            clearState()
        } finally { connection.endBatchEdit() }
        refreshCandidates()
    }
    // Clipboard history is opt-in and independent of the editor's prediction and learning flags.
    private fun clipboardEligible() = hasEditor() && !sensitive && !numeric &&
        !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    private fun clipboardText(clip: ClipData?): Pair<String,Boolean>? {
        if (clip==null || clip.itemCount==0) return null
        val value=clip.getItemAt(0).text?.toString()
        if (value.isNullOrEmpty() || value.length>1_000_000) return null
        val privateClip=clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true ||
            clip.description.timestamp==blockedClipTimestamp
        return value to privateClip
    }
    private fun captureClipboard() {
        if (!settings.clipboardHistory || !clipboardEligible()) return
        val clip=runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        val timestamp=clip.description.timestamp
        if (timestamp==observedText) return
        observedText=timestamp
        clipboardText(clip)?.let { (text,sensitiveClip) -> app.clipboard.add(text,sensitiveClip) }
    }
    private fun clearClipboardPreview() {
        clipboardPreviewJob?.cancel()
        clipboardPreviewJob=null
        previewTimestamp=-1L
        ui?.showClipboardPreview(null,null)
    }

    private fun refreshClipboardPreview() {
        if (!windowShown || !clipboardEligible() || visibility.mode==DisplayMode.HIDDEN) return
        val description=runCatching { clipboardManager.primaryClipDescription }.getOrNull()
        val timestamp=description?.timestamp ?: -1L
        if (description==null || timestamp==blockedClipTimestamp ||
            description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true) {
            clearClipboardPreview()
            return
        }
        if (timestamp==previewTimestamp) return
        clearClipboardPreview()
        val clip=runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        if (clip.description.timestamp!=timestamp || !ClipboardMedia.mayContainMedia(clip)) return
        previewTimestamp=timestamp
        val editorSession=session
        val historyEpoch=mediaHistoryEpoch
        fun current()=previewTimestamp==timestamp && session==editorSession && windowShown && clipboardEligible() &&
            runCatching { clipboardManager.primaryClipDescription?.timestamp }.getOrNull()==timestamp
        clipboardPreviewJob=scope.launch {
            var staged: SharedMedia?=null
            try {
                val (uri,mime)=withContext(Dispatchers.IO) { app.clipboardMedia.description(clip) } ?: return@launch
                if (!current()) return@launch
                val thumbnail=app.clipboardMedia.clipboardThumbnail(uri,mime)
                if (!current()) return@launch
                val kind=when {
                    mime=="image/gif" -> "GIF"
                    mime.startsWith("video/") -> "動画"
                    else -> "画像"
                }
                if (timestamp!=dismissedPreview) ui?.showClipboardPreview(thumbnail,"コピーした${kind}を貼り付け",{
                    if (current()) {
                        dismissedPreview=timestamp
                        ui?.showClipboardPreview(null,null)
                        pasteClipboardMedia(clip)
                    }
                },{
                    dismissedPreview=timestamp
                    ui?.showClipboardPreview(null,null)
                })
                if (settings.clipboardHistory && timestamp!=observedMedia && historyEpoch==mediaHistoryEpoch) {
                    val media=app.clipboardMedia.stage(uri,mime,settings.clipboardMediaMinutes)
                    staged=media
                    val historyPreview=thumbnail ?: app.clipboardMedia.thumbnail(media)
                    if (current() && settings.clipboardHistory && historyEpoch==mediaHistoryEpoch) {
                        observedMedia=timestamp
                        if (rememberMedia(MediaHistoryEntry(media,historyPreview,timestamp))) staged=null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An expired clipboard grant or quota must not interrupt typing.
            } finally {
                staged?.let(app.clipboardMedia::discard)
            }
        }
    }

    private fun rememberMedia(entry: MediaHistoryEntry): Boolean {
        if (mediaHistory.any { it.copiedAt==entry.copiedAt }) return false
        mediaHistory=(listOf(entry)+currentMedia()).sortedByDescending { it.copiedAt }.take(settings.clipboardLimit)
        if (clipboardShowing) clipboardHistory()
        scope.launch {
            delay((entry.media.expires-System.currentTimeMillis()).coerceAtLeast(0L))
            mediaHistory=mediaHistory.filterNot { it.media==entry.media }
            if (clipboardShowing) clipboardHistory()
        }
        return true
    }

    private fun pasteText(text: String) {
        val connection=currentInputConnection ?: return
        connection.beginBatchEdit()
        try {
            invalidateTranslation()
            if (connection.getSelectedText(0).isNullOrEmpty()) {
                if (!composer.isEmpty) commitComposition(false)
                finishEnglish()
            }
            clearState(); connection.finishComposingText()
            connection.commitText(text,1)
        } finally { connection.endBatchEdit() }
        refreshCandidates()
    }
    private fun pasteClipboard() {
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        val data=runCatching { clipboardManager.primaryClip }.getOrNull()
        if (clipboardEligible() && data!=null && ClipboardMedia.mayContainMedia(data)) {
            pasteClipboardMedia(data)
            return
        }
        if (data==null && runCatching { currentInputConnection?.performContextMenuAction(android.R.id.paste) }.getOrNull()==true) return
        val clip=clipboardText(data)
        if (clip==null) { clipboardMessage("貼り付けられる文字列がありません"); return }
        pasteText(clip.first)
    }
    private fun clipboardMessage(message: String) = Toast.makeText(this,message,Toast.LENGTH_SHORT).show()

    private fun pasteClipboardMedia(clip: ClipData, screenshot: Boolean=false) {
        if (mediaPasteJob?.isActive==true) { clipboardMessage("メディアを準備中です"); return }
        val timestamp=clip.description.timestamp
        val saved=if (screenshot) null else currentMedia().firstOrNull { it.copiedAt==timestamp }
        if (saved!=null) {
            sendMedia(saved.media)
            return
        }
        val inputSession=session
        val inputRevision=revision
        val inputSelection=selectionEpoch
        val historyEpoch=mediaHistoryEpoch
        val connection=currentInputConnection ?: return
        val accepted=currentInputEditorInfo?.contentMimeTypes
        if (accepted.isNullOrEmpty()) { clipboardMessage("この入力欄はメディアを受け取れません。アプリの添付ボタンをお使いください"); return }
        val privateClip=screenshot || clip.description.timestamp==blockedClipTimestamp ||
            clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true
        fun current() = session==inputSession && revision==inputRevision && selectionEpoch==inputSelection && hasEditor() &&
            currentInputConnection===connection && !sensitive && !numeric &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        mediaPasteJob=scope.launch {
            var staged: SharedMedia?=null
            var delivered=false
            try {
                val (uri,mime)=withContext(Dispatchers.IO) { app.clipboardMedia.description(clip) }
                    ?: run { if (current()) clipboardMessage("コピーされた画像・動画を読み取れません。アプリから添付してください"); return@launch }
                if (!current()) return@launch
                if (!ClipboardMedia.accepts(mime,accepted)) {
                    clipboardMessage("この入力欄はこの形式を受け取れません。アプリの添付ボタンをお使いください"); return@launch
                }
                val media=app.clipboardMedia.stage(uri,mime,settings.clipboardMediaMinutes)
                staged=media
                if (!current()) return@launch
                delivered=sendMedia(media)
                if (delivered && !privateClip && historyEpoch==mediaHistoryEpoch && settings.clipboardHistory && clipboardEligible()) {
                    observedMedia=timestamp
                    rememberMedia(MediaHistoryEntry(media,null,timestamp))
                    scope.launch {
                        val bitmap=app.clipboardMedia.thumbnail(media)
                        mediaHistory=mediaHistory.map { if (it.media==media) it.copy(preview=bitmap) else it }
                        if (clipboardShowing) clipboardHistory()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current()) clipboardMessage("メディアを読み取れないか、容量上限（1件32 MiB）を超えています。アプリから添付してください") }
            finally { if (!delivered) staged?.let(app.clipboardMedia::discard) }
        }
    }

    private fun canPreviewScreenshot() = settings.recentScreenshots && windowShown && isInputViewShown &&
        visibility.mode!=DisplayMode.HIDDEN && clipboardEligible() && RecentScreenshot.hasAccess(this)

    private fun stopScreenshot() {
        screenshotJob?.cancel()
        screenshotJob=null
        if (screenshotObserverRegistered) {
            contentResolver.unregisterContentObserver(screenshotObserver)
            screenshotObserverRegistered=false
        }
        clearScreenshot()
    }

    private fun clearScreenshot() {
        val hadScreenshot=displayedScreenshot!=null
        displayedScreenshot=null
        screenshotThumbnail=null
        ui?.showClipboardPreview(null,null,screenshot=true)
        if (hadScreenshot && clipboardShowing && clipboardEligible()) clipboardHistory()
    }

    private fun refreshScreenshot() {
        if (!canPreviewScreenshot()) {
            stopScreenshot()
            return
        }
        if (!screenshotObserverRegistered) {
            screenshotObserverRegistered=runCatching {
                contentResolver.registerContentObserver(Uri.parse("content://media"),true,screenshotObserver)
            }.isSuccess
        }
        screenshotJob?.cancel()
        val editorSession=session
        screenshotJob=scope.launch {
            delay(150)
            val recent=withContext(Dispatchers.IO) { runCatching { RecentScreenshot.find(this@DualKeyService) }.getOrNull() }
            if (session!=editorSession || !canPreviewScreenshot()) return@launch
            if (recent==null || recent==dismissedScreenshot) {
                clearScreenshot()
                return@launch
            }
            if (recent!=displayedScreenshot) {
                val bitmap=app.clipboardMedia.clipboardThumbnail(recent.uri,recent.mime)
                if (session!=editorSession || !canPreviewScreenshot() || System.currentTimeMillis()>=recent.expiresAt) {
                    clearScreenshot()
                    return@launch
                }
                displayedScreenshot=recent
                screenshotThumbnail=bitmap
                if (clipboardShowing) clipboardHistory()
                ui?.showClipboardPreview(bitmap,"撮ったスクショを貼り付け",{
                    if (displayedScreenshot==recent && session==editorSession && canPreviewScreenshot() &&
                        System.currentTimeMillis()<recent.expiresAt) {
                        pasteScreenshot(recent)
                    }
                },{
                    dismissedScreenshot=recent
                    clearScreenshot()
                },screenshot=true)
            }
            delay((recent.expiresAt-System.currentTimeMillis()).coerceAtLeast(0))
            if (displayedScreenshot==recent) clearScreenshot()
        }
    }

    private fun pasteScreenshot(recent: RecentScreenshot) {
        if (displayedScreenshot!=recent || !canPreviewScreenshot() || System.currentTimeMillis()>=recent.expiresAt) return
        val clip=ClipData("スクリーンショット",arrayOf(recent.mime),ClipData.Item(recent.uri))
        pasteClipboardMedia(clip,screenshot=true)
    }

    private fun sendMedia(media: SharedMedia): Boolean {
        if (!hasEditor() || sensitive || numeric || getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked ||
            !ClipboardMedia.accepts(media.mime,currentInputEditorInfo?.contentMimeTypes)) {
            clipboardMessage("この入力欄はこの形式を受け取れません。アプリから添付してください"); return false
        }
        if (System.currentTimeMillis()>=media.expires || !media.file.isFile) {
            clipboardMessage("メディアの有効期限が切れました。コピーし直してください"); return false
        }
        val connection=currentInputConnection ?: return false
        invalidateTranslation()
        commitComposition(false); finishEnglish(); clearState()
        connection.finishComposingText()
        val ok=runCatching { connection.commitContent(
            InputContentInfo(media.uri,ClipDescription("DualKey",arrayOf(media.mime)),null),
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null
        ) }.getOrDefault(false)
        if (!ok) clipboardMessage("アプリがメディアを受け取れませんでした。アプリから添付してください")
        return ok
    }

    private fun currentMedia(): List<MediaHistoryEntry> = if (!settings.clipboardHistory) emptyList()
        else mediaHistory.filter { System.currentTimeMillis()<it.media.expires && it.media.file.isFile }.take(settings.clipboardLimit)
    private fun clearMediaHistory() {
        mediaHistoryEpoch++
        mediaHistory.forEach { app.clipboardMedia.discard(it.media) }
        mediaHistory=emptyList()
    }
    private fun clipboardHistory() {
        if (!clipboardEligible()) {
            if (clipboardShowing) ui?.hideTranslation()
            clipboardShowing=false
            Toast.makeText(this,"この入力欄ではクリップボード履歴を表示しません",Toast.LENGTH_SHORT).show(); return
        }
        clipboardShowing=true
        val screenshot=displayedScreenshot?.takeIf {
            settings.clipboardHistory && canPreviewScreenshot() && System.currentTimeMillis()<it.expiresAt
        }
        ui?.showClipboard(app.clipboard.current(),settings.clipboardHistory,
            { entry -> if (entry in app.clipboard.current()) pasteText(entry.text) },
            { entry -> app.clipboard.remove(entry) },
            { dismissedScreenshot=displayedScreenshot ?: dismissedScreenshot; clearScreenshot(); clearMediaHistory(); app.clipboard.clear(); clipboardHistory() },
            { clipboardShowing=false; ui?.hideTranslation() },
            currentMedia(), settings.clipboardLimit,
            { entry -> if (currentMedia().any { it.media==entry.media }) sendMedia(entry.media) },
            { entry -> mediaHistory=mediaHistory.filterNot { it.media==entry.media }; app.clipboardMedia.discard(entry.media); clipboardHistory() },
            screenshot?.let { recent -> { pasteScreenshot(recent) } },screenshotThumbnail,
            { dismissedScreenshot=screenshot; clearScreenshot() })
    }
    private fun moveCursor(step: Int) {
        invalidateTranslation()
        if (continuationCandidates && selected in candidates.indices) {
            selected=(selected+step+candidates.size)%candidates.size; showCandidates(); return
        }
        if (converting && candidates.isNotEmpty()) {
            selected=(selected+step+candidates.size)%candidates.size; syncComposition()
        } else {
            commitComposition(); committedTail=null; finishEnglish()
            sendDownUpKeyEvents(if (step<0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }
    private fun onScreenKey(key: String) {
        if (!hasEditor() && key !in setOf("full","compact","hide","settings")) return
        if (key=="more_candidates") { moreCandidates(); return }
        if (key!="shift") lastShift=0
        hardwareCandidates=false; hardwarePanel.dismiss()
        when (key) {
            "expand_candidates" -> moreCandidates(true)
            "paste" -> pasteClipboard()
            "clipboard_history" -> { app.clipboard.prune(); refreshClipboardPreview(); refreshScreenshot(); clipboardHistory() }
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
            "delete_chunk" -> deleteChunk()
            "cursor_left" -> moveCursor(-1)
            "cursor_right" -> moveCursor(1)
            "modify" -> modifyKana()
            "punctuation" -> type(if (composer.text.endsWith("、")) "。" else "、")
            "shift" -> {
                val now=SystemClock.uptimeMillis()
                shift=ScreenShift.tap(shift,if (lastShift>0) now-lastShift else Long.MAX_VALUE,ViewConfiguration.getDoubleTapTimeout().toLong())
                lastShift=now; rebuild()
            }
            else -> { type(key); val next=ScreenShift.afterInput(shift,key); if (next!=shift) { shift=next; lastShift=0; rebuild() } }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!hasEditor()) return super.onKeyDown(keyCode,event)
        var handled=true
        when {
            settings.shortcut && settings.displayShortcut.matches(HardwareShortcuts.key(keyCode),HardwareShortcuts.modifiers(event)) -> {
                if (event.repeatCount==0) chooseDisplay(if (visibility.mode==DisplayMode.FULL) DisplayMode.HIDDEN else DisplayMode.FULL)
            }
            settings.ctrlSpace && keyCode==KeyEvent.KEYCODE_SPACE && event.isCtrlPressed && !event.isAltPressed -> { if (event.repeatCount==0) switchMode() }
            keyCode in setOf(KeyEvent.KEYCODE_ZENKAKU_HANKAKU,KeyEvent.KEYCODE_LANGUAGE_SWITCH) -> { if (event.repeatCount==0) switchMode() }
            settings.jisKeyboard && keyCode==KeyEvent.KEYCODE_GRAVE && !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed -> {
                // Some JIS hardware reports the key above Tab as GRAVE instead of ZENKAKU_HANKAKU.
                if (event.repeatCount==0) switchMode()
            }
            keyCode==KeyEvent.KEYCODE_EISU -> switchMode(true)
            keyCode==KeyEvent.KEYCODE_KANA || keyCode==KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> switchMode(false)
            keyCode==KeyEvent.KEYCODE_HENKAN && !english -> { hardwareCandidates=!composer.isEmpty; space() }
            keyCode==KeyEvent.KEYCODE_MUHENKAN && !english && !composer.isEmpty -> { invalidateTranslation(); resetVariant(); resetConversion(); syncComposition() }
            event.isCtrlPressed || event.isAltPressed || event.isMetaPressed -> { commitComposition(); committedTail=null; finishEnglish(); handled=false }
            keyCode==KeyEvent.KEYCODE_SPACE -> { hardwareCandidates=!english && !composer.isEmpty; space() }
            keyCode==KeyEvent.KEYCODE_TAB && !english && !sensitive && candidates.isNotEmpty() && (converting || continuationCandidates) -> {
                // Only take Tab when there are actual choices. Ordinary form navigation is untouched.
                hardwareCandidates=true
                val step=if (event.isShiftPressed) -1 else 1
                selected=if (selected<0) (if (step>0) 0 else candidates.lastIndex) else (selected+step+candidates.size)%candidates.size
                if (converting) syncComposition() else showCandidates()
            }
            keyCode==KeyEvent.KEYCODE_ENTER || keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER -> enter(true,event.isShiftPressed)
            keyCode==KeyEvent.KEYCODE_DEL -> delete()
            keyCode==KeyEvent.KEYCODE_ESCAPE && (continuationCandidates || !composer.isEmpty || englishWord.isNotEmpty()) -> {
                invalidateTranslation()
                if (composer.isEmpty && englishWord.isEmpty()) { committedTail=null; resetConversion(); refreshCandidates() }
                else if (variant!=null) { resetVariant(); resetConversion(); syncComposition() }
                else if (converting) { resetConversion(); syncComposition() } else { composer.clear(); englishWord=""; currentInputConnection?.setComposingText("",1); currentInputConnection?.finishComposingText(); refreshCandidates() }
            }
            keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN) -> {
                if (continuationCandidates && (visibility.mode!=DisplayMode.HIDDEN || hardwareCandidates) && candidates.isNotEmpty() && (selected>=0 || keyCode==KeyEvent.KEYCODE_DPAD_UP || keyCode==KeyEvent.KEYCODE_DPAD_DOWN)) {
                    hardwareCandidates=true
                    val step=if (keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    selected=if (selected<0) (if (step>0) 0 else candidates.lastIndex) else (selected+step+candidates.size)%candidates.size
                    showCandidates()
                } else if (converting && candidates.isNotEmpty()) {
                    hardwareCandidates=true
                    val step=if (keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    selected=(selected+step+candidates.size)%candidates.size; syncComposition()
                } else { commitComposition(); committedTail=null; finishEnglish(); refreshCandidates(); handled=false }
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
        if (oldSelStart!=newSelStart || oldSelEnd!=newSelEnd) selectionEpoch++
        val ownsComposition=!composer.isEmpty || englishWord.isNotEmpty()
        val moved=oldSelStart!=newSelStart || oldSelEnd!=newSelEnd
        if (ownsComposition && compositionRestoreJob?.isActive==true && candidatesEnd<0 &&
            newSelStart==newSelEnd && newSelEnd==rotationComposition?.end) return
        // Delayed composing-end notifications are normal; cancel only when the cursor leaves
        // that span, or an explicitly selected source changes.
        if (ownsComposition && ((candidatesEnd>=0 && (newSelStart!=candidatesEnd || newSelEnd!=candidatesEnd)) || (candidatesEnd<0 && moved))) {
            // Editor clear/replace notifications can arrive after the next key has
            // already begun a new composing span. Do not commit that new romaji as
            // a literal because of an obsolete cursor report.
            if (selectionReportIsStale(newSelStart,newSelEnd)) return
            lifecycleState("cursor-reset")
            currentInputConnection?.finishComposingText(); clearState()
        } else if (!ownsComposition && moved) {
            if (translationJob!=null) invalidateTranslation()
            committedTail?.let { if (!validTail(it)) { committedTail=null; resetConversion(); refreshCandidates() } }
        }
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
                        compositionRestoreJob?.cancel(); compositionRestoreJob=null; currentInputConnection?.commitText(result,1); rotationComposition=null; composingEnd=null; resetVariant(); composer.clear(); englishWord=""; resetConversion(); refreshCandidates(); invalidateTranslation()
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
