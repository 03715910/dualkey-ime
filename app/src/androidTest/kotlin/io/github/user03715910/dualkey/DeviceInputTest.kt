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
    private fun imeHeight(): Int { var value=0; main { value=ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }; return value }
    @Before fun openEditor() {
        val component=android.content.ComponentName(instrumentation.targetContext.packageName,
            "io.github.user03715910.dualkey.ime.DualKeyService").flattenToShortString()
        // UTP reinstalls the app and can reset the default IME. Select the actual target, never Gboard.
        listOf("ime enable $component","ime set $component").forEach { command ->
            shell(command)
        }
        assertEquals(component,android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver,"default_input_method"))
        val app=instrumentation.targetContext.applicationContext as DualKeyApp
        runBlocking { app.settings.awaitLoaded(); app.settings.set(Prefs.learningEnabled,false); app.settings.set(Prefs.initialEnglish,false); app.settings.set(Prefs.hardwarePolicy,"AUTO"); app.settings.set(Prefs.shortcut,true); app.settings.set(Prefs.englishPrediction,true); app.settings.set(Prefs.japanesePrediction,true); app.settings.set(Prefs.candidateCount,30); app.settings.set(Prefs.numberRow,true); app.settings.set(Prefs.keyHeight,40); app.settings.set(Prefs.heightPercent,100)
            withTimeout(30_000) { app.japaneseDictionary.engine.first { it is DictionaryConversionEngine } }
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
    private fun key(code: Int,meta: Int=0) {
        val now=SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD))
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
        var node: AccessibilityNodeInfo?=null
        await("IME action $text") { node=findNode { it.text?.toString()==text }; node!=null }
        // Translation changes the IME window height. Use the native accessibility action
        // instead of screen coordinates that can briefly refer to its previous position.
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
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
    @Test fun compactModeUsesLessSpaceAndCanRecoverWithTouch() {
        val full=imeHeight()
        ImeControl.request(DisplayMode.COMPACT); await("compact height") { imeHeight() in 1 until full }
        touch(rect("全表示")); await("full height restored") { imeHeight()>=full }
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
        touch(rect("あ")); touch(rect("h")); touch(rect("e")); touch(rect("l")); assertEquals("hel",inputText())
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
        key(KeyEvent.KEYCODE_A); key(KeyEvent.KEYCODE_A,KeyEvent.META_SHIFT_ON); key(KeyEvent.KEYCODE_A,KeyEvent.META_CAPS_LOCK_ON)
        key(KeyEvent.KEYCODE_SPACE)
        assertEquals("aAA ",inputText())
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
