package io.github.user03715910.dualkey

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.user03715910.dualkey.data.Prefs
import io.github.user03715910.dualkey.update.ApkSafety
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in, emulator-only checks of the real public feed and settings across an OS update. */
@RunWith(AndroidJUnit4::class)
class PublishedUpdateDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DualKeyApp
    private val snapshot get() = File(app.filesDir, "public-update-retention.json")
    private val expectedCode get() = InstrumentationRegistry.getArguments().getString("publishedVersionCode")!!.toLong()
    @Suppress("DEPRECATION")
    private val installedCode: Long get() = app.packageManager.getPackageInfo(app.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }

    @Before fun requireExplicitEmulatorReleaseTest() {
        assumeTrue("Opt in with -e publicUpdate true", InstrumentationRegistry.getArguments().getString("publicUpdate") == "true")
        assumeTrue("Use the signed release test variant", !BuildConfig.DEBUG)
        // Never seed or replace settings on a user's physical phone.
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Use a disposable emulator" }
        check(InstrumentationRegistry.getArguments().getString("publishedVersionCode")?.toLongOrNull() != null)
    }

    private fun savedSettings() = JSONObject().apply {
        app.settings.state.value.p.asMap().entries.sortedBy { it.key.name }.forEach { (key, value) -> put(key.name, value.toString()) }
    }

    @Test fun seedEverySettingAndDictionary() = runBlocking {
        assertTrue("Install an older signed release first", installedCode < expectedCode)
        app.settings.awaitLoaded()
        with(app.settings) {
            set(Prefs.initialEnglish, true); set(Prefs.numberRow, false)
            set(Prefs.flickDp, 31); set(Prefs.keyHeight, 64); set(Prefs.heightPercent, 110)
            set(Prefs.vibration, false); set(Prefs.sound, true)
            set(Prefs.hardwarePolicy, "COMPACT"); set(Prefs.rememberManual, true); set(Prefs.savedManual, "FULL")
            set(Prefs.shortcut, false); set(Prefs.ctrlSpace, false)
            set(Prefs.japanesePrediction, false); set(Prefs.englishPrediction, false); set(Prefs.candidateCount, 12)
            set(Prefs.translateToEnglish, false)
            set(Prefs.autoCheck, false); set(Prefs.autoDownload, false)
            set(Prefs.wifiOnly, true); set(Prefs.notifications, false)
            addWord("でゅある", "DualKey検証辞書")
            state.first { it.dictionary["でゅある"]?.contains("DualKey検証辞書") == true && !it.autoCheck && !it.notifications }
        }
        val settings = savedSettings()
        assertEquals("Every configured setting and user dictionary must be covered", 21, settings.length())
        snapshot.writeText(settings.toString())
    }

    @Test fun downloadAndVerifyPublishedUpdate() = runBlocking {
        check(BuildConfig.METADATA_URL.startsWith("https://")) { "A real distribution configuration is required" }
        assertTrue("Install an older signed release first", installedCode < expectedCode)
        val release = app.updates.check()
        assertNotNull(app.updates.state.value.message, release)
        assertEquals(expectedCode, release!!.versionCode)
        val apk = app.updates.download(release)
        assertNotNull(app.updates.state.value.message, apk)
        ApkSafety(app).verify(apk!!, release)
        assertEquals(release.sizeBytes, apk.length())
        assertFalse(app.updates.state.value.busy)
    }

    @Test fun retainsEverySettingAndDictionaryAfterUpdate() = runBlocking {
        assertEquals(expectedCode, installedCode)
        app.settings.awaitLoaded()
        assertTrue("Run the seed test on the older release first", snapshot.isFile)
        val expected = JSONObject(snapshot.readText())
        val actual = savedSettings()
        assertEquals(expected.keySet(), actual.keySet())
        for (key in expected.keys()) assertEquals("Saved setting changed: $key", expected.getString(key), actual.getString(key))
        assertEquals(listOf("DualKey検証辞書"), app.settings.state.value.dictionary["でゅある"])
    }

    @Test fun recognizesInstalledVersionAsCurrent() = runBlocking {
        check(BuildConfig.METADATA_URL.startsWith("https://")) { "A real distribution configuration is required" }
        assertEquals(expectedCode, installedCode)
        assertNull(app.updates.check())
        assertEquals("最新バージョンを使用しています", app.updates.state.value.message)
    }

    private fun JSONObject.keySet() = keys().asSequence().toSet()
}
