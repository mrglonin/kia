package kia.app.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.TextUtils
import java.io.File
import java.io.FileNotFoundException

class UpdateApkProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun getType(uri: Uri): String? {
        return "application/vnd.android.package-archive"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = fileFor(uri)
        val cursor: MatrixCursor =
            MatrixCursor(arrayOf<String?>(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        if (file != null && file.exists())
            cursor.addRow(arrayOf<Any?>(file.getName(), file.length()))
        return cursor
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = fileFor(uri)
        if (file == null || !file.exists() || !file.isFile())
            throw FileNotFoundException("APK not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("read only")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        return 0
    }

    private fun fileFor(uri: Uri?): File? {
        if (getContext() == null || uri == null) return null
        val name = uri.getLastPathSegment()
        if (
            TextUtils.isEmpty(name) ||
                !name!!.endsWith(".apk") ||
                name.contains("/") ||
                name.contains("..")
        )
            return null
        var root: File? = getContext()!!.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (root == null) root = getContext()!!.getFilesDir()
        return File(File(root, UpdateApkProvider.DIR), name)
    }

    companion object {
        const val DIR: String = "updates"
    }
}
