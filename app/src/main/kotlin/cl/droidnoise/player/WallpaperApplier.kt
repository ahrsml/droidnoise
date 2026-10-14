package cl.droidnoise.player

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Aplica `/sdcard/DroidNoise/wallpaper.png` como fondo de inicio y de bloqueo cuando cambia
 * (se compara la fecha del archivo con la última aplicada). Se sube con `adb push`.
 */
object WallpaperApplier {
    private val file: File get() = File(Paths.root, "wallpaper.png")

    fun applyIfChanged(context: Context) {
        val f = file
        if (!f.canRead()) return
        val prefs = context.applicationContext.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        val stamp = f.lastModified() xor f.length()
        if (prefs.getLong("applied", 0L) == stamp) return
        Thread {
            try {
                f.inputStream().use {
                    WallpaperManager.getInstance(context).setStream(
                        it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                    )
                }
                prefs.edit().putLong("applied", stamp).apply()
                Log.i(TAG, "fondo aplicado: ${f.path}")
            } catch (e: Exception) {
                Log.w(TAG, "no pude aplicar el fondo: $e")
            }
        }.start()
    }
}
