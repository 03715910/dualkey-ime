package io.github.user03715910.dualkey.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import io.github.dualkey.core.ReleaseInfo
import io.github.user03715910.dualkey.BuildConfig
import java.io.File
import java.security.MessageDigest

class ApkSafety(private val context: Context) {
    @Suppress("DEPRECATION")
    fun verify(file: File, info: ReleaseInfo) {
        check(file.length()==info.sizeBytes) { "APKの容量が一致しません" }
        check(sha256(file)==info.sha256.lowercase()) { "APKが破損しています（SHA-256不一致）" }
        val result=ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        check(result.isVerified) { "APKの暗号署名を検証できません" }
        val certs=result.signerCertificates.map { digest(it.encoded) }.toSet()
        val pin=BuildConfig.TRUSTED_CERT_SHA256
        check(pin.matches(Regex("[a-f0-9]{64}")) && certs==setOf(pin)) { "公式の署名鍵ではありません" }
        val flags=if (Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        val archive=context.packageManager.getPackageArchiveInfo(file.path,flags) ?: error("APK情報を読み取れません")
        check(archive.packageName==context.packageName && archive.packageName==info.packageName) { "別のアプリのAPKです" }
        fun code(p: PackageInfo)=if (Build.VERSION.SDK_INT>=28) p.longVersionCode else p.versionCode.toLong()
        check(code(archive)==info.versionCode && code(archive)>code(installed)) { "更新バージョンが一致しません" }
        check(archive.versionName==info.versionName) { "表示バージョンが一致しません" }
        check((archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE==0) { "デバッグAPKは配布できません" }
        check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)<=Build.VERSION.SDK_INT) { "このAndroidには対応していません" }
        check(archive.applicationInfo?.minSdkVersion==info.minSdk) { "対応Androidの情報が一致しません" }
        val installedCerts=if (Build.VERSION.SDK_INT>=28) installed.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
            else installed.signatures?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
        // Intentionally require the exact current signer set. Key rotation needs an explicit future migration.
        check(installedCerts==certs) { "現在のアプリと署名が一致しません" }
    }
    companion object {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun sha256(file: File): String {
            val md=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b=ByteArray(65536); while (true) { val n=input.read(b); if (n<0) break; md.update(b,0,n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
