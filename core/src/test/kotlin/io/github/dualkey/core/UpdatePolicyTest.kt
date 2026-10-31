package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val site="https://release-owner.github.io/dualkey-test/"
    private val policy=UpdatePolicy("io.test.keyboard",site,"release-owner/dualkey-test")
    private val info=ReleaseInfo("io.test.keyboard",2,"0.1.1","2026-10-31",
        "https://github.com/release-owner/dualkey-test/releases/download/v0.1.1/DualKeyIME-0.1.1.apk",site,"a".repeat(64),1024,26,false,listOf("修正"))
    private fun rejects(value: ReleaseInfo) { try { policy.validate(value); fail("must reject") } catch (_: IllegalArgumentException) {} }
    @Test fun acceptsValidReleaseAndUsesOnlyVersionCode() {
        policy.validate(info)
        assertTrue(policy.isNewer(info,1,35)); assertFalse(policy.isNewer(info,2,35)); assertFalse(policy.isNewer(info,3,35))
        assertFalse(policy.isNewer(info.copy(minSdk=36),1,35))
        assertTrue(policy.isNewer(info.copy(versionName="0.0.1"),1,35))
    }
    @Test fun rejectsForeignPackageHashAndSize() {
        rejects(info.copy(packageName="io.evil")); rejects(info.copy(sha256="bad")); rejects(info.copy(sizeBytes=0))
        rejects(info.copy(sizeBytes=UpdatePolicy.MAX_APK_BYTES+1)); rejects(info.copy(versionCode=0))
    }
    @Test fun rejectsUntrustedSources() {
        rejects(info.copy(apkUrl=info.apkUrl.replace("https:","http:")))
        rejects(info.copy(apkUrl=info.apkUrl.replace("github.com","github.com.evil.invalid")))
        rejects(info.copy(apkUrl=info.apkUrl.replace("release-owner/","other/")))
        rejects(info.copy(websiteUrl=site+"other"))
        rejects(info.copy(apkUrl=info.apkUrl+"?redirect=evil"))
    }
    @Test fun redirectAllowlistRejectsLookalikesAndDowngrades() {
        assertTrue(policy.allowRedirect("https://release-assets.githubusercontent.com/a?token=abc"))
        assertFalse(policy.allowRedirect("http://release-assets.githubusercontent.com/a"))
        assertFalse(policy.allowRedirect("https://release-assets.githubusercontent.com.evil.invalid/a"))
        assertFalse(policy.allowRedirect("https://user@release-assets.githubusercontent.com/a"))
        assertFalse(policy.allowRedirect("https://release-assets.githubusercontent.com:444/a"))
        assertFalse(policy.allowRedirect("https://github.com/other/repo/releases/download/v1/app.apk"))
    }
}
