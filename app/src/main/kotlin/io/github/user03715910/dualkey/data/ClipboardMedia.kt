package io.github.user03715910.dualkey.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
data class MediaHistoryEntry(val media: SharedMedia,val preview: Bitmap?,val copiedAt: Long)

/** Private, bounded staging for paste and opt-in clipboard history. */
class ClipboardMedia(private val context: Context,private val scope: CoroutineScope) {
    private val mutex=Mutex()
    private val directory=File(context.cacheDir,"ime-paste")
    private val authority=context.packageName+".paste"
    private val resolver=context.contentResolver
    private val maxItem=32L*1024*1024
    private val maxTotal=64L*1024*1024
    private val staging=mutableSetOf<File>()

    init { scope.launch(Dispatchers.IO) { mutex.withLock { prune() } } }

    private fun prune() {
        val now=System.currentTimeMillis()
        directory.listFiles().orEmpty().filter { it.isFile && it !in staging && (it.lastModified()-now !in 1L..3_600_000L) }
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

    suspend fun stage(uri: Uri,mime: String,minutes: Int = 10): SharedMedia = withContext(Dispatchers.IO) {
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
                val lifetime=minutes.coerceIn(5,60)*60_000L
                val expires=System.currentTimeMillis()+lifetime
                check(file.setLastModified(expires))
                val shared=SharedMedia(file,FileProvider.getUriForFile(context,authority,file),mime,expires)
                done=true
                scope.launch(Dispatchers.IO) { delay(lifetime); mutex.withLock { delete(file) } }
                shared
            } finally { staging-=file; if (!done) file.delete() }
        }
    }

    suspend fun clipboardThumbnail(uri: Uri,mime: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (mime.startsWith("image/") && Build.VERSION.SDK_INT>=28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri)) { decoder,info,_ ->
                    val ratio=(96f/maxOf(info.size.width,info.size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),
                        (info.size.height*ratio).toInt().coerceAtLeast(1))
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else if (Build.VERSION.SDK_INT>=29) resolver.loadThumbnail(uri,Size(96,96),null)
            else null
        }.getOrNull()
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
