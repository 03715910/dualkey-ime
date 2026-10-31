package io.github.user03715910.dualkey

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.dualkey.core.ReleaseInfo
import io.github.user03715910.dualkey.data.Prefs
import io.github.user03715910.dualkey.settings.MainActivity
import io.github.user03715910.dualkey.update.ApkSafety
import io.github.user03715910.dualkey.update.UpdateState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Native navigation, startup notices and the actual signed-APK permission handoff. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as DualKeyApp
    private lateinit var activity: MainActivity
    private fun main(block: ()->Unit) {
        if (android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) block() else instrumentation.runOnMainSync(block)
    }
    private fun await(label: String,condition: ()->Boolean) {
        val end=SystemClock.uptimeMillis()+60_000
        while (SystemClock.uptimeMillis()<end) { if (condition()) return; SystemClock.sleep(80) }
        fail("Timed out: $label")
    }
    private fun views(): List<View> {
        val result=mutableListOf<View>()
        fun collect(v: View) { result+=v; if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
        main { collect(activity.findViewById(android.R.id.content)) }; return result
    }
    private fun textContains(value: String)=views().filterIsInstance<TextView>().any { it.text.toString().contains(value) && it.isShown }
    private fun click(id: Int) { main { activity.findViewById<View>(id).performClick() }; instrumentation.waitForIdleSync() }
    private fun tag(value: String): View? { var v: View?=null; main { v=activity.findViewById<View>(android.R.id.content).findViewWithTag(value) }; return v }
    private fun popup(value: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(value)?.firstOrNull { it.text?.toString()==value }
    private fun clickPopup(value: String) { await(value) { popup(value)!=null }; assertTrue(popup(value)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync() }
    private fun recreate() {
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        main { activity.recreate() }
        activity=instrumentation.waitForMonitorWithTimeout(monitor,15_000) as MainActivity
        instrumentation.removeMonitor(monitor)
        await("recreated page") { activity.findViewById<View>(R.id.page_content)!=null }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi")!="true") return
        instrumentation.waitForIdleSync(); SystemClock.sleep(200)
        val folder=File(app.getExternalFilesDir(null),"ui-preview").apply { mkdirs() }
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    private fun offer()=ReleaseInfo(app.packageName,BuildConfig.VERSION_CODE+1L,"0.1.4","2026-10-31",
        "https://github.com/03715910/dualkey-ime/releases/download/v0.1.4/DualKeyIME-0.1.4.apk",
        BuildConfig.WEBSITE_URL,"a".repeat(64),1024,26,false,listOf("更新操作を確認するテストです"))

    @Before fun open() {
        // Test a disposable emulator only, never a user's daily input settings.
        check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic"))
        runBlocking {
            app.settings.awaitLoaded(); app.settings.set(Prefs.autoCheck,true); app.settings.set(Prefs.autoDownload,false)
            app.settings.set(Prefs.notifications,false); app.settings.set(Prefs.wifiOnly,false)
            app.settings.state.first { it.autoCheck && !it.autoDownload && !it.notifications && !it.wifiOnly }
        }
        WorkManager.getInstance(app).cancelUniqueWork("update-check").result.get()
        app.updates.state.value=UpdateState()
        activity=instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        await("home ready") { tag("test_input")!=null }
        await("startup check completed") { !app.updates.state.value.busy && app.updates.state.value.message!="更新は未確認です" }
    }
    @After fun close() {
        app.updates.state.value=UpdateState()
        if (::activity.isInitialized) main { activity.finish() }
    }
    @Test fun groupedSettingsAndVisibleUpdateActionSurviveRecreation() {
        capture("home")
        click(R.id.nav_settings); await("category list") { tag("settings_input")!=null }; capture("settings")
        assertNull(tag("test_input"))
        main { tag("settings_input")!!.performClick() }
        await("input settings") { textContains("標準の大きさに戻す") }
        val setting=views().filterIsInstance<SwitchCompat>().first { it.text.toString()=="数字専用行を表示" }
        var before=false; main { before=setting.isChecked; setting.performClick() }
        runBlocking { app.settings.state.first { it.numberRow!=before } }
        recreate(); await("saved input page") { textContains("標準の大きさに戻す") }
        val restored=views().filterIsInstance<SwitchCompat>().first { it.text.toString()=="数字専用行を表示" }
        main { assertEquals(!before,restored.isChecked) }
        click(R.id.nav_updates); await("update action") { tag("update_primary")!=null }
        val bounds=Rect()
        await("visible update action") { var visible=false; main { visible=tag("update_primary")?.getGlobalVisibleRect(bounds)==true }; visible }
        assertTrue("The update action is visible without scrolling",bounds.height()>50)
        capture("updates")
    }
    @Test fun startupOfferWorksWithoutNotificationPermissionAndLaterDoesNotRepeat() {
        if (android.os.Build.VERSION.SDK_INT>=33) assertNotEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        app.updates.state.value=UpdateState("新しいバージョンがあります",offer())
        await("startup notice") { popup("新しいバージョンがあります")!=null }; capture("startup-update")
        clickPopup("あとで")
        app.updates.state.value=UpdateState("APKを取得しています…",offer(),busy=true,progress=42)
        SystemClock.sleep(300)
        assertNull(popup("新しいバージョンがあります"))
        recreate(); await("restored home") { tag("test_input")!=null }
        assertNull(popup("新しいバージョンがあります"))
        click(R.id.nav_updates); await("progress page") { textContains("42%") }
        main { assertFalse((tag("update_primary") as Button).isEnabled) }; capture("download-progress")
    }
    @Test fun notificationIntentOpensUpdatesWithoutStartingInstallation() {
        app.startActivity(Intent(app,MainActivity::class.java).putExtra("updates",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await("notification destination") { tag("update_primary")!=null }
        assertNull(tag("test_input"))
        assertNull(popup("アプリからの更新を許可"))
        assertEquals(app.packageName,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
    }
    @Test fun failedDownloadOffersRetryAndNavigation() {
        click(R.id.nav_updates)
        app.updates.state.value=UpdateState("新しいバージョンがあります",offer())
        await("download action") { textContains("ダウンロードして更新") }
        main { tag("update_primary")!!.performClick() }
        await("safe download failure") { app.updates.state.value.failure && !app.updates.state.value.busy }
        assertNull(app.updates.state.value.apk)
        await("retry enabled") { var enabled=false; main { enabled=tag("update_primary")?.isEnabled==true }; enabled }
        click(R.id.nav_settings); await("navigation still works") { tag("settings_input")!=null }
    }
    @Test fun readySignedApkContinuesToAndroidPermissionAndReturnsSafely() {
        Assume.assumeFalse(BuildConfig.DEBUG)
        val fixture=File("/data/local/tmp/dualkey-ui-next.apk")
        Assume.assumeTrue("Build and push the same-key future APK fixture first",fixture.isFile)
        @Suppress("DEPRECATION") val archive=app.packageManager.getPackageArchiveInfo(fixture.path,0)!!
        @Suppress("DEPRECATION") val code=if (android.os.Build.VERSION.SDK_INT>=28) archive.longVersionCode else archive.versionCode.toLong()
        val info=offer().copy(versionCode=code,versionName=archive.versionName!!,sizeBytes=fixture.length(),sha256=ApkSafety.sha256(fixture))
        val cached=File(app.cacheDir,"updates/ui-ready.apk").apply { parentFile!!.mkdirs(); fixture.copyTo(this,true) }
        ApkSafety(app).verify(cached,info)
        app.updates.state.value=UpdateState("更新の準備ができました",info,cached)
        await("ready update notice") { popup("新しいバージョンがあります")!=null }
        clickPopup("更新する")
        if (!app.packageManager.canRequestPackageInstalls()) {
            await("permission guidance") { popup("アプリからの更新を許可")!=null }; capture("install-permission")
            clickPopup("設定を開く")
            await("Android permission settings") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.settings" }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("return from Android settings") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==app.packageName }
            capture("install-return")
            await("returned to update page") { textContains("インストールの許可が必要です") }
            assertNotNull(tag("update_primary"))
        } else {
            await("Android installer") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty().let { it.contains("packageinstaller") || it.contains("permissioncontroller") } }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        }
        cached.delete()
    }
}
