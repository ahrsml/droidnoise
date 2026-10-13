package cl.droidnoise.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Sirve solo `/sdcard/DroidNoise/apk/droidnoise.apk`, de lectura, al instalador del sistema.
 * Reemplaza a androidx FileProvider para no meter AndroidX por un archivo.
 */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("solo lectura")
        val apk = Paths.apk
        if (!apk.isFile) throw FileNotFoundException(apk.path)
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val apk = Paths.apk
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> apk.name
                OpenableColumns.SIZE -> apk.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        const val MIME = "application/vnd.android.package-archive"
        val uri: Uri = Uri.parse("content://cl.droidnoise.player.apk/droidnoise.apk")
    }
}
