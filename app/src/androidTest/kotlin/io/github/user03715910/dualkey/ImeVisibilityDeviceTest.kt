package io.github.user03715910.dualkey

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dualkey.core.DisplayMode
import io.github.user03715910.dualkey.data.Prefs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real framework focus/configuration requests from a separate test APK process. */
@RunWith(AndroidJUnit4::class)
class ImeVisibilityDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private val app get()=instrumentation.targetContext.applicationContext as DualKeyApp
    private val fixture="${instrumentation.context.packageName}/io.github.user03715910.dualkey.fixtures.LifecycleFixtureActivity"
    private fun shell(command: String): String=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { String(it.readBytes()) }
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(label: String,condition: ()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+20_000
        while (SystemClock.uptimeMillis()<deadline) { if (condition()) return; SystemClock.sleep(100) }
        fail("Timed out: $label; ${diagnostic()}; fixture=${fixtureState()}; syntheticInput=${text()}")
    }
    private fun diagnostic()=shell("dumpsys activity service ${instrumentation.targetContext.packageName}/io.github.user03715910.dualkey.ime.DualKeyService")
    private fun fixtureState()=shell("dumpsys activity $fixture").lineSequence().firstOrNull { it.contains("Fixture:") }.orEmpty()
    private fun click(description: String) {
        // Invoke the fixture's own public Android actions, including while immersive UI hides nodes.
        // No broadcast/control component is shipped in the production APK.
        val result=shell("am broadcast -n ${fixture}\$FinishReceiver --es command $description")
        assertTrue(result,result.contains("Broadcast completed"))
        SystemClock.sleep(200)
        if (description=="fullscreen") {
            fun confirm(node: AccessibilityNodeInfo?): Boolean {
                if (node==null) return false
                if (node.packageName?.toString() in setOf("android","com.android.systemui") && node.text?.toString()?.equals("Got it",true)==true) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true
                }
                for (i in 0 until node.childCount) if (confirm(node.getChild(i))) return true
                return false
            }
            // The system posts its first-use overlay after a delay, not at the fullscreen call.
            val until=SystemClock.uptimeMillis()+1200
            while (SystemClock.uptimeMillis()<until) { automation.windows.forEach { confirm(it.root) }; SystemClock.sleep(100) }
            await("immersive tutorial dismissed") {
                automation.windows.forEach { confirm(it.root) }
                fixtureState().contains("windowFocus=true")
            }
        }
    }
    private fun launch(media: String?=null) {
        val result=shell("am start -W -f 0x10008000 -n $fixture"+(media?.let { " --es media $it" } ?: ""))
        assertFalse(result,result.contains("Error"))
        await("local video playing") { fixtureState().contains("video=playing") }
    }
    private fun stableHidden() {
        await("no keyboard inset") { fixtureState().contains("ime=0;") && !ImeControl.state.value.keyboardVisible }
        // Longer than the complete manual-show retry sequence, not just a single frame.
        val until=SystemClock.uptimeMillis()+1500
        while (SystemClock.uptimeMillis()<until) {
            assertFalse("Keyboard reopened: ${diagnostic()}",ImeControl.state.value.keyboardVisible)
            assertTrue("Keyboard reserved app space: ${fixtureState()}",fixtureState().contains("ime=0;"))
            SystemClock.sleep(100)
        }
    }
    private fun rotate(landscape: Boolean) {
        click(if (landscape) "landscape" else "portrait")
        await("orientation=$landscape") { fixtureState().contains("landscape=$landscape") }
        SystemClock.sleep(400)
    }
    private fun edit() { click("edit"); await("keyboard for real editor") { ImeControl.state.value.keyboardVisible && ImeControl.state.value.editorActive } }
    private fun letters(text: String) { text.forEach { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A+(it-'a')) }; instrumentation.waitForIdleSync() }
    private fun text(): String? {
        val encoded=shell("dumpsys activity $fixture").lineSequence().firstOrNull { it.contains("FixtureText64:") }
            ?.substringAfter("FixtureText64:")?.trim() ?: return null
        return String(android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP),Charsets.UTF_8)
    }
    private fun capture(name: String) {
        val image=automation.takeScreenshot() ?: error("No screenshot")
        File(instrumentation.targetContext.getExternalFilesDir("visibility"),"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic"))
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        shell("cmd statusbar collapse")
        val ime=ComponentName(instrumentation.targetContext.packageName,"io.github.user03715910.dualkey.ime.DualKeyService").flattenToShortString()
        shell("ime enable $ime"); shell("ime set $ime")
        runBlocking {
            app.settings.awaitLoaded(); app.settings.set(Prefs.hardwarePolicy,"FULL"); app.settings.set(Prefs.rememberManual,false)
            app.settings.set(Prefs.initialEnglish,false); app.settings.set(Prefs.learningEnabled,false)
            app.settings.set(Prefs.hideImeCaption,true); app.settings.clearNotificationDisplay()
        }
        main { ImeControl.requests.value=null }
        launch(); click("portrait"); await("portrait") { fixtureState().contains("landscape=false") }
    }
    @After fun cleanup() {
        shell("am broadcast -n ${fixture}\$FinishReceiver")
        main { ImeControl.requests.value=null }
    }
    private fun mediaResult()=shell("dumpsys activity $fixture").lineSequence().firstOrNull { it.contains("FixtureMedia:") }.orEmpty()
    private fun keyboardAction(label: String,action: Int=AccessibilityNodeInfo.ACTION_CLICK) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node==null) return null
            if (node.text?.toString()==label || node.contentDescription?.toString()==label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        await("keyboard action $label") { automation.windows.filter { it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .firstNotNullOfOrNull { find(it.root) }?.performAction(action)==true }
    }
    @Test fun richEditorReceivesImagesGifAndVideoWithActualReadGrant() {
        launch("rich"); await("rich editor window focused") { fixtureState().contains("windowFocus=true") }; edit()
        listOf("test.png" to "image/png","test.jpeg" to "image/jpeg","test.webp" to "image/webp",
            "test.gif" to "image/gif","lifecycle-video.mp4" to "video/mp4").forEach { (file,mime) ->
            click("copy-$file"); keyboardAction("貼り付け")
            await("received $mime: ${mediaResult()}") { mediaResult().contains("$mime; match=true") }
        }
        letters("ka"); await("regular text still works") { text()=="か" }
    }
    @Test fun richHistoryCanPasteAgainAndRejectUnsupportedVideo() {
        runBlocking { app.settings.set(Prefs.clipboardHistory,true); app.settings.state.first { it.clipboardHistory } }
        try {
            launch("images"); await("image editor window focused") { fixtureState().contains("windowFocus=true") }; edit()
            click("copy-test.png"); keyboardAction("貼り付け")
            await("PNG received") { mediaResult().contains("image/png; match=true") }
            click("reset-media")
            keyboardAction("貼り付け",AccessibilityNodeInfo.ACTION_LONG_CLICK)
            keyboardAction("メディア履歴：画像")
            await("history PNG received again") { mediaResult().contains("image/png; match=true") }
            click("copy-lifecycle-video.mp4"); keyboardAction("貼り付け"); SystemClock.sleep(500)
            assertTrue(mediaResult(),mediaResult().contains("FixtureMedia: none"))
            letters("ka"); await("unsupported MIME leaves text input usable") { text()=="か" }
        } finally { runBlocking { app.settings.set(Prefs.clipboardHistory,false) } }
    }
    @Test fun videoRotationAndFullscreenNeverRequestTextInput() {
        stableHidden(); assertFalse(ImeControl.state.value.editorActive)
        rotate(true); stableHidden()
        click("fullscreen"); stableHidden(); capture("landscape-video-no-keyboard")
        click("windowed"); stableHidden()
        rotate(false); stableHidden()
    }
    @Test fun pictureInPictureHomeAndAppSwitchStayHidden() {
        Assume.assumeTrue(instrumentation.targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        click("pip"); await("actual PiP") { fixtureState().contains("pip=true") }; stableHidden(); capture("picture-in-picture-no-keyboard")
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
        assertFalse(ImeControl.state.value.keyboardVisible)
        shell("am start -W -a android.settings.SETTINGS"); SystemClock.sleep(1500)
        assertFalse(ImeControl.state.value.keyboardVisible)
        launch(); stableHidden()
    }
    @Test fun activeJapaneseCompositionContinuesAcrossBothOrientations() {
        edit(); letters("k"); await("pending k") { text()=="k" }
        rotate(true); await("landscape editor shown") { ImeControl.state.value.keyboardVisible }
        letters("yo"); await("pending romaji retained") { text()=="きょ" }; capture("landscape-active-editor")
        rotate(false); letters("uha"); await("composition retained") { text()=="きょうは" }
        assertTrue(ImeControl.state.value.keyboardVisible)
        click("end-input"); stableHidden(); assertFalse(ImeControl.state.value.editorActive)
    }
    @Test fun systemDismissalIsRespectedOnRotationAndExplicitShowStillWorks() {
        edit(); letters("kyou"); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        stableHidden(); rotate(true); stableHidden()
        edit(); letters("ha"); await("explicit show retains composition") { text()=="きょうは" }
        assertTrue(ImeControl.state.value.keyboardVisible)
    }
    @Test fun manualHideAndShowWorkWithRealEditorAfterRotation() {
        edit(); letters("ka"); main { ImeControl.request(DisplayMode.HIDDEN) }; stableHidden()
        rotate(true); stableHidden()
        main { ImeControl.request(DisplayMode.FULL) }
        await("manual show") { ImeControl.state.value.keyboardVisible }
        assertEquals("か",text())
    }
    @Test fun delayedManualShowCannotReopenANonEditor() {
        edit(); main { ImeControl.request(DisplayMode.HIDDEN) }; SystemClock.sleep(80)
        main { ImeControl.request(DisplayMode.FULL) }; click("end-input")
        stableHidden(); rotate(true); stableHidden()
        assertFalse(ImeControl.state.value.editorActive)
    }
    @Test fun deferredManualShowWaitsForAnActualTextEditor() {
        main { ImeControl.request(DisplayMode.FULL) }
        rotate(true); click("fullscreen"); stableHidden()
        edit(); letters("ka"); await("deferred show types") { text()=="か" }
    }
    @Test fun rapidManualToggleSurvivesTheEarlierHideAcknowledgement() {
        edit(); letters("ka")
        main { ImeControl.request(DisplayMode.HIDDEN) }
        await("hide command consumed") { ImeControl.requests.value==null }
        main { ImeControl.request(DisplayMode.FULL) }
        await("rapid show") { ImeControl.state.value.keyboardVisible }
        SystemClock.sleep(1800)
        assertTrue(diagnostic(),ImeControl.state.value.keyboardVisible)
        assertEquals("か",text())
    }
}
