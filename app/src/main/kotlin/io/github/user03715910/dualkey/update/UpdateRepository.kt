package io.github.user03715910.dualkey.update

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import io.github.dualkey.core.*
import io.github.user03715910.dualkey.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

data class UpdateState(val message: String="更新は未確認です", val release: ReleaseInfo?=null, val apk: File?=null,
    val busy: Boolean=false, val progress: Int?=null)

class UpdateRepository(private val context: Context) {
    private val policy=UpdatePolicy(context.packageName,BuildConfig.WEBSITE_URL,BuildConfig.RELEASE_REPOSITORY)
    private val network=OfficialNetwork(policy)
    private val mutex=Mutex()
    val state=MutableStateFlow(UpdateState())
    private val directory=File(context.cacheDir,"updates")
    private val cached=AtomicFile(File(context.filesDir,"release-info.json"))
    val configured get()=BuildConfig.METADATA_URL.isNotBlank()
    private fun verifiedFile(info: ReleaseInfo): File? {
        val file=File(directory,"verified-${info.versionCode}.apk")
        if (!file.exists()) return null
        return runCatching { ApkSafety(context).verify(file,info); file }.getOrElse { file.delete(); null }
    }
    suspend fun restoreCached() = withContext(Dispatchers.IO) { mutex.withLock {
        val release=cachedRelease() ?: return@withLock
        val file=verifiedFile(release)
        state.value=UpdateState(if (file!=null) "検証済みの更新APKを利用できます" else "新しいバージョン ${release.versionName} があります",release,file)
    } }
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) { mutex.withLock {
        if (!configured) { state.value=UpdateState("配布サイトはまだ設定されていません"); return@withLock null }
        state.value=state.value.copy(message="更新情報を確認しています…",busy=true)
        try {
            val text=network.metadata()
            val info=ReleaseJson.parse(text); policy.validate(info)
            val available=policy.isNewer(info,BuildConfig.VERSION_CODE.toLong(),Build.VERSION.SDK_INT)
            val output=cached.startWrite()
            try { output.write(text.toByteArray()); cached.finishWrite(output) } catch (e: Exception) { cached.failWrite(output); throw e }
            state.value=UpdateState(if (available) "新しいバージョン ${info.versionName} があります" else if (info.minSdk>Build.VERSION.SDK_INT) "新しい版はこのAndroidに対応していません" else "最新バージョンを使用しています",if (available) info else null,if (available) verifiedFile(info) else null)
            if (available) info else null
        } catch (e: CancellationException) { state.value=state.value.copy(busy=false); throw e }
        catch (e: Exception) { state.value=state.value.copy(message="更新を確認できませんでした。${e.message.orEmpty().take(160)}",busy=false); null }
    } }
    suspend fun cachedRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching { ReleaseJson.parse(cached.readFully().toString(Charsets.UTF_8)).also { policy.validate(it); check(policy.isNewer(it,BuildConfig.VERSION_CODE.toLong(),Build.VERSION.SDK_INT)) } }.getOrNull()
    }
    suspend fun download(info: ReleaseInfo): File? = withContext(Dispatchers.IO) { mutex.withLock {
        policy.validate(info)
        check(policy.isNewer(info,BuildConfig.VERSION_CODE.toLong(),Build.VERSION.SDK_INT))
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.endsWith(".part") || System.currentTimeMillis()-it.lastModified()>7*86_400_000L }?.forEach { it.delete() }
        val part=File(directory,"download.part")
        val verified=File(directory,"verified-${info.versionCode}.apk")
        state.value=UpdateState("APKを取得しています…",info,busy=true,progress=0)
        try {
            if (verified.exists()) { ApkSafety(context).verify(verified,info); state.value=UpdateState("検証済みAPKを利用できます",info,verified); return@withLock verified }
            val connection=network.apk(info.apkUrl)
            try {
                check(connection.contentLengthLong==-1L || connection.contentLengthLong==info.sizeBytes) { "配布ファイルの容量が一致しません" }
                val digest=MessageDigest.getInstance("SHA-256")
                var total=0L
                connection.inputStream.use { input -> part.outputStream().use { out ->
                    val buffer=ByteArray(65536)
                    while (true) {
                        ensureActive()
                        val n=input.read(buffer); if (n<0) break
                        total+=n; check(total<=info.sizeBytes && total<=UpdatePolicy.MAX_APK_BYTES) { "APKの容量が上限を超えています" }
                        out.write(buffer,0,n); digest.update(buffer,0,n)
                        state.value=state.value.copy(progress=(100*total/info.sizeBytes).toInt())
                    }
                    out.fd.sync()
                } }
                check(total==info.sizeBytes && digest.digest().joinToString("") { "%02x".format(it) }==info.sha256.lowercase()) { "APKが破損しています" }
            } finally { connection.disconnect() }
            state.value=state.value.copy(message="署名とAPKを検証しています…",progress=null)
            ApkSafety(context).verify(part,info)
            check(part.renameTo(verified)) { "検証済みファイルを保存できません" }
            state.value=UpdateState("検証が完了しました。Androidの確認画面で更新できます",info,verified)
            verified
        } catch (e: CancellationException) { state.value=state.value.copy(busy=false); throw e }
        catch (e: Exception) { verified.delete(); state.value=UpdateState("更新APKを取得できませんでした。${e.message.orEmpty().take(160)}",info); null }
        finally { part.delete() }
    } }
}
