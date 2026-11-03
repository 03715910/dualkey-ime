package io.github.user03715910.dualkey.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class ClipboardMediaProvider : FileProvider()

data class SharedMedia(val file: File,val uri: Uri,val mime: String,val expires: Long)
data class MediaHistoryEntry(val media: SharedMedia,val preview: Bitmap?)

/** Private, bounded staging for explicit paste requests. No clipboard listener reads media bytes. */
class ClipboardMedia(private val context: Context,private val scope: CoroutineScope) {
    private val mutex=Mutex()
    private val directory=File(context.cacheDir,"ime-paste")
    private val authority=context.packageName+".paste"
    private val resolver=context.contentResolver
    private val maxItem=32L*1024*1024
    private val maxTotal=64L*1024*1024
    private val lifetime=10*60*1000L
    private val staging=mutableSetOf<File>()

    init { scope.launch(Dispatchers.IO) { mutex.withLock { prune() } } }

    private fun prune() {
        val now=System.currentTimeMillis()
        directory.listFiles().orEmpty().filter { it.isFile && it !in staging && (now-it.lastModified() !in 0L until lifetime) }
            .forEach { delete(it) }
    }

    private fun delete(file: File) {
        if (file.parentFile!=directory) return
        runCatching { context.revokeUriPermission(FileProvider.getUriForFile(context,authority,file),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        file.delete()
    }

    fun discard(media: SharedMedia) { scope.launch(Dispatchers.IO) { mutex.withLock { delete(media.file) } } }

    fun description(clip: ClipData): Pair<Uri, String>? {
        val uri=if (clip.itemCount>0) clip.getItemAt(0).uri else null
        if (uri?.scheme!="content" || uri.authority.isNullOrEmpty() || uri.authority==authority ||
            uri.authority==context.packageName+".updates") return null
        val mime=resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (!isMedia(mime) || (0 until clip.description.mimeTypeCount).none {
                ClipDescription.compareMimeTypes(mime,clip.description.getMimeType(it))
            }) return null
        return uri to mime
    }

    suspend fun stage(uri: Uri,mime: String): SharedMedia = withContext(Dispatchers.IO) {
        mutex.withLock {
            prune()
            val available=(maxTotal-directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }).coerceAtMost(maxItem)
            require(available>0) { "quota" }
            check(directory.isDirectory || directory.mkdirs())
            val extension=MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "media"
            val file=File(directory,UUID.randomUUID().toString()+"."+extension)
            staging+=file
            var done=false
            try {
                val descriptor=resolver.openAssetFileDescriptor(uri,"r") ?: error("missing")
                descriptor.use {
                    require(it.length<0 || it.length in 1..available) { "size" }
                    it.createInputStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer=ByteArray(32*1024)
                            var total=0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val length=input.read(buffer)
                                if (length<0) break
                                total+=length
                                require(total<=available) { "size" }
                                output.write(buffer,0,length)
                            }
                            require(total>0) { "empty" }
                        }
                    }
                }
                val shared=SharedMedia(file,FileProvider.getUriForFile(context,authority,file),mime,System.currentTimeMillis()+lifetime)
                done=true
                scope.launch(Dispatchers.IO) { delay(lifetime); mutex.withLock { delete(file) } }
                shared
            } finally { staging-=file; if (!done) file.delete() }
        }
    }

    suspend fun thumbnail(media: SharedMedia): Bitmap? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT<29) null
        else runCatching {
            val size=Size(96,96)
            val signal=CancellationSignal()
            if (media.mime.startsWith("video/")) ThumbnailUtils.createVideoThumbnail(media.file,size,signal)
            else ThumbnailUtils.createImageThumbnail(media.file,size,signal)
        }.getOrNull()
    }

    companion object {
        fun isMedia(mime: String)=(mime.startsWith("image/") || mime.startsWith("video/")) && '*' !in mime &&
            mime.substringAfter('/').matches(Regex("[a-z0-9][a-z0-9.+_-]{0,63}"))
        fun accepts(mime: String,types: Array<out String>?) = isMedia(mime) &&
            types.orEmpty().any { ClipDescription.compareMimeTypes(mime,it) }
    }
}
