package io.github.user03715910.dualkey

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.*
import android.view.accessibility.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dualkey.core.DisplayMode
import io.github.dualkey.core.ToggleShortcut
import io.github.user03715910.dualkey.data.Prefs
import io.github.user03715910.dualkey.settings.MainActivity
import kotlinx.coroutines.runBlocking
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
        fail("Timed out: $label; input=${if (::editor.isInitialized) inputText() else "not ready"}; IME=${labels.joinToString(" | ")}")
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
        }
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("editor ready") { var found=false; main { found=activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("test_input")!=null }; found }
        await("focused activity") { activity.hasWindowFocus() }
        main {
            editor=activity.findViewById<View>(android.R.id.content).findViewWithTag("test_input")
            editor.setText(""); editor.requestFocus(); ImeControl.request(DisplayMode.FULL)
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor,0)
        }
        await("IME shown") {
            val shown=imeHeight()>300
            if (!shown) main { activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor,0) }
            shown
        }
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        SystemClock.sleep(200)
    }
    @After fun close() { if (::activity.isInitialized) main { activity.finish() } }
    private fun key(code: Int,meta: Int=0,repeat: Int=0) {
        val now=SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,repeat,meta,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD))
        instrumentation.sendKeySync(KeyEvent(now,now+10,KeyEvent.ACTION_UP,code,0,meta,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD))
        instrumentation.waitForIdleSync()
    }
    private fun letters(text: String) { text.forEach { key(KeyEvent.KEYCODE_A+(it.lowercaseChar()-'a')) } }
    private fun findNode(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node==null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return instrumentation.uiAutomation.windows.filter { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }.firstNotNullOfOrNull { search(it.root) }
    }
    private fun rect(text: String): Rect {
        var result: AccessibilityNodeInfo?=null
        try { await("IME key $text") { result=findNode { it.text?.toString()==text || it.contentDescription?.toString()?.startsWith(text)==true }; result!=null } }
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
    private fun clickLabel(text: String) {
        // Translation changes the IME window height. Use the native accessibility action
        // instead of screen coordinates that can briefly refer to its previous position.
        // Learning may replace a candidate view between its lookup and the action.
        await("IME action $text") {
            val node=findNode { it.text?.toString()==text || it.contentDescription?.toString()?.startsWith(text)==true }
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
    @Test fun physicalBackspaceDeletesDisplayedKanaAndUnfinishedRomaji() {
        letters("KA"); assertEquals("か",inputText()); key(KeyEvent.KEYCODE_DEL); assertEquals("",inputText())
        letters("K"); assertEquals("k",inputText()); key(KeyEvent.KEYCODE_DEL); assertEquals("",inputText())
        letters("KYO"); key(KeyEvent.KEYCODE_DEL); assertEquals("き",inputText()); key(KeyEvent.KEYCODE_DEL); assertEquals("",inputText())
        letters("NIHONGO"); key(KeyEvent.KEYCODE_DEL); assertEquals("にほん",inputText())
        letters("MO"); assertEquals("にほんも",inputText()); key(KeyEvent.KEYCODE_ENTER)
        key(KeyEvent.KEYCODE_DEL); assertEquals("にほん",inputText())
    }
    @Test fun physicalJapaneseSymbolsAreFullWidthButEnglishSymbolsStayAscii() {
        key(KeyEvent.KEYCODE_SLASH,KeyEvent.META_SHIFT_ON); key(KeyEvent.KEYCODE_1,KeyEvent.META_SHIFT_ON)
        key(KeyEvent.KEYCODE_9,KeyEvent.META_SHIFT_ON); key(KeyEvent.KEYCODE_0,KeyEvent.META_SHIFT_ON)
        key(KeyEvent.KEYCODE_1); assertEquals("？！（）1",inputText())
        key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_ZENKAKU_HANKAKU)
        key(KeyEvent.KEYCODE_SLASH,KeyEvent.META_SHIFT_ON); key(KeyEvent.KEYCODE_1,KeyEvent.META_SHIFT_ON)
        assertEquals("？！（）1?!",inputText())
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
        assertTrue(hardwareNode("物理候補：教えてください")!!.isSelected)
        key(KeyEvent.KEYCODE_TAB,KeyEvent.META_SHIFT_ON)
        assertTrue(hardwareNode("物理候補：お願いします")!!.isSelected)
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きを教えてください",inputText())
        main { assertEquals(-1,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text)) }
        assertNull(hardwareNode("物理キーボードの候補一覧"))
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
        commitTsudukiwo(); clickLabel("見せてください")
        runBlocking { withTimeout(10_000) { app.learning.state.first { it.continuations.any { c -> c.context=="続きを" && c.text=="見せてください" } } } }
        main { editor.setText("") }; instrumentation.waitForIdleSync()
        commitTsudukiwo(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きを見せてください",inputText())
        main {
            editor.setText(""); editor.imeOptions=editor.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            activity.getSystemService(InputMethodManager::class.java).restartInput(editor)
        }
        SystemClock.sleep(200)
        commitTsudukiwo(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        assertEquals("続きをお願いします",inputText())
        assertFalse(app.learning.state.value.continuations.any { it.context=="続きを" && it.text=="お願いします" })
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
        letters("KANJI"); await("writing candidate") { findNode { it.text?.toString()=="漢字" }!=null }
        clickLabel("漢字"); await("confirmed spelling") { inputText()=="漢字" }
        runBlocking { withTimeout(10_000) { app.learning.state.first { it.entries.any { e -> e.reading=="かんじ" && e.text=="漢字" } } } }
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
        assertEquals(1,app.learning.state.value.entries.first { it.reading=="かんじ" && it.text=="漢字" }.count)
    }
    @Test fun candidatePagesCanShowMoreThanSixtyChoices() {
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.set(Prefs.candidateCount,1000); app.settings.state.first { it.candidateCount==1000 } }
        letters("KOU"); key(KeyEvent.KEYCODE_SPACE); touch(rect("一覧"))
        await("first 50 choices") { findNode { it.text?.toString()=="50 / 1000件" }!=null }
        var more: AccessibilityNodeInfo?=null
        repeat(20) {
            if (more==null) {
                more=findNode { it.contentDescription?.toString()=="さらに候補を表示" && it.isVisibleToUser }
                if (more==null) {
                    findNode { it.className?.toString()=="android.widget.ScrollView" }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(120)
                }
            }
        }
        assertNotNull("A page action is reachable by scrolling",more)
        assertTrue(more!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("more than sixty displayed") { findNode { it.text?.toString()=="100 / 1000件" }!=null }
    }
    @Test fun legacyCandidateLimitCanSearchForMore() {
        letters("KOU"); key(KeyEvent.KEYCODE_SPACE); touch(rect("一覧"))
        await("original thirty choices") { findNode { it.text?.toString()=="30 / 30件" }!=null }
        var more: AccessibilityNodeInfo?=null
        repeat(20) {
            if (more==null) {
                more=findNode { it.contentDescription?.toString()=="さらに候補を表示" && it.isVisibleToUser }
                if (more==null) {
                    findNode { it.className?.toString()=="android.widget.ScrollView" }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(120)
                }
            }
        }
        assertNotNull(more); assertTrue(more!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("search grows beyond the old saved setting") { findNode { it.text?.toString()=="50 / 130件" }!=null }
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
        assertNotNull(findNode { it.text?.toString()=="イス" })
        assertNotNull(findNode { it.text?.toString()=="チェア" })
        assertNotNull(findNode { it.text?.toString()=="chair" })
        repeat(15) {
            if (findNode { it.text?.toString()=="chair" && it.isVisibleToUser }==null) {
                findNode { it.className?.toString()=="android.widget.ScrollView" }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(100)
            }
        }
        clickLabel("chair"); assertEquals("chair",inputText())
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
        touch(rect("日本語")); touch(rect("h")); touch(rect("e")); touch(rect("l")); assertEquals("hel",inputText())
        touch(rect("⇧")); touch(rect("A")); touch(rect("b")); assertEquals("helAb",inputText())
        val shift=rect("⇧"); touch(shift); touch(shift)
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
        touch(rect("◀")); assertEquals("今日",inputText())
        touch(rect("↵")); touch(rect("◀")); touch(rect("あ行")); touch(rect("↵"))
        await("cursor insertion") { inputText()=="今あ日" }
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
    @Test fun passwordDisablesSuggestionsAndTranslation() {
        main { editor.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; editor.setText(""); activity.getSystemService(InputMethodManager::class.java).restartInput(editor) }
        await("password input mode ready") { findNode { it.text?.toString()=="EN" }!=null && findNode { it.text?.toString()=="翻訳" }==null }
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
