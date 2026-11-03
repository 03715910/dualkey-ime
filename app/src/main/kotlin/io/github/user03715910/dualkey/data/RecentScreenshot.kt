package io.github.user03715910.dualkey.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

internal data class RecentScreenshot(val uri: Uri, val mime: String, val expiresAt: Long) {
    companion object {
        private const val MAX_AGE_MS = 120_000L
        private val screenshotName = Regex("^screenshot[_ -]\\d{8}.*", RegexOption.IGNORE_CASE)

        fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasAccess(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

        fun find(context: Context): RecentScreenshot? {
            if (Build.VERSION.SDK_INT < 29 || !hasAccess(context)) return null
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_ADDED)
            val now = System.currentTimeMillis()
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.IS_PENDING} = 0"
            context.contentResolver.query(collection, columns, selection, arrayOf(((now - MAX_AGE_MS) / 1000).toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")?.use { rows ->
                while (rows.moveToNext()) {
                    val name = rows.getString(1).orEmpty()
                    val path = rows.getString(2).orEmpty()
                    val mime = rows.getString(3).orEmpty()
                    val added = rows.getLong(4) * 1000
                    val screenshot = path.split('/').any { it.equals("Screenshots", true) } || screenshotName.matches(name)
                    if (screenshot && mime.startsWith("image/") && now - added in 0 until MAX_AGE_MS) {
                        return RecentScreenshot(ContentUris.withAppendedId(collection, rows.getLong(0)), mime, added + MAX_AGE_MS)
                    }
                }
            }
            return null
        }
    }
}
