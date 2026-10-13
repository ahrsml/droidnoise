package cl.droidnoise.player

import android.os.Environment
import android.util.Log
import java.io.File

const val TAG = "DroidNoise"

object Paths {
    val root: File get() = File(Environment.getExternalStorageDirectory(), "DroidNoise")
    val mainPatch: File get() = File(root, "main.pd")
    val config: File get() = File(root, "config.txt")
    val apk: File get() = File(root, "apk/droidnoise.apk")
}

/** `/sdcard/DroidNoise/config.txt`: líneas `clave=valor`, `#` comenta. */
class Config(private val values: Map<String, String>) {
    val bootStart: Boolean get() = values["boot"] == "1"
    val sensorMaxHz: Int get() = values["sensor_hz"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 200

    companion object {
        fun load(): Config {
            val file = Paths.config
            val values = mutableMapOf<String, String>()
            try {
                if (file.canRead()) {
                    file.readLines().forEach { raw ->
                        val line = raw.substringBefore('#').trim()
                        val eq = line.indexOf('=')
                        if (eq > 0) values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "no pude leer ${file.path}: $e")
            }
            return Config(values)
        }
    }
}
