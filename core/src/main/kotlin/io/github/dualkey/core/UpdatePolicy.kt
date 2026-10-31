package io.github.dualkey.core

import java.net.URI

data class ReleaseInfo(val packageName: String, val versionCode: Long, val versionName: String,
    val releaseDate: String, val apkUrl: String, val websiteUrl: String, val sha256: String,
    val sizeBytes: Long, val minSdk: Int, val required: Boolean, val releaseNotes: List<String>)

class UpdatePolicy(private val packageName: String, private val website: String, private val repository: String) {
    companion object { const val MAX_APK_BYTES = 150L * 1024 * 1024 }
    fun validate(info: ReleaseInfo) {
        require(info.packageName == packageName) { "別のアプリの更新情報です" }
        require(info.versionCode in 1..2_100_000_000L)
        require(info.versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(info.releaseDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(info.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "SHA-256が正しくありません" }
        require(info.sizeBytes in 1..MAX_APK_BYTES) { "APK容量が範囲外です" }
        require(info.minSdk in 26..100)
        require(info.releaseNotes.size<=100 && info.releaseNotes.all { it.length<=2000 })
        require(website.isNotBlank() && info.websiteUrl == website) { "公式サイトが一致しません" }
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        val expected = "https://github.com/$repository/releases/download/v${info.versionName}/DualKeyIME-${info.versionName}.apk"
        require(info.apkUrl == expected) { "公式APK配布先ではありません" }
        secureUri(info.apkUrl)
    }
    fun isNewer(info: ReleaseInfo, installedVersionCode: Long, sdk: Int): Boolean = info.versionCode>installedVersionCode && info.minSdk<=sdk
    fun allowRedirect(url: String): Boolean = runCatching {
        val u=secureUri(url)
        u.host in setOf("release-assets.githubusercontent.com","objects.githubusercontent.com") ||
            (u.host=="github.com" && u.path.startsWith("/$repository/releases/download/"))
    }.getOrDefault(false)
    private fun secureUri(url: String): URI = URI(url).also {
        require(it.scheme=="https" && it.userInfo==null && it.port==-1 && it.fragment==null && !it.host.isNullOrEmpty())
    }
}
