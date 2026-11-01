package io.github.user03715910.dualkey.conversion

import android.content.Context
import io.github.dualkey.core.JapaneseLexicon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Verified APK-bundled JMdict subset; failure leaves the Japanese IME available. */
class SemanticDictionary(context: Context,scope: CoroutineScope) {
    private val current=MutableStateFlow<JapaneseLexicon?>(null)
    val lexicon=current.asStateFlow()
    init {
        scope.launch {
            try {
                current.value=withContext(Dispatchers.IO) {
                    val manifest=context.assets.open("semantic/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
                    val hash=manifest.getString("sha256"); val expected=manifest.getLong("bytes")
                    require(hash.matches(Regex("[a-f0-9]{64}")) && expected in 64..80_000_000)
                    val folder=File(context.noBackupFilesDir,"semantic-dictionary").apply { mkdirs() }
                    val file=File(folder,"lexicon-$hash.dkl")
                    if (!file.isFile || file.length()!=expected || digest(file)!=hash) {
                        val temporary=File(folder,"lexicon.tmp")
                        try {
                            var size=0L
                            GZIPInputStream(context.assets.open("semantic/lexicon.dklz"),65536).use { source ->
                                temporary.outputStream().use { target ->
                                    val buffer=ByteArray(65536)
                                    while (true) {
                                        val length=source.read(buffer); if (length<0) break
                                        size+=length; require(size<=expected); target.write(buffer,0,length)
                                    }
                                }
                            }
                            require(size==expected && digest(temporary)==hash)
                            if (file.exists()) check(file.delete())
                            check(temporary.renameTo(file))
                        } finally { temporary.delete() }
                    }
                    val result=JapaneseLexicon.open(file)
                    folder.listFiles()?.filter { it.name.startsWith("lexicon-") && it.extension=="dkl" && it!=file }?.forEach { it.delete() }
                    result
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Never stop typing or log the user's text. */ }
        }
    }
    private fun digest(file: File): String = file.inputStream().use { input ->
        val hash=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(65536)
        while (true) { val length=input.read(buffer); if (length<0) break; hash.update(buffer,0,length) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
}
