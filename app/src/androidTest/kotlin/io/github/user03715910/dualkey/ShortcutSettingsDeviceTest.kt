package io.github.user03715910.dualkey

import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dualkey.core.ToggleShortcut
import io.github.user03715910.dualkey.data.Prefs
import io.github.user03715910.dualkey.settings.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShortcutSettingsDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as DualKeyApp
    private lateinit var activity: MainActivity
    private fun main(block: ()->Unit) {
        if (android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) block() else instrumentation.runOnMainSync(block)
    }
    private fun await(label: String,condition: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+15_000
        while (SystemClock.uptimeMillis()<end) { if (condition()) return; SystemClock.sleep(80) }
        fail("Timed out: $label")
    }
    private fun tag(value: String): View? { var result: View?=null; main { result=activity.findViewById<View>(android.R.id.content).findViewWithTag(value) }; return result }
    private fun node(label: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString()==label }
    private fun click(label: String) {
        await(label) { node(label)!=null }
        val value=node(label)!!
        assertTrue("Could not click $label",value.performAction(AccessibilityNodeInfo.ACTION_CLICK) || value.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        instrumentation.waitForIdleSync()
    }
    private fun openDialog() { main { tag("configure_visibility_shortcut")!!.performClick() }; instrumentation.waitForIdleSync(); await("shortcut dialog") { node("表示／非表示のショートカット")!=null } }
    private fun chord(code: Int,meta: Int) {
        val now=SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta))
        instrumentation.sendKeySync(KeyEvent(now,now+10,KeyEvent.ACTION_UP,code,0,meta))
        instrumentation.waitForIdleSync()
    }
    private fun saved(value: ToggleShortcut)=runBlocking { withTimeout(10_000) { app.settings.state.first { it.displayShortcut==value } } }
    @Before fun open() {
        check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic"))
        runBlocking { app.settings.awaitLoaded(); app.settings.set(Prefs.autoCheck,false); app.settings.setDisplayShortcut(ToggleShortcut()); app.settings.state.first { !it.autoCheck && it.displayShortcut==ToggleShortcut() } }
        activity=instrumentation.startActivitySync(Intent(app,MainActivity::class.java).putExtra("settings",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("hardware category") { tag("settings_hardware")!=null }
        main { tag("settings_hardware")!!.performClick() }
        await("shortcut settings") { tag("configure_visibility_shortcut")!=null }
    }
    @After fun close() { if (::activity.isInitialized) main { activity.finish() } }
    @Test fun manualControlsSaveTheChosenChordAndRetainItAfterRecreation() {
        openDialog(); click("Alt"); click("Shift")
        // The main key's native Spinner exposes its selected label.
        click("K")
        repeat(3) {
            if (node("J")==null) {
                fun scroll(node: AccessibilityNodeInfo?): Boolean {
                    if (node==null) return false
                    if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                    for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
                    return false
                }
                scroll(instrumentation.uiAutomation.rootInActiveWindow); SystemClock.sleep(120)
            }
        }
        click("J"); click("保存")
        val expected=ToggleShortcut("J",ToggleShortcut.CTRL or ToggleShortcut.SHIFT); saved(expected)
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        main { activity.recreate() }
        activity=instrumentation.waitForMonitorWithTimeout(monitor,15_000) as MainActivity
        instrumentation.removeMonitor(monitor)
        await("saved shortcut label") { (tag("configure_visibility_shortcut") as? TextView)?.text?.contains(expected.label)==true }
        assertEquals(expected,app.settings.state.value.displayShortcut)
    }
    @Test fun keyCaptureCancelAndRestoreDefaultWorkThroughTheDialog() {
        val expected=ToggleShortcut("U",ToggleShortcut.CTRL or ToggleShortcut.ALT)
        openDialog(); click("キーボードから登録"); chord(KeyEvent.KEYCODE_U,KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        await("captured key preview") { node(expected.label)!=null }
        click("キャンセル"); assertEquals(ToggleShortcut(),app.settings.state.value.displayShortcut)
        openDialog(); click("キーボードから登録"); chord(KeyEvent.KEYCODE_U,KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        click("保存"); saved(expected)
        openDialog(); click("初期値に戻す"); await("default preview") { node(ToggleShortcut().label)!=null }
        click("キャンセル"); assertEquals(expected,app.settings.state.value.displayShortcut)
        openDialog(); click("初期値に戻す"); click("保存"); saved(ToggleShortcut())
    }
}
