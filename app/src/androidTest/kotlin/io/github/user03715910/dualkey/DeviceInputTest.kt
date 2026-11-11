package io.github.user03715910.dualkey

import android.content.Intent
import android.app.Notification
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.*
import android.view.accessibility.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dualkey.core.DisplayMode
import io.github.dualkey.core.ToggleShortcut
import io.github.user03715910.dualkey.data.LearningStore
import io.github.user03715910.dualkey.data.Prefs
import io.github.user03715910.dualkey.settings.MainActivity
import io.github.user03715910.dualkey.ime.KeyboardVisibilityNotification
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import io.github.dualkey.core.DictionaryConversionEngine
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual Android input routing and touch injection for the target build variant. */
@RunWith(AndroidJUnit4::class)
class DeviceInputTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var editor: EditText
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
    private fun await(label: String,condition: ()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+15_000
        while (SystemClock.uptimeMillis()<deadline) { if (condition()) return; SystemClock.sleep(80) }
        val labels=mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) { if (node==null) return; node.text?.toString()?.let(labels::add); for (i in 0 until node.childCount) collect(node.getChild(i)) }
        instrumentation.uiAutomation.windows.filter { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }.forEach { collect(it.root) }
        fail("Timed out: $label; input=${if (::editor.isInitialized) inputText() else "not ready"}; IME=${labels.joinToString(" | ")}\n${diagnostic()}")
    }
    private fun inputText(): String { var value=""; main { value=editor.text.toString() }; return value }
    private fun imeHeight(): Int {
        var value=0
        main { ViewCompat.getRootWindowInsets(activity.window.decorView)?.let { insets ->
            // An empty IME host may include the existing system navigation bar, not keyboard space.
            value=(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom-insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom).coerceAtLeast(0)
        } }
        return value
    }
    @Before fun openEditor() {
        check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic"))
        shell("cmd statusbar collapse")
        val component=android.content.ComponentName(instrumentation.targetContext.packageName,
            "io.github.user03715910.dualkey.ime.DualKeyService").flattenToShortString()
        // UTP reinstalls the app and can reset the default IME. Select the actual target, never Gboard.
        listOf("ime enable $component","ime set $component").forEach { command ->
            shell(command)
        }
        assertEquals(component,android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver,"default_input_method"))
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.awaitLoaded(); app.settings.set(Prefs.learningEnabled,false); app.settings.set(Prefs.initialEnglish,false); app.settings.set(Prefs.layout,"JIS"); app.settings.set(Prefs.hardwarePolicy,"AUTO"); app.settings.set(Prefs.shortcut,true); app.settings.set(Prefs.englishPrediction,true); app.settings.set(Prefs.japanesePrediction,true); app.settings.set(Prefs.candidateCount,30); app.settings.set(Prefs.numberRow,true); app.settings.set(Prefs.keyHeight,40); app.settings.set(Prefs.heightPercent,100)
            withTimeout(30_000) { app.japaneseDictionary.engine.first { it is DictionaryConversionEngine } }
            withTimeout(30_000) { app.semanticDictionary.lexicon.first { it!=null } }
            app.settings.setDisplayShortcut(ToggleShortcut())
            app.settings.set(Prefs.hideImeCaption,true); app.settings.set(Prefs.visibilityNotification,false)
            app.settings.clearNotificationDisplay()
            app.settings.set(Prefs.japaneseLayout,"FLICK"); app.settings.set(Prefs.englishLayout,"QWERTY")
            app.settings.set(Prefs.screenEnter,"SMART_NEWLINE"); app.settings.set(Prefs.physicalEnter,"SMART_SEND")
            app.settings.set(Prefs.clipboardHistory,false)
            withTimeout(10_000) { app.symbols.entries.first { it.isNotEmpty() } }
        }
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("editor ready") { var found=false; main { found=activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("test_input")!=null }; found }
        await("focused activity") { activity.hasWindowFocus() }
        main {
            editor=activity.findViewById<View>(android.R.id.content).findViewWithTag("test_input")
            editor.id=View.generateViewId()
            editor.setText(""); editor.requestFocus(); ImeControl.request(DisplayMode.FULL)
            activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor,0)
        }
        await("IME shown") {
            val shown=imeHeight()>300
            if (!shown) main { activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor,0) }
            shown
        }
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        await("IME bound to this test editor") { "fieldId=${editor.id}\n" in diagnostic().replace("\r\n","\n") }
        SystemClock.sleep(200)
    }
    @After fun close() { shell("cmd statusbar collapse"); if (::activity.isInitialized) main { activity.finish() } }
    private fun key(code: Int,meta: Int=0,repeat: Int=0) {
        // Programmatic setText/restartInput calls must settle before simulating the next user key.
        instrumentation.waitForIdleSync()
        val now=SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,repeat,meta,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(now,now+10,KeyEvent.ACTION_UP,code,0,meta,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD))
        instrumentation.waitForIdleSync()
    }
    private fun letters(text: String) { text.forEach { key(KeyEvent.KEYCODE_A+(it.lowercaseChar()-'a')) } }
    private fun diagnostic(): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
        "dumpsys activity service ${instrumentation.targetContext.packageName}/io.github.user03715910.dualkey.ime.DualKeyService")).use { String(it.readBytes()) }
    private fun hold(bounds: Rect) {
        val now=SystemClock.uptimeMillis()
        fun inject(action: Int) { val e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }; instrumentation.uiAutomation.injectInputEvent(e,true); e.recycle() }
        inject(MotionEvent.ACTION_DOWN); SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+150L); inject(MotionEvent.ACTION_UP); instrumentation.waitForIdleSync()
    }
    private fun copy(text: String,sensitive: Boolean=false) = main {
        val clip=android.content.ClipData.newPlainText("DualKey test",text)
        if (sensitive) clip.description.extras=android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
        activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
    }
    @Test fun generalSymbolReadingsIncludeMusicAndBothWaveCharacters() {
        letters("ONPU"); key(KeyEvent.KEYCODE_SPACE); touch(rect("一覧"))
        assertNotNull(gridCandidate { it.substringAfter('：')=="♪" })
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        val values=app.symbols.entries.value["おんぷ"].orEmpty()
        assertTrue(values.containsAll(listOf("♪","♫","♬")))
        for (reading in listOf("から","ー")) assertTrue(app.symbols.entries.value[reading].orEmpty().containsAll(listOf("〜","～")))
        captureHardware("music-symbol-candidates")
    }
    @Test fun waveCandidatesCanBeChosenForKaraAndLongVowelMark() {
        letters("KARA"); key(KeyEvent.KEYCODE_SPACE); touch(rect("一覧"))
        val wave=gridCandidate { it.substringAfter('：')=="～" }
        assertNotNull(wave); assertTrue(wave!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)); await("wave committed") { inputText()=="～" }
        key(KeyEvent.KEYCODE_MINUS); key(KeyEvent.KEYCODE_SPACE); touch(rect("一覧"))
        assertNotNull(gridCandidate { it.substringAfter('：')=="～" })
    }
    @Test fun symbolPanelProvidesTwoDistinctUnicodeWaves() {
        touch(rect("記号")); touch(rect("#+="))
        touch(rect("～")); touch(rect("〜"))
        assertEquals("～〜",inputText())
    }

    @Test fun symbolPagesSwitchAndInsertTheDisplayedGlyphsInEitherLanguage() {
        touch(rect("か行"))
        touch(rect("記号"))
        for (symbol in listOf(":", "@", ".", "-", "/", "¥", "#", "\"")) touch(rect(symbol))
        assertEquals("か:@.-/¥#\"",inputText())

        val nextPage=rect("#+=")
        touch(nextPage)
        assertEquals(nextPage,rect("123"))
        for (symbol in listOf("☆", "♪", "→", "〒", "々", "〆", "\\", "：")) touch(rect(symbol))
        assertEquals("か:@.-/¥#\"☆♪→〒々〆\\：",inputText())

        touch(rect("123")); touch(rect("1")); touch(rect("⌫")); touch(rect("戻る"))
        touch(rect("英語に切り替え")); touch(rect("a"))
        touch(rect("記号")); touch(rect(":")); touch(rect("空白"))
        assertEquals("か:@.-/¥#\"☆♪→〒々〆\\：a: ",inputText())
    }

    @Test fun symbolCatalogInsertsAcrossCategoriesAndPasteStillOpensClipboardHistory() {
        touch(rect("記号一覧を開く"))
        touch(rect("数学")); touch(rect("記号：∞"))
        touch(rect("矢印"))
        assertTrue(findNode { it.contentDescription=="記号の一覧" }?.isScrollable==true)
        touch(rect("記号：←"))
        touch(rect("丸数字")); touch(rect("記号：①"))
        assertEquals("∞←①",inputText())

        touch(rect("記号一覧を閉じる"))
        touch(rect("あ行"))
        assertEquals("∞←①あ",inputText())
        touch(rect("記号一覧を開く"))
        hold(rect("貼り付け"))
        await("clipboard history via Paste long press") {
            findNode { it.text?.toString()?.startsWith("履歴保存はOFF")==true }!=null
        }
        assertNull(findNode { it.contentDescription=="記号の一覧" })
    }
    @Test fun japaneseQwertyAndEnglishFlickUseIndependentSavedLayouts() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.japaneseLayout,"QWERTY"); app.settings.set(Prefs.englishLayout,"FLICK"); app.settings.state.first { it.japaneseQwerty && it.englishFlick } }
        touch(rect("k")); touch(rect("y")); touch(rect("o")); assertEquals("きょ",inputText()); touch(rect("↵"))
        touch(rect("英語に切り替え")); val a=rect("a行"); touch(a,dx=-(a.width()*0.7).toInt()); assertEquals("きょb",inputText())
        key(KeyEvent.KEYCODE_ZENKAKU_HANKAKU); letters("NIHONGO"); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("きょbにほんご",inputText())
        captureHardware("japanese-qwerty")
    }
    @Test fun englishFlickPositionsAndAllShiftStates() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.englishLayout,"FLICK"); app.settings.state.first { it.englishFlick } }
        touch(rect("英語に切り替え"))
        assertNull(findNode { it.text?.toString()=="Space" })
        assertTrue(rect("アットマーク").right<=rect("abc").left)
        touch(rect("アットマーク")); val punctuation=rect("句読点")
        touch(punctuation); touch(punctuation,dy=-(punctuation.height()*0.8).toInt())
        assertEquals("@.?",inputText())
        clickLabel("Shift：小文字"); assertNotNull(findNode { it.text?.toString()=="ABC" })
        touch(rect("ABC")); touch(rect("abc"))
        clickLabel("Shift：小文字"); SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout()+80L)
        clickLabel("Shift：次の1文字を大文字"); touch(rect("abc"))
        doubleTapShift()
        touch(rect("ABC")); touch(rect("DEF")); clickLabel("Shift：大文字固定"); touch(rect("abc"))
        assertEquals("@.?AaaADa",inputText())
    }
    @Test fun candidateTabsFilterAndCommitWithoutSpaces() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { withTimeout(10_000) { app.expressions.entries.first { it.words.isNotEmpty() } } }
        fun open() {
            letters("NIKO")
            clickLabel("一覧")
            assertTrue(rect("すべて").right<=rect("絞り込み").left)
            assertTrue(rect("絞り込み").right<=rect("絵文字").left)
            assertTrue(rect("絵文字").right<=rect("顔文字").left)
        }
        fun pick(value: String) {
            val node=gridCandidate { it.substringAfter('：')==value } ?: error("Missing candidate: $value")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        open()
        clickLabel("絞り込み")
        assertEquals("にこ",inputText())
        assertNotNull(gridCandidate { it.substringAfter('：')=="二個" })
        assertNull(findNode { it.contentDescription?.toString()?.substringAfter('：') in setOf("にコメント","に来","にこれ","にこの") })
        pick("2個")
        open()
        clickLabel("絵文字")
        assertNotNull(gridCandidate { it.substringAfter('：')=="😊" })
        assertNull(findNode { it.contentDescription?.toString()?.endsWith("：二個")==true })
        clickLabel("絞り込み")
        pick("2個")
        open()
        clickLabel("顔文字")
        pick("(^_^)")
        assertEquals("2個2個(^_^)",inputText())
    }
    @Test fun japanesePunctuationAliasesAndExpressionsAreSelectable() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { withTimeout(10_000) { app.expressions.entries.first { it.symbols.isNotEmpty() } }
            app.settings.set(Prefs.emojiPrediction,true); app.settings.set(Prefs.facePrediction,true) }
        fun pick(reading: String,answer: String,also: String?=null) {
            letters(reading); touch(rect("空白")); touch(rect("一覧"))
            if (also!=null) assertNotNull(gridCandidate { it.substringAfter('：')==also })
            val item=gridCandidate { it.substringAfter('：')==answer } ?: error("Missing candidate: $answer")
            assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        pick("TEN","・","点"); pick("TENTENTEN","…","⋯")
        pick("EGAO","😊","(^_^)")
        assertEquals("・…😊",inputText())
    }
    @Test fun englishExpressionAndIndependentOffSwitches() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { withTimeout(10_000) { app.expressions.entries.first { it.symbols.isNotEmpty() } }
            app.settings.set(Prefs.emojiPrediction,true); app.settings.set(Prefs.facePrediction,true) }
        touch(rect("英語に切り替え")); letters("SMILE")
        await("English emoji and face") { findNode { it.text?.toString()=="😊" }!=null && findNode { it.text?.toString()=="(^_^)" }!=null }
        runBlocking { app.settings.set(Prefs.emojiPrediction,false); app.settings.state.first { !it.emojiPrediction } }
        await("only faces remain") { findNode { it.text?.toString()=="😊" }==null && findNode { it.text?.toString()=="(^_^)" }!=null }
        clickLabel("(^_^)"); assertEquals("(^_^)",inputText())
        runBlocking { app.settings.set(Prefs.emojiPrediction,true); app.settings.set(Prefs.facePrediction,true) }
    }
    @Test fun shiftCanCancelAnUnusedSingleCapitalAndShowsThreeStates() {
        touch(rect("英語に切り替え")); touch(rect("Shift：小文字"))
        assertNotNull(findNode { it.contentDescription?.toString()=="Shift：次の1文字を大文字" })
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout()+80L); touch(rect("Shift：次の1文字を大文字"))
        assertNotNull(findNode { it.contentDescription?.toString()=="Shift：小文字" })
        touch(rect("a")); assertEquals("a",inputText())
        touch(rect("Shift：小文字")); touch(rect("A")); touch(rect("b")); assertEquals("aAb",inputText())
        assertNotNull(findNode { it.contentDescription?.toString()=="Shift：小文字" })
    }
    @Test fun deleteLongPressClearsOwnedCompositionWithoutAnExtraTapDelete() {
        main { editor.setText("前。"); editor.setSelection(editor.length()) }
        letters("KANA"); assertEquals("前。かな",inputText())
        hold(rect("⌫")); await("composition deleted once") { inputText()=="前。" }
        touch(rect("⌫")); assertEquals("前",inputText())
    }
    @Test fun deleteLongPressHonorsCursorClausesAndSelection() {
        main { editor.setText("あ。い、うえ。後ろ"); editor.setSelection(7) }
        hold(rect("⌫")); assertEquals("あ。い、後ろ",inputText())
        main { editor.setSelection(0,2) }; hold(rect("⌫")); assertEquals("い、後ろ",inputText())
    }
    @Test fun pasteWorksWithHistoryOffAndToolbarHasNoDuplicateModeButton() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        copy("貼り付け試験"); touch(rect("貼り付け")); assertEquals("貼り付け試験",inputText())
        assertFalse(app.settings.state.value.clipboardHistory); assertTrue(app.clipboard.current().isEmpty())
        assertNull(findNode { it.text?.toString()=="日本語" })
        hold(rect("貼り付け")); assertEquals("貼り付け試験",inputText())
        assertNotNull(findNode { it.text?.toString()?.startsWith("履歴保存はOFF")==true })
    }
    @Test fun clipboardHistorySupportsPickingIndividualDeletionAndClearing() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.clipboardHistory,true); app.settings.state.first { it.clipboardHistory } }
        copy("一つ目の文章"); await("first clip") { app.clipboard.current().any { it.text=="一つ目の文章" } }
        copy("二つ目の文章"); await("second clip") { app.clipboard.current().size==2 }
        hold(rect("貼り付け")); assertEquals("",inputText())
        clickLabel("履歴 2：一つ目の文章"); assertEquals("一つ目の文章",inputText())
        hold(rect("貼り付け")); clickLabel("履歴 2を削除"); await("one clip remains") { app.clipboard.current().size==1 }
        clickLabel("全削除"); clickLabel("削除する"); await("all clips deleted") { app.clipboard.current().isEmpty() }
        clickLabel("閉じる"); hold(rect("貼り付け")); assertTrue("Opening history must not resurrect the system's current clip",app.clipboard.current().isEmpty())
        captureHardware("clipboard-history")
    }
    @Test fun markedSensitiveClipIsNotRetainedAndDisablingErasesHistory() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.clipboardHistory,true); app.settings.state.first { it.clipboardHistory } }
        copy("保存できる普通の文章"); await("ordinary clip saved") { app.clipboard.current().size==1 }
        copy("機密の短文",true); SystemClock.sleep(250); assertFalse(app.clipboard.current().any { it.text=="機密の短文" })
        touch(rect("貼り付け")); assertEquals("機密の短文",inputText())
        runBlocking { app.settings.set(Prefs.clipboardHistory,false); app.settings.state.first { !it.clipboardHistory } }
        await("history file erased") { app.clipboard.state.value.isEmpty() && !java.io.File(app.noBackupFilesDir,"clipboard-history.json").exists() }
    }
    @Test fun passwordFieldsDoNotCaptureOrShowClipboardHistory() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.clipboardHistory,true); app.settings.state.first { it.clipboardHistory } }
        main { editor.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; activity.getSystemService(InputMethodManager::class.java).restartInput(editor) }
        await("password EditorInfo") { "sensitive=true" in diagnostic() }
        copy("保存してはいけない文章"); SystemClock.sleep(250); assertTrue(app.clipboard.current().isEmpty())
        hold(rect("貼り付け")); assertNull(findNode { it.text?.toString()?.startsWith("クリップボード履歴・")==true })
    }
    @Test fun screenEnterNewlinesPhysicalEnterSendsAndShiftEnterNewlines() {
        var sends=0
        main {
            editor.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editor.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            editor.setOnEditorActionListener { _,action,_ -> if (action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sends++; true } else false }
            editor.setText("本文"); editor.setSelection(editor.length()); activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
        }
        await("SEND EditorInfo") { "action=4" in diagnostic() }
        touch(rect("↵")); assertEquals("本文\n",inputText()); main { assertEquals(0,sends) }
        key(KeyEvent.KEYCODE_ENTER); main { assertEquals(1,sends) }
        key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_SHIFT_ON); assertEquals("本文\n\n",inputText()); main { assertEquals(1,sends) }
    }
    @Test fun enterSettingsCanSelectActionsWhileSearchFieldsKeepSearch() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        var searches=0
        main {
            editor.setSingleLine(true); editor.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            editor.setOnEditorActionListener { _,a,_ -> if (a==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searches++; true } else false }
            activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
        }
        await("SEARCH EditorInfo") { "action=3" in diagnostic() }
        runBlocking { app.settings.set(Prefs.screenEnter,"NEWLINE"); app.settings.state.first { it.screenEnter=="NEWLINE" } }
        touch(rect("↵")); main { assertEquals(1,searches) }; assertEquals("",inputText())
        key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_SHIFT_ON); main { assertEquals(2,searches) }; assertEquals("",inputText())
    }
    @Test fun generalGreetingAndContinuationPredictionsWorkWithoutLearning() {
        letters("OHA"); await("dictionary greeting completions") { findNode { it.text?.toString()=="おはようございます" }!=null }
        assertNotNull(findNode { it.text?.toString()=="おはよう" }); key(KeyEvent.KEYCODE_ESCAPE)
        letters("KYOUHA"); key(KeyEvent.KEYCODE_SPACE); key(KeyEvent.KEYCODE_ENTER)
        await("general context") { findNode { it.text?.toString()=="いい天気ですね" }!=null }
    }
    @Test fun arbitraryLongInputIsNotSavedAsOneLearningRecord() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.learning.awaitLoaded(); app.settings.set(Prefs.learningEnabled,true); app.settings.state.first { it.learningEnabled } }
        letters("A".repeat(60)); key(KeyEvent.KEYCODE_ENTER); assertEquals("あ".repeat(60),inputText())
        SystemClock.sleep(300)
        assertFalse(app.learning.state.value.entries.any { it.reading=="あ".repeat(60) })
    }
    @Test fun deletingLearningRejectsWorkPlannedBeforeDeletion() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.learning.awaitLoaded(); app.settings.set(Prefs.learningEnabled,true); app.settings.state.first { it.learningEnabled } }
        val generation=app.learning.generation; val revision=app.learning.state.value.revision
        app.learning.clear(); await("learning erased") { app.learning.state.value.revision>revision && app.learning.state.value.entries.isEmpty() }
        app.learning.record("かんじ","漢字",generation); app.learning.recordContinuation("を","みせ","見せ",generation)
        SystemClock.sleep(200); assertTrue(app.learning.state.value.entries.isEmpty()); assertTrue(app.learning.state.value.continuations.isEmpty())
    }
    @Test fun newPreferencesAndDictionaryEditsSurviveActivityRecreation() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        val original=app.settings.state.value.p[Prefs.dictionary] ?: "{}"
        try {
            runBlocking {
                app.settings.set(Prefs.japaneseLayout,"QWERTY"); app.settings.set(Prefs.englishLayout,"FLICK")
                app.settings.set(Prefs.screenEnter,"ACTION"); app.settings.set(Prefs.physicalEnter,"NEWLINE"); app.settings.set(Prefs.clipboardLimit,17)
                app.settings.addWord("てすとよう","編集前"); app.settings.addWord("てすとよう","残す単語")
                app.settings.editWord("てすとよう","編集前","てすとご","編集後")
                app.settings.state.first { it.japaneseQwerty && it.englishFlick && it.clipboardLimit==17 && it.dictionary["てすとご"]?.contains("編集後")==true }
            }
            assertTrue(app.settings.state.value.dictionary["てすとよう"].orEmpty().contains("残す単語"))
            assertFalse(app.settings.state.value.dictionary["てすとよう"].orEmpty().contains("編集前"))
            val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
            try {
                main { activity.recreate() }
                activity=monitor.waitForActivityWithTimeout(10_000) as MainActivity
            } finally { instrumentation.removeMonitor(monitor) }
            await("recreated editor") { var present=false; main { present=activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("test_input")!=null }; present }
            main {
                editor=activity.findViewById<View>(android.R.id.content).findViewWithTag("test_input"); editor.id=View.generateViewId(); editor.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
                ImeControl.request(DisplayMode.FULL); activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor,0)
            }
            await("recreated window focus") { activity.hasWindowFocus() && imeHeight()>300 }
            await("recreated input connection") { "fieldId=${editor.id}\n" in diagnostic().replace("\r\n","\n") }
            instrumentation.waitForIdleSync()
            touch(rect("k")); touch(rect("a")); assertEquals("か",inputText())
            val saved=app.settings.state.value
            assertEquals("ACTION",saved.screenEnter); assertEquals("NEWLINE",saved.physicalEnter); assertEquals(17,saved.clipboardLimit)
            assertTrue(saved.japaneseQwerty && saved.englishFlick)
        } finally { runBlocking { app.settings.set(Prefs.dictionary,original) } }
    }
    private fun findNode(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node==null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.windows.filter { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }.firstNotNullOfOrNull { search(it.root) }
    }
    private fun findLabel(label: String): AccessibilityNodeInfo? =
        findNode { it.text?.toString()==label || it.contentDescription?.toString()==label }
            ?: findNode { it.contentDescription?.toString()?.startsWith(label)==true }

    private fun rect(text: String): Rect {
        var result: AccessibilityNodeInfo?=null
        try { await("IME key $text") { result=findLabel(text); result!=null } }
        catch (e: AssertionError) {
            val labels=mutableListOf<String>()
            fun collect(n: AccessibilityNodeInfo?) { if (n==null) return; n.text?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add); for (i in 0 until n.childCount) collect(n.getChild(i)) }
            instrumentation.uiAutomation.windows.filter { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }.forEach { collect(it.root) }
            throw AssertionError("IME key $text absent; current test UI: ${labels.joinToString(" | ")}",e)
        }
        return Rect().also { result!!.getBoundsInScreen(it) }
    }
    private fun touch(rect: Rect,dx: Int=0,dy: Int=0) {
        val now=SystemClock.uptimeMillis(); val x=rect.exactCenterX(); val y=rect.exactCenterY()
        fun inject(action: Int,xv: Float,yv: Float,t: Long) { val e=MotionEvent.obtain(now,t,action,xv,yv,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }; instrumentation.uiAutomation.injectInputEvent(e,true); e.recycle() }
        inject(MotionEvent.ACTION_DOWN,x,y,now)
        if (dx!=0 || dy!=0) inject(MotionEvent.ACTION_MOVE,x+dx,y+dy,now+30)
        inject(MotionEvent.ACTION_UP,x+dx,y+dy,now+60)
        instrumentation.waitForIdleSync()
    }
    private fun doubleTapShift() {
        val bounds=rect("Shift：小文字")
        // Do not wait for the whole IME to become idle between the two taps.
        repeat(2) {
            val now=SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                e.source=InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(e,false)) } finally { e.recycle() }
            }
            if (it==0) SystemClock.sleep(85)
        }
        await("caps lock after two quick taps") { findNode { it.contentDescription?.toString()=="Shift：大文字固定" }!=null }
    }
    private fun clickLabel(text: String) {
        // Translation changes the IME window height. Use the native accessibility action
        // instead of screen coordinates that can briefly refer to its previous position.
        // Learning may replace a candidate view between its lookup and the action.
        await("IME action $text") {
            val node=findLabel(text)
            val clicked=node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            if (!clicked) node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            clicked
        }
        instrumentation.waitForIdleSync()
    }
    private fun commitTsudukiwo() {
        letters("TSUDUKIWO"); key(KeyEvent.KEYCODE_SPACE)
        await("続きを converted") { inputText().endsWith("続きを") }
        key(KeyEvent.KEYCODE_ENTER)
    }
    private fun hardwareNode(description: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node==null) return null
            if (node.contentDescription?.toString()==description) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.windows.firstNotNullOfOrNull { search(it.root) }
    }
    private fun captureHardware(name: String) {
        val folder=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"hardware").apply { mkdirs() }
        val image=instrumentation.uiAutomation.takeScreenshot()
        java.io.File(folder,"$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }
    private fun assertCandidateOnly() {
        // Android can retain its IME navigation/caption bar, even with a zero-height input view.
        assertTrue("Only the OS navigation area remains",imeHeight()<=(48*activity.resources.displayMetrics.density).toInt())
        assertNull(findNode { it.contentDescription?.toString()?.startsWith("あ行。")==true })
    }
    private fun controlNotification(): Notification? = instrumentation.targetContext.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id==KeyboardVisibilityNotification.ID }?.notification
    private fun enableControlNotification() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        if (android.os.Build.VERSION.SDK_INT>=33) assertEquals("Grant notification permission on the disposable emulator first",PackageManager.PERMISSION_GRANTED,app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        runBlocking { app.settings.set(Prefs.visibilityNotification,true); app.settings.state.first { it.visibilityNotification } }
        KeyboardVisibilityNotification.refresh(app)
        await("control notification posted") { controlNotification()!=null }
    }
    private fun bottomEditor(multiline: Boolean=false, reportsCursor: Boolean=true) {
        ImeControl.request(DisplayMode.HIDDEN); await("letter keys hidden") { imeHeight()==0 }
        main {
            val bars=WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.mandatorySystemGestures()
            val margin=(ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(bars)?.bottom ?: 0)+4
            val overlay=android.widget.FrameLayout(activity)
            editor=object : EditText(activity) {
                override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
                    val connection=super.onCreateInputConnection(outAttrs) ?: return null
                    if (reportsCursor) return connection
                    return object : android.view.inputmethod.InputConnectionWrapper(connection,false) {
                        override fun requestCursorUpdates(cursorUpdateMode: Int)=false
                        override fun requestCursorUpdates(cursorUpdateMode: Int,cursorUpdateFilter: Int)=false
                    }
                }
            }.apply {
                id=View.generateViewId()
                setSingleLine(!multiline)
                inputType=InputType.TYPE_CLASS_TEXT or (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                hint="画面下端の入力欄"
                setBackgroundColor(android.graphics.Color.WHITE)
            }
            val height=((if (multiline) 96 else 56)*activity.resources.displayMetrics.density).toInt()
            overlay.addView(editor,android.widget.FrameLayout.LayoutParams(-1,height,Gravity.BOTTOM).apply { bottomMargin=margin })
            activity.addContentView(overlay,android.view.ViewGroup.LayoutParams(-1,-1))
            assertTrue("The bottom editor can take input focus",editor.requestFocus())
        }
        instrumentation.waitForIdleSync()
        // Bind the replacement editor without requesting that Android show the keyboard.
        main { activity.getSystemService(InputMethodManager::class.java).restartInput(editor) }
        await("bottom editor served") { "fieldId=${editor.id}\n" in diagnostic().replace("\r\n","\n") }
    }
    @Test fun hiddenCaptionLeavesTheBottomEditorVisibleAndTouchableDuringHardwareConversion() {
        bottomEditor(); letters("KYOU")
        assertEquals("きょう",inputText())
        key(KeyEvent.KEYCODE_SPACE)
        await("conversion list with the caption setting enabled") { hardwareNode("物理候補：今日")?.isSelected==true }
        assertEquals("今日",inputText())
        assertEquals("Candidate-only IME must reserve no bottom space",0,imeHeight())
        captureHardware("bottom-editor-with-hidden-caption-candidates")
        key(KeyEvent.KEYCODE_SPACE)
        assertNotEquals("今日",inputText())
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("今日",inputText())
        key(KeyEvent.KEYCODE_ESCAPE)
        assertEquals("きょう",inputText())
        assertNull(hardwareNode("物理キーボードの候補一覧"))
        key(KeyEvent.KEYCODE_HENKAN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("今日",inputText())
        assertNull(hardwareNode("物理キーボードの候補一覧"))
        assertEquals(0,imeHeight())
        captureHardware("bottom-editor-after-hidden-conversion")
        var touched=false
        val bounds=Rect()
        main {
            editor.setOnTouchListener { _,event -> if (event.action==MotionEvent.ACTION_DOWN) touched=true; false }
            val origin=IntArray(2).also(editor::getLocationOnScreen)
            bounds.set(origin[0],origin[1],origin[0]+editor.width,origin[1]+editor.height)
            val safe=Rect().also(activity.window.decorView::getWindowVisibleDisplayFrame)
            assertTrue("The bottom field $bounds stays inside the visible frame $safe",safe.contains(bounds))
        }
        assertTrue(bounds.height()>40)
        touch(Rect(bounds.left,bounds.centerY(),bounds.right,bounds.bottom-16))
        main { assertTrue("The IME must not eat taps at the bottom of the input field",touched) }
    }
    private fun allowCandidateWindowWhileHidden(allow: Boolean=true) {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking {
            app.settings.set(Prefs.hideImeCaption,!allow)
            app.settings.state.first { it.hideImeCaption!=allow }
        }
    }
    @Test fun hardwareCandidatesKeepBottomMultilineEditorClearWithoutCursorUpdates() {
        bottomEditor(multiline=true,reportsCursor=false)
        letters("KYOU"); key(KeyEvent.KEYCODE_SPACE)
        await("candidate visible") { hardwareNode("物理候補：今日")?.isVisibleToUser==true }
        val panel=hardwareNode("物理キーボードの候補一覧")!!
        val panelBounds=Rect().also(panel::getBoundsInScreen)
        main {
            val editorOrigin=IntArray(2).also(editor::getLocationOnScreen)
            val safe=Rect().also(activity.window.decorView::getWindowVisibleDisplayFrame)
            assertTrue("Candidate window ${panelBounds} stays within ${safe}",safe.contains(panelBounds))
            assertTrue("Candidate window leaves bottom editor and caret visible",panelBounds.bottom<=editorOrigin[1])
            val decor=activity.window.decorView
            val nav=ViewCompat.getRootWindowInsets(decor)?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            val decorOrigin=IntArray(2).also(decor::getLocationOnScreen)
            // The optional IME caption can shrink the visible frame beyond the system navigation bar.
            val navTop=decorOrigin[1]+decor.height-(nav?.bottom ?: 0)
            assertTrue("Bottom of the field is above system navigation",editorOrigin[1]+editor.height<=navTop)
        }
        for (index in 0 until panel.childCount) {
            val row=panel.getChild(index) ?: continue
            assertTrue("Candidate row is visible",row.isVisibleToUser)
            assertTrue(panelBounds.contains(Rect().also(row::getBoundsInScreen)))
        }
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("今日",inputText())
        assertNull(hardwareNode("物理キーボードの候補一覧"))
        assertEquals(0,imeHeight())
    }
    @Test fun captionSettingCanRestoreTheBarAndFullKeyboardRestoresItAutomatically() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        allowCandidateWindowWhileHidden()
        ImeControl.request(DisplayMode.HIDDEN); await("keyboard hidden") { imeHeight()==0 }
        letters("KYOU"); key(KeyEvent.KEYCODE_SPACE)
        await("optional OS caption present") { imeHeight()>0 }
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("今日",inputText())
        allowCandidateWindowWhileHidden(false)
        await("caption and candidate host closed") { imeHeight()==0 && hardwareNode("物理キーボードの候補一覧")==null &&
            "candidateHost=false" in diagnostic() && "windowShown=false" in diagnostic() }
        ImeControl.request(DisplayMode.FULL); await("full keyboard restored") { imeHeight()>300 }
        val command="dumpsys activity service ${app.packageName}/io.github.user03715910.dualkey.ime.DualKeyService"
        var diagnostic=""
        await("normal keyboard caption restored") {
            diagnostic=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }
            "captionVisible=true" in diagnostic
        }
    }
    @Test fun notificationActionTogglesTheSameEditorWithoutLosingItsComposingText() {
        enableControlNotification(); letters("KA")
        controlNotification()!!.actions.first().actionIntent.send()
        await("notification hides keyboard") { imeHeight()==0 && ImeControl.state.value.mode==DisplayMode.HIDDEN }
        assertEquals("か",inputText())
        main { assertTrue(editor.hasFocus()); assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)>=0) }
        letters("NA"); assertEquals("かな",inputText())
        controlNotification()!!.actions.first().actionIntent.send()
        await("same notification restores keyboard") { imeHeight()>300 }
        assertEquals("かな",inputText())
        captureHardware("notification-restored-keyboard")
    }
    @Test fun notificationShadeTapKeepsTheOriginalInputField() {
        enableControlNotification(); letters("KA")
        assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        var title: AccessibilityNodeInfo?=null
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node==null) return null
            if (node.text?.toString()?.contains("キーボード表示切替")==true) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        try {
            await("notification visible in shade") {
                title=instrumentation.uiAutomation.windows.firstNotNullOfOrNull { search(it.root) }
                title!=null
            }
        } catch (failure: AssertionError) {
            captureHardware("notification-shade-lookup-failure")
            val labels=mutableListOf<String>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node==null) return
                node.text?.toString()?.let(labels::add); node.contentDescription?.toString()?.let(labels::add)
                for (i in 0 until node.childCount) collect(node.getChild(i))
            }
            instrumentation.uiAutomation.windows.forEach { labels.add("windowType=${it.type}; root=${it.root!=null}"); collect(it.root) }
            throw AssertionError(labels.joinToString(" | "),failure)
        }
        captureHardware("notification-shade")
        touch(Rect().also { title!!.getBoundsInScreen(it) })
        await("shade notification toggles") { ImeControl.state.value.mode==DisplayMode.HIDDEN }
        shell("cmd statusbar collapse")
        await("keyboard hidden after shade closes") { imeHeight()==0 }
        await("original input window focused after shade closes") { activity.hasWindowFocus() && ImeControl.state.value.editorActive }
        instrumentation.waitForIdleSync()
        assertEquals("か",inputText()); main { assertTrue(editor.hasFocus()) }
        letters("NA"); assertEquals("かな",inputText())
    }
    @Test fun deferredNotificationChoiceAppliesToTheNextEditorAndDoesNotOpenAnotherActivity() {
        enableControlNotification()
        main { activity.findViewById<View>(R.id.nav_settings).performClick() }
        await("no editor focused") { !ImeControl.state.value.editorActive }
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        for (expected in listOf(DisplayMode.FULL,DisplayMode.HIDDEN,DisplayMode.FULL)) {
            controlNotification()!!.actions.first().actionIntent.send()
            await("deferred $expected") { app.settings.state.value.pendingNotificationDisplay==expected }
        }
        assertTrue(activity.hasWindowFocus())
        // Emulate loss of the volatile request; the persisted choice must still be consumed.
        ImeControl.requests.value=null
        main { activity.findViewById<View>(R.id.nav_home).performClick() }
        await("home editor") { var found=false; main { found=activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("test_input")!=null }; found }
        main { editor=activity.findViewById<View>(android.R.id.content).findViewWithTag("test_input"); editor.requestFocus() }
        await("deferred full mode") { ImeControl.state.value.editorActive && imeHeight()>300 }
        await("deferred choice consumed") { app.settings.state.value.pendingNotificationDisplay==null }
        letters("KA"); assertEquals("か",inputText())
    }
    @Test fun controlNotificationContainsNoInputAndCanBeDisabledIndependently() {
        enableControlNotification(); letters("NIHONGO")
        val notification=controlNotification()!!
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(inputText()))
        val oldAction=notification.actions.first().actionIntent
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.visibilityNotification,false); app.settings.state.first { !it.visibilityNotification } }
        await("control notice removed") { controlNotification()==null }
        oldAction.send(); SystemClock.sleep(250)
        assertTrue(imeHeight()>300); assertEquals("にほんご",inputText())
    }
    @Test fun disablingNotificationCancelsItsDeferredDisplayChoice() {
        enableControlNotification()
        ImeControl.request(DisplayMode.HIDDEN); await("manual hidden mode") { imeHeight()==0 && ImeControl.state.value.mode==DisplayMode.HIDDEN }
        main { activity.findViewById<View>(R.id.nav_settings).performClick() }
        await("settings without an editor") { !ImeControl.state.value.editorActive }
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        controlNotification()!!.actions.first().actionIntent.send()
        await("queued show command") { app.settings.state.value.pendingNotificationDisplay==DisplayMode.FULL }
        runBlocking { app.settings.set(Prefs.visibilityNotification,false); app.settings.state.first { !it.visibilityNotification } }
        await("volatile notification command cancelled") { ImeControl.requests.value?.notification!=true }
        main { activity.findViewById<View>(R.id.nav_home).performClick() }
        await("home editor") { var found=false; main { found=activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("test_input")!=null }; found }
        main { editor=activity.findViewById<View>(android.R.id.content).findViewWithTag("test_input"); editor.requestFocus() }
        await("new editor remains hidden") { ImeControl.state.value.editorActive && imeHeight()==0 && ImeControl.state.value.mode==DisplayMode.HIDDEN }
        await("persisted notification command cancelled") { app.settings.state.value.pendingNotificationDisplay==null }
        letters("KA"); assertEquals("か",inputText())
    }
    @Test fun notificationPermissionDoesNotAffectOrdinaryInput() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.visibilityNotification,true); app.settings.state.first { it.visibilityNotification } }
        KeyboardVisibilityNotification.refresh(app)
        if (KeyboardVisibilityNotification.permitted(app)) await("allowed control notice") { controlNotification()!=null }
        else assertNull(controlNotification())
        letters("KYOU"); key(KeyEvent.KEYCODE_SPACE); key(KeyEvent.KEYCODE_ENTER); assertEquals("今日",inputText())
    }
    @Test fun physicalBackspaceDeletesDisplayedKanaAndUnfinishedRomaji() {
        letters("KA"); assertEquals("か",inputText()); key(KeyEvent.KEYCODE_DEL); assertEquals("",inputText())
        letters("K"); assertEquals("k",inputText()); key(KeyEvent.KEYCODE_DEL); assertEquals("",inputText())
        letters("KYO"); key(KeyEvent.KEYCODE_DEL); assertEquals("き",inputText()); key(KeyEvent.KEYCODE_DEL); assertEquals("",inputText())
        letters("NIHONGO"); key(KeyEvent.KEYCODE_DEL); assertEquals("にほん",inputText())
        letters("MO"); assertEquals("にほんも",inputText()); key(KeyEvent.KEYCODE_ENTER)
        key(KeyEvent.KEYCODE_DEL); assertEquals("にほん",inputText())
    }
    @Test fun hardwareRomajiRepeatsAndDoubleNReplaceTheComposingSpan() {
        key(KeyEvent.KEYCODE_W); assertEquals("w",inputText())
        key(KeyEvent.KEYCODE_W,repeat=1); assertEquals("ww",inputText())
        key(KeyEvent.KEYCODE_W,repeat=2); assertEquals("www",inputText())
        key(KeyEvent.KEYCODE_ENTER); assertEquals("www",inputText())
        letters("NN"); assertEquals("wwwん",inputText())
        letters("K"); assertEquals("wwwんk",inputText())
        letters("A"); assertEquals("wwwんか",inputText())
        key(KeyEvent.KEYCODE_DEL); assertEquals("wwwん",inputText())
        key(KeyEvent.KEYCODE_ENTER); assertEquals("wwwん",inputText())
        key(KeyEvent.KEYCODE_DEL); assertEquals("www",inputText())
        letters("NNK"); key(KeyEvent.KEYCODE_DEL); assertEquals("wwwん",inputText())
        letters("A"); assertEquals("wwwんな",inputText())
        key(KeyEvent.KEYCODE_ZENKAKU_HANKAKU)
        letters("WW"); assertEquals("wwwんなww",inputText())
        key(KeyEvent.KEYCODE_SPACE); assertEquals("wwwんなww ",inputText())
    }

    @Test fun screenQwertyUsesTheSameRomajiRules() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.japaneseLayout,"QWERTY"); app.settings.state.first { it.japaneseQwerty } }
        val w=rect("w")
        repeat(3) { touch(w) }
        assertEquals("www",inputText())
        touch(rect("↵"))
        val n=rect("n")
        touch(n); touch(n)
        assertEquals("wwwん",inputText())
        touch(w); assertEquals("wwwんw",inputText())
        touch(rect("a")); touch(rect("↵"))
        assertEquals("wwwんわ",inputText())
    }
    @Test fun physicalJapaneseSymbolsAreFullWidthButEnglishSymbolsStayAscii() {
        key(KeyEvent.KEYCODE_SLASH,KeyEvent.META_SHIFT_ON); key(KeyEvent.KEYCODE_1,KeyEvent.META_SHIFT_ON)
        key(KeyEvent.KEYCODE_9,KeyEvent.META_SHIFT_ON); key(KeyEvent.KEYCODE_0,KeyEvent.META_SHIFT_ON)
        key(KeyEvent.KEYCODE_1); assertEquals("？！（）1",inputText())
        key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_ZENKAKU_HANKAKU)
        key(KeyEvent.KEYCODE_SLASH,KeyEvent.META_SHIFT_ON); key(KeyEvent.KEYCODE_1,KeyEvent.META_SHIFT_ON)
        assertEquals("？！（）1?!",inputText())
    }

    private fun resetPunctuationEditor(text: String = "", type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0) {
        main {
            editor.id = View.generateViewId()
            editor.inputType = type
            editor.imeOptions = options
            editor.setText(text)
            editor.setSelection(text.length)
            activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
        }
        await("new punctuation editor") { "fieldId=${editor.id}\n" in diagnostic().replace("\r\n", "\n") }
    }

    @Test fun punctuationFollowsTextForPhysicalAndScreenKeys() {
        letters("KONNICHIWA")
        clickLabel("こんにちは")
        key(KeyEvent.KEYCODE_1, KeyEvent.META_SHIFT_ON)
        assertEquals("こんにちは！", inputText())
        key(KeyEvent.KEYCODE_ENTER)
        key(KeyEvent.KEYCODE_EISU)
        key(KeyEvent.KEYCODE_SLASH, KeyEvent.META_SHIFT_ON)
        assertEquals("こんにちは！？", inputText())
        key(KeyEvent.KEYCODE_SPACE)
        letters("REALLY")
        key(KeyEvent.KEYCODE_KANA)
        key(KeyEvent.KEYCODE_SLASH, KeyEvent.META_SHIFT_ON)
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("こんにちは！？ really?", inputText())

        resetPunctuationEditor("Hello日本語　")
        touch(rect("記号"))
        touch(rect("?"))
        assertEquals("Hello日本語　？", inputText())
        resetPunctuationEditor("日本語abc ")
        touch(rect("記号"))
        touch(rect("!"))
        assertEquals("日本語abc !", inputText())
    }

    @Test fun protectedFieldsAndExplicitWidthChoicesStayLiteral() {
        val textFields = listOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        for (variation in textFields) {
            resetPunctuationEditor("日本語", InputType.TYPE_CLASS_TEXT or variation)
            key(KeyEvent.KEYCODE_SLASH, KeyEvent.META_SHIFT_ON)
            assertEquals("日本語?", inputText())
        }
        resetPunctuationEditor("日本語", options = EditorInfo.IME_FLAG_FORCE_ASCII)
        key(KeyEvent.KEYCODE_1, KeyEvent.META_SHIFT_ON)
        assertEquals("日本語!", inputText())

        resetPunctuationEditor("本当")
        letters("HATENA")
        clickLabel("?")
        assertEquals("本当?", inputText())
        resetPunctuationEditor("Really")
        letters("BIKKURI")
        clickLabel("！")
        assertEquals("Really！", inputText())
    }

    @Test fun selectedEllipsesPredictFromTheirStoredReadingAndSurviveReopening() {
        val app = instrumentation.targetContext.applicationContext as DualKeyApp
        val reading = "てんてんてん"
        fun counts() = app.learning.state.value.entries.filter { it.reading == reading }.associate { it.text to it.count }
        runBlocking {
            app.learning.awaitLoaded()
            app.settings.set(Prefs.learningEnabled, true)
            app.settings.state.first { it.learningEnabled }
        }
        val original = counts()
        try {
            if (InstrumentationRegistry.getArguments().getString("restartedLearning") == "true") {
                assertEquals(mapOf("⋯" to 7, "…" to 1), original)
                letters("TEN")
                await("restored short learned prefix visible") { findNode { it.text?.toString() == "⋯" } != null }
                return
            }
            for (mark in List(6) { "⋯" } + "…") {
                val count = counts()[mark] ?: 0
                letters("TENTENTEN")
                clickLabel(mark)
                await("explicit symbol selection recorded") { counts()[mark] == count + 1 }
            }
            assertEquals("⋯⋯⋯⋯⋯⋯…", inputText())
            assertEquals((original["⋯"] ?: 0) + 6, counts()["⋯"])
            assertEquals((original["…"] ?: 0) + 1, counts()["…"])

            val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reopened = LearningStore(app, readerScope) { true }
            try {
                runBlocking { withTimeout(10_000) { reopened.awaitLoaded() } }
                assertEquals(counts(), reopened.state.value.entries.filter { it.reading == reading }.associate { it.text to it.count })
            } finally { reopened.close(); readerScope.cancel() }

            letters("TENTEN")
            clickLabel("⋯")
            await("partial selection credited to the original reading") { counts()["⋯"] == (original["⋯"] ?: 0) + 7 }
            letters("TEN")
            await("short learned prefix visible") { findNode { it.text?.toString() == "⋯" } != null }
            ImeControl.request(DisplayMode.HIDDEN)
            await("keys hidden for learned prediction") { imeHeight()==0 }
            key(KeyEvent.KEYCODE_ESCAPE)
            letters("TEN")
            await("hardware panel uses learned prefix") { hardwareNode("物理候補：⋯")?.isVisibleToUser==true }
            assertEquals(0,imeHeight())
            ImeControl.request(DisplayMode.FULL)
            await("keys restored after the candidate-only window") { imeHeight()>300 }
            resetPunctuationEditor(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
            letters("TENTENTEN")
            clickLabel("⋯")
            letters("TEN")
            await("ordinary private candidates visible") { findNode { it.text?.toString() == "点" } != null }
            assertNull(findNode { it.text?.toString() == "⋯" })
            assertEquals((original["⋯"] ?: 0) + 7, counts()["⋯"])
        } finally { runBlocking { app.settings.set(Prefs.learningEnabled, false) } }
    }
    @Test fun zenkakuHankakuAndJisCompatibilityKeysToggleBothWays() {
        letters("KA"); key(KeyEvent.KEYCODE_ZENKAKU_HANKAKU); letters("KA"); assertEquals("かka",inputText())
        key(KeyEvent.KEYCODE_ZENKAKU_HANKAKU); letters("KA"); assertEquals("かkaか",inputText())
        key(KeyEvent.KEYCODE_GRAVE); letters("A"); assertEquals("かkaかa",inputText())
        key(KeyEvent.KEYCODE_GRAVE); letters("A"); assertEquals("かkaかaあ",inputText())
    }
    @Test fun usLayoutKeepsTheBacktickAsText() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.layout,"US"); app.settings.state.first { !it.jisKeyboard } }
        key(KeyEvent.KEYCODE_ZENKAKU_HANKAKU); key(KeyEvent.KEYCODE_GRAVE); letters("A")
        assertEquals("`a",inputText())
    }
    @Test fun physicalConversionShowsAListAndCyclesWithSpaceAndArrows() {
        letters("KYOU"); key(KeyEvent.KEYCODE_SPACE)
        await("floating conversion list") { hardwareNode("物理候補：今日")?.isSelected==true }
        captureHardware("conversion-full")
        assertTrue(activity.hasWindowFocus())
        key(KeyEvent.KEYCODE_SPACE); assertNotEquals("今日",inputText())
        key(KeyEvent.KEYCODE_DPAD_UP); assertEquals("今日",inputText())
        key(KeyEvent.KEYCODE_DPAD_DOWN); val second=inputText()
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertEquals("今日",inputText())
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(second,inputText())
        key(KeyEvent.KEYCODE_ENTER); assertEquals(second,inputText())
        assertNull(hardwareNode("物理キーボードの候補一覧"))
        main { assertEquals(-1,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)) }
    }
    @Test fun hiddenHardwareConversionShowsOnlyTheCandidateWindowAndAllowsTouch() {
        ImeControl.request(DisplayMode.HIDDEN); await("IME hidden") { imeHeight()==0 }
        letters("KYOU"); key(KeyEvent.KEYCODE_HENKAN)
        await("floating candidates while keyboard hidden") { hardwareNode("物理候補：今日")!=null }
        assertCandidateOnly(); captureHardware("conversion-hidden")
        touch(Rect().also { hardwareNode("物理候補：今日")!!.getBoundsInScreen(it) })
        instrumentation.waitForIdleSync(); assertEquals("今日",inputText())
        assertNull(hardwareNode("物理キーボードの候補一覧")); assertCandidateOnly()
    }
    @Test fun tabCyclesPostCommitPredictionsInHiddenModeAndShiftTabGoesBack() {
        ImeControl.request(DisplayMode.HIDDEN); await("IME hidden") { imeHeight()==0 }
        commitTsudukiwo()
        assertNull(hardwareNode("物理キーボードの候補一覧"))
        key(KeyEvent.KEYCODE_TAB)
        await("Tab selects first next word") { hardwareNode("物理候補：お願いします")?.isSelected==true }
        assertEquals("続きを",inputText()); assertCandidateOnly(); captureHardware("next-word-tab")
        key(KeyEvent.KEYCODE_TAB)
        await("Tab moves to the next prediction") { hardwareNode("物理候補：教えてください")?.isSelected==true }
        key(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON)
        await("Shift+Tab moves to the previous prediction") { hardwareNode("物理候補：お願いします")?.isSelected==true }
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きを教えてください",inputText())
        main { assertEquals(-1,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)) }
        assertNull(hardwareNode("物理キーボードの候補一覧"))
    }
    @Test fun hiddenHardwarePredictionsSupportModesAndPreserveCompositionOnEscape() {
        ImeControl.request(DisplayMode.HIDDEN); await("IME hidden") { imeHeight()==0 }
        letters("OHA")
        await("predictive list while typing") { hardwareNode("物理候補：おはよう")?.isVisibleToUser==true }
        key(KeyEvent.KEYCODE_ESCAPE)
        await("predictive list closed") { hardwareNode("物理キーボードの候補一覧")==null }
        assertEquals("おは",inputText())
        key(KeyEvent.KEYCODE_ESCAPE)
        assertEquals("",inputText())

        letters("NIKO")
        await("ordinary predictive candidates") { hardwareNode("物理候補：2個")!=null }
        key(KeyEvent.KEYCODE_F6)
        await("exact mode") { hardwareNode("物理候補の種類：絞り込み")?.isSelected==true }
        assertEquals("にこ",inputText())
        key(KeyEvent.KEYCODE_F6)
        await("emoji mode") { hardwareNode("物理候補：😊")?.isVisibleToUser==true }
        val face=Rect().also(hardwareNode("物理候補の種類：顔文字")!!::getBoundsInScreen)
        touch(face)
        await("face mode") { hardwareNode("物理候補：(^_^)")?.isVisibleToUser==true }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        captureHardware("hardware-prediction-face-selection")
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("(^_^)",inputText())
        await("panel closed on commit") { hardwareNode("物理キーボードの候補一覧")==null }
        assertEquals(0,imeHeight())
    }

    @Test fun hiddenHardwareCandidateListScrollsAndClosesWhenEditorLosesFocus() {
        ImeControl.request(DisplayMode.HIDDEN); await("IME hidden") { imeHeight()==0 }
        letters("KOU"); key(KeyEvent.KEYCODE_SPACE)
        await("scrollable conversion list") { hardwareNode("物理キーボードの候補一覧")?.isVisibleToUser==true }
        fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::scrollable) }
        }
        val list=scrollable(hardwareNode("物理キーボードの候補一覧")!!)!!
        val first=list.getChild(0)?.text
        assertTrue(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        await("list scrolled") {
            hardwareNode("物理キーボードの候補一覧")?.let(::scrollable)?.getChild(0)?.text?.let { it!=first }==true
        }
        main {
            val replacement=EditText(activity).apply { id=View.generateViewId(); isSingleLine=true }
            activity.findViewById<android.widget.LinearLayout>(R.id.page_content).addView(replacement)
            replacement.requestFocus()
        }
        await("candidate window follows editor lifecycle") { hardwareNode("物理キーボードの候補一覧")==null }
        assertEquals(0,imeHeight())
    }
    @Test fun tabWithoutPredictionsStillMovesToTheNextField() {
        lateinit var next: EditText
        main {
            next=EditText(activity).apply { id=View.generateViewId(); isSingleLine=true }
            activity.findViewById<android.widget.LinearLayout>(R.id.page_content).addView(next)
            editor.nextFocusForwardId=next.id
        }
        key(KeyEvent.KEYCODE_TAB)
        await("ordinary Tab focus navigation") { var focused=false; main { focused=next.hasFocus() }; focused }
    }
    @Test fun floatingCandidatesCancelOnEscapeTypingAndSelection() {
        letters("KYOU"); key(KeyEvent.KEYCODE_SPACE); key(KeyEvent.KEYCODE_ESCAPE)
        assertEquals("きょう",inputText()); assertNull(hardwareNode("物理キーボードの候補一覧"))
        key(KeyEvent.KEYCODE_SPACE); letters("HA")
        assertEquals("今日は",inputText()); assertNull(hardwareNode("物理キーボードの候補一覧"))
        key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_TAB)
        main { editor.setSelection(0,editor.length()) }
        await("candidate panel discarded after selection") { hardwareNode("物理キーボードの候補一覧")==null }
    }
    @Test fun nextWordsRemainAfterUnderlineDisappearsAndAppendOnTap() {
        commitTsudukiwo()
        main { assertEquals(-1,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)) }
        await("post-commit next word") { findNode { it.text?.toString()=="お願いします" }!=null }
        assertEquals("続きを",inputText())
        clickLabel("お願いします"); assertEquals("続きをお願いします",inputText())
        main { assertEquals(-1,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)) }
        clickLabel("。"); assertEquals("続きをお願いします。",inputText())
    }
    @Test fun hardwareDownAndEnterCanAcceptTheNextWord() {
        commitTsudukiwo(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きをお願いします",inputText())
        main { assertEquals(-1,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)) }
    }
    @Test fun hiddenKeyboardDoesNotUseArrowsForInvisibleNextWords() {
        commitTsudukiwo()
        key(KeyEvent.KEYCODE_K,KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        await("keyboard hidden") { imeHeight()==0 }
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きを\n",inputText())
    }
    @Test fun movingOrSelectingTextDiscardsOldNextWords() {
        commitTsudukiwo()
        await("next word shown") { findNode { it.text?.toString()=="お願いします" }!=null }
        main { editor.setSelection(0,editor.length()) }
        await("stale next word cleared") { findNode { it.text?.toString()=="お願いします" }==null }
        assertEquals("続きを",inputText())
    }
    @Test fun ordinaryTypingContinuesInsteadOfAutomaticallyAcceptingSuggestions() {
        commitTsudukiwo(); letters("NIHONGO")
        assertEquals("続きをにほんご",inputText())
        key(KeyEvent.KEYCODE_SPACE); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きを日本語",inputText())
    }
    @Test fun predictionSettingCanDisablePostCommitSuggestions() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.japanesePrediction,false); app.settings.state.first { !it.japanesePrediction } }
        commitTsudukiwo()
        assertNull(findNode { it.text?.toString()=="お願いします" })
    }
    @Test fun nextWordLearningRanksAChosenContinuationButPrivateFieldsUseDefaults() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking {
            app.learning.awaitLoaded(); val before=app.learning.state.value.revision; app.learning.clear()
            withTimeout(10_000) { app.learning.state.first { it.revision>before && it.entries.isEmpty() && it.continuations.isEmpty() } }
            app.settings.set(Prefs.learningEnabled,true); app.settings.state.first { it.learningEnabled }
        }
        fun resetEditor(privateMode: Boolean=false) {
            main {
                editor.setText("")
                editor.id=View.generateViewId()
                if (privateMode) editor.imeOptions=editor.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
            }
            await("new test editor session") { "fieldId=${editor.id}\n" in diagnostic().replace("\r\n","\n") }
        }
        fun chooseContinuation(times: Int) {
            repeat(times) {
                resetEditor()
                commitTsudukiwo(); clickLabel("見せてください")
            }
            runBlocking { withTimeout(10_000) {
                app.learning.state.first { snapshot ->
                    snapshot.continuations.any { c ->
                        "続きを".endsWith(c.context) && "見せてください".startsWith(c.text) && c.count>=times
                    }
                }
            } }
        }
        chooseContinuation(1)
        resetEditor()
        commitTsudukiwo(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きをお願いします",inputText())
        chooseContinuation(4)
        resetEditor()
        commitTsudukiwo(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きを見せてください",inputText())
        // Learning the immediately preceding selection may still be in flight.
        // Only the default continuation could have been newly learned in the private editor.
        fun defaultContinuationUses()=app.learning.state.value.continuations.filter {
            "続きを".endsWith(it.context) && "お願いします".startsWith(it.text)
        }.sumOf { it.count }
        val learningBeforePrivate=defaultContinuationUses()
        resetEditor(privateMode=true)
        commitTsudukiwo(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きをお願いします",inputText())
        SystemClock.sleep(300)
        assertEquals(learningBeforePrivate,defaultContinuationUses())
    }
    @Test fun physicalRomajiAndSpaceEnterConversion() {
        letters("KYOUHA"); await("きょうは") { inputText()=="きょうは" }
        key(KeyEvent.KEYCODE_SPACE); await("今日は") { inputText()=="今日は" }
        key(KeyEvent.KEYCODE_ENTER); assertEquals("今日は",inputText())
    }
    @Test fun learnedChoiceMovesToTopAndIsIgnoredInPrivateEditors() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking {
            app.settings.set(Prefs.learningEnabled,true); app.settings.state.first { it.learningEnabled }
            app.learning.awaitLoaded(); val before=app.learning.state.value.revision; app.learning.clear()
            withTimeout(10_000) { app.learning.state.first { it.revision>before && it.entries.isEmpty() } }
        }
        fun chooseKanji(times: Int) {
            repeat(times) {
                main { editor.text.clear() }
                letters("KANJI"); await("writing candidate") { findNode { it.text?.toString()=="漢字" }!=null }
                clickLabel("漢字"); await("confirmed spelling") { inputText()=="漢字" }
            }
        }
        fun awaitCount(count: Int) = runBlocking { withTimeout(10_000) {
            app.learning.state.first { it.entries.any { e -> e.reading=="かんじ" && e.text=="漢字" && e.count==count } }
        } }
        chooseKanji(1); awaitCount(1)
        main { editor.text.clear() }
        letters("KANJI"); key(KeyEvent.KEYCODE_SPACE); assertEquals("感じ",inputText())
        key(KeyEvent.KEYCODE_ESCAPE); key(KeyEvent.KEYCODE_ESCAPE)
        chooseKanji(3); awaitCount(4)
        main { editor.text.clear() }
        letters("KANJI"); key(KeyEvent.KEYCODE_SPACE); assertEquals("漢字",inputText())
        key(KeyEvent.KEYCODE_ESCAPE); key(KeyEvent.KEYCODE_ESCAPE)
        // A real second editor makes Android deliver an actual private EditorInfo.
        main {
            val privateEditor=EditText(activity).apply {
                inputType=InputType.TYPE_CLASS_TEXT
                imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            activity.findViewById<android.widget.LinearLayout>(R.id.page_content).addView(privateEditor)
            editor=privateEditor; editor.requestFocus()
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor,0)
        }
        await("private editor served") { var active=false; main { active=activity.getSystemService(InputMethodManager::class.java).isActive(editor) }; active }
        letters("KANJI"); key(KeyEvent.KEYCODE_SPACE); assertEquals("感じ",inputText())
        await("private alternate") { findNode { it.text?.toString()=="漢字" }!=null }
        clickLabel("漢字"); SystemClock.sleep(300)
        assertEquals(4,app.learning.state.value.entries.first { it.reading=="かんじ" && it.text=="漢字" }.count)
    }
    private fun gridCandidate(test: (String)->Boolean): AccessibilityNodeInfo? {
        val list=findNode { it.contentDescription?.toString()=="変換候補の一覧" }
        var back=0
        while (back++<120 && list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)==true) SystemClock.sleep(100)
        repeat(120) {
            val item=findNode { it.isVisibleToUser && it.contentDescription?.toString()?.let(test)==true }
            if (item!=null) return item
            val list=findNode { it.contentDescription?.toString()=="変換候補の一覧" }
            if (list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)!=true) SystemClock.sleep(60)
            else SystemClock.sleep(70)
        }
        return null
    }
    @Test fun candidatePagesCanShowMoreThanSixtyChoices() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.candidateCount,1000); app.settings.state.first { it.candidateCount==1000 } }
        letters("KOU"); key(KeyEvent.KEYCODE_SPACE); touch(rect("一覧"))
        val item=gridCandidate { it.startsWith("候補 61：") }
        assertNotNull("Scroll directly beyond sixty without any more button",item)
        assertNull(findNode { it.contentDescription?.toString()=="さらに候補を表示" })
        val word=item!!.contentDescription.toString().substringAfter('：')
        captureHardware("continuous-candidates")
        assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK)); await("61st choice committed") { inputText()==word }
    }
    @Test fun legacyCandidateLimitCanSearchForMore() {
        letters("KOU"); key(KeyEvent.KEYCODE_SPACE); touch(rect("一覧"))
        clickLabel("絞り込み")
        assertNotNull("Exact mode has its own scrollable list beyond the saved limit",gridCandidate { it.startsWith("候補 81：") })
        assertTrue(rect("すべて").right<=rect("絞り込み").left)
        assertNull(findNode { it.text?.toString()=="見る" || it.contentDescription?.toString()=="さらに候補を表示" })
    }
    @Test fun diamondFlickBubbleIsVisibleAndTracksTheDirection() {
        val key=rect("か行"); val density=activity.resources.displayMetrics.density
        val now=SystemClock.uptimeMillis(); val x=key.exactCenterX(); val y=key.exactCenterY()
        fun inject(action: Int,xv: Float,yv: Float) {
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,xv,yv,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.uiAutomation.injectInputEvent(event,true); event.recycle(); instrumentation.waitForIdleSync()
        }
        fun snapshot(name: String,left: Boolean) {
            SystemClock.sleep(200)
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            val folder=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"preview").apply { mkdirs() }
            java.io.File(folder,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            val bubbleX=(x-74*density).coerceIn(4*density,bitmap.width-152*density)
            val bubbleY=(key.top-154*density).coerceAtLeast(4*density)
            val sx=(bubbleX+74*density+(if (left) -39.44f else 0f)*density+12*density).toInt()
            val sy=(bubbleY+74*density).toInt()
            val color=bitmap.getPixel(sx,sy); bitmap.recycle()
            assertEquals("The selected preview cell is drawn above the key",android.graphics.Color.rgb(8,127,140),color)
        }
        try {
            inject(MotionEvent.ACTION_DOWN,x,y); snapshot("flick-ka-center.png",false)
            inject(MotionEvent.ACTION_MOVE,x-100*density,y); snapshot("flick-ka-left.png",true)
            inject(MotionEvent.ACTION_UP,x-100*density,y)
            await("left flick committed") { inputText()=="き" }
        } finally { inject(MotionEvent.ACTION_CANCEL,x,y) }
    }
    @Test fun feelingPhraseAndExpandedKanjiChoices() {
        letters("IIKANJI")
        await("feeling prediction") { findNode { it.text?.toString()=="いい感じ" }!=null }
        key(KeyEvent.KEYCODE_SPACE); assertEquals("いい感じ",inputText())
        key(KeyEvent.KEYCODE_ENTER); assertEquals("いい感じ",inputText())
        main { editor.text.clear() }
        letters("KANJI")
        await("both homophones") { findNode { it.text?.toString()=="感じ" }!=null && findNode { it.text?.toString()=="漢字" }!=null }
        val originalHeight=imeHeight()
        touch(rect("一覧"))
        await("expanded choices") { findNode { it.contentDescription?.toString()?.matches(Regex("候補 [0-9]+：漢字"))==true }!=null }
        assertTrue("Expansion replaces the key area",imeHeight()<=originalHeight+20)
        val kanji=findNode { it.contentDescription?.toString()?.matches(Regex("候補 [0-9]+：漢字"))==true }!!
        assertTrue(kanji.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("selected writing kanji") { inputText()=="漢字" }
        await("keys restored") { findNode { it.contentDescription?.toString()?.startsWith("あ行")==true }!=null }
    }
    @Test fun sentenceCompletionAndPredictionOffConversion() {
        letters("IIKAN")
        await("phrase completion") { findNode { it.text?.toString()=="いい感じ" }!=null }
        clickLabel("いい感じ"); await("completion committed") { inputText()=="いい感じ" }
        main { editor.text.clear() }
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        try {
            runBlocking { app.settings.set(Prefs.japanesePrediction,false); app.settings.state.first { !it.japanesePrediction } }
            letters("KANJIRU")
            assertNull(findNode { it.text?.toString()=="感じる" })
            key(KeyEvent.KEYCODE_SPACE); assertEquals("感じる",inputText())
            key(KeyEvent.KEYCODE_ENTER); assertEquals("感じる",inputText())
        } finally { runBlocking { app.settings.set(Prefs.japanesePrediction,true) } }
    }
    @Test fun realDictionarySearchLatency() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        val engine=app.japaneseDictionary.engine.value
        val samples=listOf("あ","かんじ","いいかんじ","きょうはにほ","きょうはいいてんきですね")
        val durations=mutableListOf<Long>()
        repeat(3) { samples.forEach { reading ->
            val before=SystemClock.elapsedRealtimeNanos()
            val values=engine.candidates(reading,true,30)
            durations.add((SystemClock.elapsedRealtimeNanos()-before)/1_000_000)
            assertTrue(values.isNotEmpty())
        } }
        println("Dictionary searches on Android: median=${durations.sorted()[durations.size/2]}ms, max=${durations.max()}ms")
        assertTrue("Indexed searches stay bounded",durations.max()<1500)
    }
    @Test fun candidatePresentationProbe() {
        val cases=listOf("NIKO" to "2個", "KYOU" to "今日", "OHA" to "おはよう",
            "OHAYOU" to "おはよう", "ONPU" to "音符", "TEN" to "点",
            "TENTENTEN" to "…", "ISU" to "椅子", "WATASHIHA" to "私は", "NIHONGO" to "日本語")
        val samples=mutableListOf<Long>()
        repeat(3) { pass ->
            for ((romaji, candidate) in cases) {
                main { editor.text.clear() }
                letters(romaji.dropLast(1))
                val start=SystemClock.elapsedRealtimeNanos()
                letters(romaji.takeLast(1))
                await("presented candidate $candidate") { findNode { it.text?.toString()==candidate }!=null }
                if (pass>0) samples.add((SystemClock.elapsedRealtimeNanos()-start)/1_000_000)
            }
        }
        val sorted=samples.sorted()
        val memory=android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
        android.util.Log.i("DualKeyPredictionProbe",
            "n=${sorted.size} median_ms=${sorted[sorted.size/2]} p95_ms=${sorted[(sorted.size*95+99)/100-1]} pss_kib=${memory.totalPss}")
    }
    @Test fun physicalContractedGeminatedAndN() {
        letters("KYONIHONGOSHACHATTANNA"); await("romaji sequence") { inputText()=="きょにほんごしゃちゃったんな" }
        key(KeyEvent.KEYCODE_ENTER); assertEquals("きょにほんごしゃちゃったんな",inputText())
    }
    @Test fun candidateArrowEscapeAndBackspace() {
        letters("KYOU"); key(KeyEvent.KEYCODE_SPACE); assertEquals("今日",inputText())
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertNotEquals("今日",inputText())
        key(KeyEvent.KEYCODE_ESCAPE); assertEquals("きょう",inputText())
        key(KeyEvent.KEYCODE_DEL); assertEquals("きょ",inputText())
    }
    @Test fun fullyHiddenStillAcceptsHardwareAndShortcutRestores() {
        ImeControl.request(DisplayMode.HIDDEN); await("IME hidden") { imeHeight()==0 }
        letters("KYO"); await("hidden hardware input") { inputText()=="きょ" }
        key(KeyEvent.KEYCODE_K,KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        await("shortcut restored keyboard") { imeHeight()>300 }
    }
    @Test fun customShortcutTogglesBothDirectionsAndDoesNotStealAnotherChord() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        val custom=ToggleShortcut("J",ToggleShortcut.CTRL or ToggleShortcut.SHIFT)
        runBlocking { app.settings.setDisplayShortcut(custom); app.settings.state.first { it.displayShortcut==custom } }
        val chord=KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        key(KeyEvent.KEYCODE_K,KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertTrue(imeHeight()>300)
        letters("KA")
        key(KeyEvent.KEYCODE_J,chord); await("custom chord hides") { imeHeight()==0 }
        key(KeyEvent.KEYCODE_J,chord,1); assertEquals(0,imeHeight())
        letters("NA"); assertEquals("かな",inputText())
        key(KeyEvent.KEYCODE_J,chord); await("same custom chord restores") { imeHeight()>300 }
        assertEquals("かな",inputText())
        key(KeyEvent.KEYCODE_J,chord or KeyEvent.META_ALT_ON); assertTrue(imeHeight()>300)
    }
    @Test fun visibilityShortcutCanBeDisabledAndCustomChoiceSurvivesInputRestart() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        val custom=ToggleShortcut("U",ToggleShortcut.CTRL or ToggleShortcut.ALT)
        runBlocking { app.settings.setDisplayShortcut(custom); app.settings.set(Prefs.shortcut,false); app.settings.state.first { !it.shortcut && it.displayShortcut==custom } }
        key(KeyEvent.KEYCODE_U,KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON); assertTrue(imeHeight()>300)
        runBlocking { app.settings.set(Prefs.shortcut,true); app.settings.state.first { it.shortcut } }
        main { activity.getSystemService(InputMethodManager::class.java).restartInput(editor) }
        SystemClock.sleep(200)
        key(KeyEvent.KEYCODE_U,KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON); await("stored chord hides after restart") { imeHeight()==0 }
        key(KeyEvent.KEYCODE_U,KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON); await("stored chord restores after restart") { imeHeight()>300 }
    }
    @Test fun compactModeUsesLessSpaceAndCanRecoverWithTouch() {
        val full=imeHeight()
        ImeControl.request(DisplayMode.COMPACT); await("compact height") { imeHeight() in 1 until full }
        touch(rect("全表示")); await("full height restored") { imeHeight()>=full }
    }
    @Test fun bilingualCandidatesIncludeChairAndLoanword() {
        letters("ISU"); key(KeyEvent.KEYCODE_SPACE)
        assertEquals("椅子",inputText())
        touch(rect("一覧"))
        assertNotNull(gridCandidate { it.substringAfter('：')=="イス" })
        assertNotNull(gridCandidate { it.substringAfter('：')=="チェア" })
        val chair=gridCandidate { it.substringAfter('：')=="chair" }
        assertNotNull(chair); assertTrue(chair!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)); assertEquals("chair",inputText())
    }
    @Test fun scriptCycleUsesMeaningAndKeepsTheWholeSpanUncommitted() {
        letters("ISU")
        clickLabel("表記を切り替え"); assertEquals("イス",inputText())
        clickLabel("表記を切り替え"); await("meaning, not romaji") { inputText()=="chair" }
        main { assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)>=0) }
        clickLabel("表記を切り替え"); assertEquals("いす",inputText())
        clickLabel("表記を切り替え"); assertEquals("イス",inputText())
        key(KeyEvent.KEYCODE_ENTER); assertEquals("イス",inputText())
        main { assertEquals(-1,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)) }
        clickLabel("表記を切り替え"); assertEquals("イス",inputText())
    }
    @Test fun scriptCycleFromKanjiDoesNotReplaceCommittedHistory() {
        letters("NIHONGO"); key(KeyEvent.KEYCODE_SPACE); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("日本語",inputText())
        letters("ISU"); key(KeyEvent.KEYCODE_SPACE); assertEquals("日本語椅子",inputText())
        clickLabel("表記を切り替え"); assertEquals("日本語イス",inputText())
        clickLabel("表記を切り替え"); await("only current span translated") { inputText()=="日本語chair" }
        key(KeyEvent.KEYCODE_ENTER)
        clickLabel("表記を切り替え"); assertEquals("日本語chair",inputText())
    }
    @Test fun cancelledScriptResultCannotChangeLaterTyping() {
        letters("ISU"); clickLabel("表記を切り替え"); clickLabel("表記を切り替え")
        key(KeyEvent.KEYCODE_ESCAPE); assertEquals("いす",inputText())
        letters("HA"); SystemClock.sleep(500)
        assertEquals("いすは",inputText())
    }
    @Test fun scriptCycleTranslatesWholeSentenceOnDeviceAndRestoresReading() {
        val engine=io.github.user03715910.dualkey.translation.TranslationEngine()
        try { Assume.assumeTrue("Download the Japanese model first",runBlocking { engine.ready() }) }
        finally { engine.close() }
        letters("KYOUHAIITENKIDESU")
        clickLabel("表記を切り替え"); assertEquals("キョウハイイテンキデス",inputText())
        clickLabel("表記を切り替え")
        await("whole sentence translated") { inputText().any { it in 'a'..'z' || it in 'A'..'Z' } }
        assertFalse(inputText().equals("kyouhaiitenkidesu",true))
        main { assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)>=0) }
        assertNotNull(findNode { it.contentDescription?.toString()=="powered by Google Translate" })
        clickLabel("表記を切り替え"); assertEquals("きょうはいいてんきです",inputText())
    }
    @Test fun flickDirectionsAndNumberRow() {
        touch(rect("あ行")); touch(rect("あ行"),dx=-100); touch(rect("あ行"),dy=-100)
        touch(rect("あ行"),dx=100); touch(rect("あ行"),dy=100)
        await("all flick directions") { inputText()=="あいうえお" }
        touch(rect("か行"),dy=-100)
        "1234567890".forEach { touch(rect(it.toString())) }; key(KeyEvent.KEYCODE_ENTER)
        assertEquals("あいうえおく1234567890",inputText())
    }
    @Test fun englishQwertyAndShiftCapsLock() {
        touch(rect("英語に切り替え")); touch(rect("h")); touch(rect("e")); touch(rect("l")); assertEquals("hel",inputText())
        touch(rect("⇧")); touch(rect("A")); touch(rect("b")); assertEquals("helAb",inputText())
        doubleTapShift()
        touch(rect("C")); touch(rect("D")); assertEquals("helAbCD",inputText())
        touch(rect("⇪")); touch(rect("e")); assertEquals("helAbCDe",inputText())
    }
    @Test fun compactLayoutUsesFourRowsAndShortDigits() {
        val density=activity.resources.displayMetrics.density
        val a=rect("あ行"); val ka=rect("か行"); val sa=rect("さ行"); val wa=rect("わ行")
        val digit=rect("1"); val delete=rect("⌫")
        assertTrue("Digits need a shorter row than kana",digit.height()<a.height())
        assertTrue("Three kana keys fit between the side controls",a.right<=ka.left && ka.right<=sa.left && sa.right<=delete.left)
        assertEquals("わ aligns with the central column",ka.left,wa.left)
        assertTrue("Four kana rows only",wa.bottom-a.top<=4*(a.height()+4*density))
        val landscape=activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screen=if (android.os.Build.VERSION.SDK_INT>=30) activity.windowManager.currentWindowMetrics.bounds.height() else activity.resources.displayMetrics.heightPixels
        assertTrue("Leave room above the keyboard: ${imeHeight()} of $screen pixels",imeHeight()<=screen*(if (landscape) .75 else .40))
        if (InstrumentationRegistry.getArguments().getString("capturePreview")=="true") {
            letters("KYOU"); key(KeyEvent.KEYCODE_SPACE)
            await("preview candidates") { findNode { it.text?.toString()=="今日" }!=null }
            val folder=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"preview").apply { mkdirs() }
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            java.io.File(folder,"keyboard-compact.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            screenshot.recycle()
        }
    }
    @Test fun sideArrowsSelectCandidatesAndMoveTheCursor() {
        letters("KYOU"); key(KeyEvent.KEYCODE_SPACE); assertEquals("今日",inputText())
        touch(rect("▶")); assertNotEquals("今日",inputText())
        assertNull("Touch controls leave the PC candidate panel",hardwareNode("物理キーボードの候補一覧"))
        touch(rect("◀")); assertEquals("今日",inputText())
        touch(rect("↵")); touch(rect("◀")); touch(rect("あ行")); touch(rect("↵"))
        await("cursor insertion") { inputText()=="今あ日" }
    }
    @Test fun flickConversionKeepsTheInlineCandidatesInsteadOfThePcPanel() {
        touch(rect("あ行")); assertEquals("あ",inputText())
        assertNull(hardwareNode("物理キーボードの候補一覧"))
        touch(rect("空白")); touch(rect("▶"))
        assertNull(hardwareNode("物理キーボードの候補一覧"))
        assertNotNull(findNode { it.text?.toString()=="一覧" })
        touch(rect("一覧"))
        assertNotNull(findNode { it.text?.toString()=="閉じる" })
        assertNull(hardwareNode("物理キーボードの候補一覧"))
        captureHardware("touch-candidates-only")
    }
    @Test fun bottomRowHandlesSmallVoicedKanaAndPunctuation() {
        touch(rect("か行")); touch(rect("小゛゜"))
        touch(rect("は行")); touch(rect("小゛゜")); touch(rect("小゛゜"))
        touch(rect("た行"),dy=-100); touch(rect("小゛゜"))
        touch(rect("、行")); touch(rect("、行"),dx=-100); touch(rect("↵"))
        assertEquals("がぱっ、。",inputText())
    }
    @Test fun physicalEnglishHonorsShiftAndCaps() {
        key(KeyEvent.KEYCODE_EISU)
        key(KeyEvent.KEYCODE_A); key(KeyEvent.KEYCODE_A,KeyEvent.META_SHIFT_ON); key(KeyEvent.KEYCODE_B)
        key(KeyEvent.KEYCODE_A,KeyEvent.META_CAPS_LOCK_ON); key(KeyEvent.KEYCODE_A,KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_SHIFT_ON)
        key(KeyEvent.KEYCODE_SPACE)
        assertEquals("aAbAa ",inputText())
    }
    @Test fun searchEditorCanPasteAndOpenClipboardHistory() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.clipboardHistory,true); app.settings.state.first { it.clipboardHistory } }
        try {
            main {
                editor.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                editor.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
            }
            await("search editor ready") { "action=3" in diagnostic() && "predictions=false" in diagnostic() }
            val sample="検索欄への貼り付け"
            copy(sample)
            clickLabel("貼り付け")
            await("search clipboard text committed") { inputText()==sample }
            await("open search clipboard history") {
                findNode { it.text?.toString()=="貼り付け" }?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)==true
            }
            await("search clipboard history") { findNode { it.text?.toString()==sample }!=null }
        } finally { runBlocking { app.settings.set(Prefs.clipboardHistory,false) } }
    }
    @Test fun searchEditorWithoutSuggestionsStillConvertsJapanese() {
        main {
            editor.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editor.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
        }
        await("search editor disables predictions") { "predictions=false" in diagnostic() }
        letters("TENKI")
        assertEquals("てんき",inputText())
        await("kana-to-kanji candidate") { findNode { it.text?.toString()=="天気" }!=null }
        touch(rect("空白"))
        await("space converts reading") { inputText()=="天気" }
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("天気",inputText())
    }
    @Test fun searchEditorWithOnlyCapitalizationFlagAcceptsJapanese() {
        val fieldId=View.generateViewId()
        main {
            // Kotodaman Dictionary changes its input type to this flag alone when focused.
            editor.id=fieldId
            editor.inputType=InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
        }
        await("flag-only search editor is active") {
            val state=diagnostic()
            "fieldId=$fieldId\n" in state.replace("\r\n","\n") && "editor=true" in state && imeHeight()>0
        }
        touch(rect("あ行")); touch(rect("か行")); touch(rect("↵"))
        assertEquals("あか",inputText())
    }
    @Test fun passwordDisablesSuggestionsAndTranslation() {
        main { editor.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; editor.setText(""); activity.getSystemService(InputMethodManager::class.java).restartInput(editor) }
        await("password input mode ready") { "sensitive=true" in diagnostic() && findNode { it.contentDescription?.toString()=="日本語に切り替え" }!=null && findNode { it.text?.toString()=="翻訳" }==null }
        letters("secret"); assertEquals("secret",inputText())
        var composingStart=0; main { composingStart=android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) }
        assertEquals(-1,composingStart)
        assertNull(findNode { it.text?.toString()=="翻訳" })
        assertNull(findNode { it.text?.toString()=="secret" })
    }
    @Test fun offlineStillAcceptsAndConvertsJapanese() {
        shell("svc wifi disable"); shell("svc data disable")
        try {
            val cm=activity.getSystemService(android.net.ConnectivityManager::class.java)
            await("network offline") { cm.activeNetwork==null }
            letters("NIHONGO"); key(KeyEvent.KEYCODE_SPACE); key(KeyEvent.KEYCODE_ENTER)
            assertEquals("日本語",inputText())
        } finally { shell("svc wifi enable"); shell("svc data enable") }
    }
    @Test fun translationPreviewAndCommit() {
        val engine=io.github.user03715910.dualkey.translation.TranslationEngine()
        try { Assume.assumeTrue("Download the model with TranslationDeviceTest first",runBlocking { engine.ready() }) }
        finally { engine.close() }
        letters("KONNICHIHA"); assertEquals("こんにちは",inputText())
        clickLabel("翻訳"); clickLabel("Googleで日→英"); clickLabel("この訳を入力")
        await("translated text committed") { inputText().any { it in 'a'..'z' || it in 'A'..'Z' } }
        assertNotEquals("こんにちは",inputText())
    }
}
