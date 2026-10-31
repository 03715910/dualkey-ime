package io.github.user03715910.dualkey.update

import io.github.dualkey.core.UpdatePolicy
import io.github.user03715910.dualkey.BuildConfig
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class OfficialNetwork(private val policy: UpdatePolicy) {
    fun metadata(): String {
        check(BuildConfig.METADATA_URL.startsWith("https://")) { "配布先はまだ設定されていません" }
        val connection=open(BuildConfig.METADATA_URL+"?hour="+(System.currentTimeMillis()/3_600_000))
        return try {
            check(connection.responseCode==200) { "更新サイトに接続できません（HTTP ${connection.responseCode}）" }
            connection.inputStream.use { input ->
                val bytes=input.readNBytesCompat(64*1024+1)
                check(bytes.size<=64*1024) { "更新情報が大きすぎます" }
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }
    fun apk(url: String): HttpsURLConnection {
        var current=url
        repeat(6) {
            check(policy.allowRedirect(current)) { "許可されていないダウンロード先です" }
            val connection=open(current)
            val status=connection.responseCode
            if (status==200) return connection
            if (status in setOf(301,302,303,307,308)) {
                val next=connection.getHeaderField("Location")
                connection.disconnect()
                check(!next.isNullOrBlank())
                current=URL(URL(current),next).toExternalForm()
            } else { connection.disconnect(); error("APKを取得できません（HTTP $status）") }
        }
        error("リダイレクトが多すぎます")
    }
    private fun open(url: String) = (URL(url).openConnection() as HttpsURLConnection).apply {
        instanceFollowRedirects=false; connectTimeout=15_000; readTimeout=30_000; useCaches=false
        setRequestProperty("Cache-Control","no-cache")
        setRequestProperty("Accept-Encoding","identity")
        setRequestProperty("User-Agent","DualKeyIME/${BuildConfig.VERSION_NAME}")
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(4096)
    while (out.size()<limit) { val n=read(buffer,0,minOf(buffer.size,limit-out.size())); if (n<0) break; out.write(buffer,0,n) }
    return out.toByteArray()
}
