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
        fail("Timed out: $label")
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
        runBlocking { app.settings.awaitLoaded(); app.settings.set(Prefs.initialEnglish,false); app.settings.set(Prefs.hardwarePolicy,"AUTO"); app.settings.set(Prefs.shortcut,true); app.settings.set(Prefs.englishPrediction,true); app.settings.set(Prefs.numberRow,true); app.settings.set(Prefs.keyHeight,40); app.settings.set(Prefs.heightPercent,100) }
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("editor ready") { var found=false; main { found=activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("test_input")!=null }; found }
        main {
            editor=activity.findViewById<View>(android.R.id.content).findViewWithTag("test_input")
            editor.setText(""); editor.requestFocus(); ImeControl.request(DisplayMode.FULL)
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor,0)
        }
        await("IME shown") { imeHeight()>300 }
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
    @Test fun physicalContractedGeminatedAndN() {
        letters("KYONIHONGOSHACHATTANNA"); await("romaji sequence") { inputText()=="きょにほんごしゃちゃったんな" }
        key(KeyEvent.KEYCODE_ENTER); assertEquals("きょにほんごしゃちゃったんな",inputText())
    }
    @Test fun candidateArrowEscapeAndBackspace() {
        letters("KYOU"); key(KeyEvent.KEYCODE_SPACE); assertEquals("今日",inputText())
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals("京都",inputText())
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
        touch(rect("▶")); assertEquals("京都",inputText())
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
