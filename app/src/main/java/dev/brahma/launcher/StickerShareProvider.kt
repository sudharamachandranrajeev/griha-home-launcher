package dev.brahma.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Hands one exported sticker PNG to the app the user shares with. Only files in the share
 * folder of the cache are served, read-only, and only to apps granted the URI for that share.
 */
class StickerShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val folder = shareFolder(context!!.cacheDir)
        val file = File(folder, uri.lastPathSegment ?: throw SecurityException("No file"))
        if (file.parentFile?.canonicalPath != folder.canonicalPath) throw SecurityException("Outside share folder")
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(file.name, file.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        fun shareFolder(cache: File) = File(cache, "share").apply { mkdirs() }
        fun uri(packageName: String, file: File): Uri = Uri.parse("content://$packageName.stickers/${file.name}")
    }
}
