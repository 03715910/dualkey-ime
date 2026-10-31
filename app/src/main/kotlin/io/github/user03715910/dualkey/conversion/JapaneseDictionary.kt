package io.github.user03715910.dualkey.conversion

import android.content.Context
import io.github.dualkey.core.ConversionEngine
import io.github.dualkey.core.DictionaryConversionEngine
import io.github.dualkey.core.JapaneseLexicon
import io.github.dualkey.core.LocalConversionEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** APK-bundled data only. Opening/expansion runs off the input/UI thread. */
class JapaneseDictionary(context: Context, scope: CoroutineScope, user: () -> Map<String, List<String>>) {
    private val fallback: ConversionEngine = LocalConversionEngine(user)
    private val current = MutableStateFlow(fallback)
    val engine = current.asStateFlow()
    private val status = MutableStateFlow("日本語辞書を準備しています…")
    val message = status.asStateFlow()

    init {
        scope.launch {
            try {
                val lexicon = withContext(Dispatchers.IO) {
                    val manifest = context.assets.open("japanese/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
                    val hash = manifest.getString("sha256")
                    val expected = manifest.getLong("bytes")
                    require(hash.matches(Regex("[a-f0-9]{64}")) && expected in 64..150_000_000)
                    val folder = File(context.noBackupFilesDir, "japanese-dictionary").apply { mkdirs() }
                    val file = File(folder, "lexicon-$hash.dkl")
                    if (!file.isFile || file.length() != expected || sha256(file) != hash) {
                        val staging = File(folder, "lexicon.tmp")
                        try {
                            var size = 0L
                            GZIPInputStream(context.assets.open("japanese/lexicon.dklz"),65536).use { source ->
                                staging.outputStream().use { target ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        val length = source.read(buffer)
                                        if (length < 0) break
                                        size += length; require(size <= expected)
                                        target.write(buffer, 0, length)
                                    }
                                }
                            }
                            require(size == expected && sha256(staging) == hash)
                            if (file.exists()) check(file.delete())
                            check(staging.renameTo(file))
                        } finally { staging.delete() }
                    }
                    val mapped = JapaneseLexicon.open(file)
                    folder.listFiles()?.filter { it.name.startsWith("lexicon-") && it.extension == "dkl" && it != file }?.forEach { it.delete() }
                    mapped
                }
                current.value = DictionaryConversionEngine(lexicon, user)
                status.value = "日本語辞書の準備完了"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Disk/data failure must never disable typing. Never log the user's input.
                status.value = "辞書を開けませんでした。空き容量を確認してアプリを開き直してください。"
            }
        }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(65536)
            while (true) { val length = source.read(buffer); if (length < 0) break; digest.update(buffer, 0, length) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
