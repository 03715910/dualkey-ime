package io.github.user03715910.dualkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dualkey.core.ReleaseInfo
import io.github.user03715910.dualkey.update.ApkSafety
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Requires the original signed release installed alongside debug, plus staged fixtures. */
@RunWith(AndroidJUnit4::class)
class ApkSafetyDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val installedPackage="io.github.user03715910.dualkey"
    private val context get()=instrumentation.targetContext.createPackageContext(installedPackage,0)
    @Before fun requireOriginalAppVisible() { assertEquals(installedPackage,context.packageName) }
    private fun fixture(name: String): File {
        val file=File("/data/local/tmp/dualkey-$name.apk")
        Assume.assumeTrue("Run tools/prepare_update_fixtures.py and install release v1 first",file.exists())
        return file
    }
    private fun info(file: File)=ReleaseInfo(installedPackage,2,"0.1.1","2026-10-31","","",ApkSafety.sha256(file),file.length(),26,false,emptyList())
    private fun rejects(file: File,release: ReleaseInfo=info(file)) {
        try { ApkSafety(context).verify(file,release); fail("Unsafe APK was accepted") } catch (e: Exception) { assertTrue(e.message.orEmpty().isNotBlank()) }
    }
    @Test fun acceptsOfficialNewerSignedApk() { val f=fixture("next"); ApkSafety(context).verify(f,info(f)) }
    @Test fun rejectsHashMismatch() { val f=fixture("next"); rejects(f,info(f).copy(sha256="0".repeat(64))) }
    @Test fun rejectsTruncatedSize() { val f=fixture("next"); rejects(f,info(f).copy(sizeBytes=f.length()+1)) }
    @Test fun rejectsWrongSignerEvenWithMatchingHash() { rejects(fixture("wrong-signer")) }
    @Test fun rejectsCorruptionEvenWithRecomputedHash() { rejects(fixture("corrupt")) }
    @Test fun rejectsDifferentPackageSignedWithOfficialKey() { rejects(fixture("foreign")) }
    @Test fun rejectsMetadataVersionMismatch() { val f=fixture("next"); rejects(f,info(f).copy(versionCode=3)) }
    @Test fun opensAndroidInstallerForVerifiedApk() {
        val f=fixture("next"); ApkSafety(context).verify(f,info(f))
        val target=instrumentation.targetContext
        val shared=File(target.cacheDir,"updates/installer-test.apk").apply { parentFile!!.mkdirs(); f.copyTo(this,true) }
        try {
            val uri=FileProvider.getUriForFile(target,target.packageName+".updates",shared)
            target.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            val deadline=android.os.SystemClock.uptimeMillis()+15_000
            var found=false
            while (android.os.SystemClock.uptimeMillis()<deadline) {
                val pkg=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
                if (pkg.contains("packageinstaller") || pkg.contains("permissioncontroller")) { found=true; break }
                android.os.SystemClock.sleep(100)
            }
            assertTrue("Android's installer/permission UI must be visible",found)
        } finally {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            shared.delete()
        }
    }
}
