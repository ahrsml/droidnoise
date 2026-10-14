package cl.droidnoise.player

import android.content.Context
import org.puredata.core.PdBase

/**
 * Faders y HOLD de la app → receivers `dn-*` / `hold` en Pd.
 * Se guardan en SharedPreferences y se reenvían tras cada carga del parche.
 */
object Params {
    /** [meter]: receiver de Pd con el nivel de la rama que controla el fader. */
    data class Fader(val receiver: String, val label: String, val default: Float, val meter: String)

    val faders = listOf(
        Fader("dn-vol", "VOL", 0.8f, "dn-m-out"),
        Fader("dn-mic", "MIC", 0.5f, "dn-m-mic"),
        Fader("dn-core", "FDBK", 0.6f, "dn-m-core"),
        Fader("dn-chaos", "FM", 0.3f, "dn-m-chaos"),
        Fader("dn-drone", "DRN", 0.5f, "dn-m-drone"),
        Fader("dn-grains", "GRN", 0.6f, "dn-m-grains"),
    )
    const val HOLD = "hold"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("params", Context.MODE_PRIVATE)

    fun get(context: Context, receiver: String): Float =
        prefs(context).getFloat(receiver, faders.find { it.receiver == receiver }?.default ?: 0f)

    fun set(context: Context, receiver: String, value: Float) {
        prefs(context).edit().putFloat(receiver, value).apply()
        if (AudioService.Status.running) PdBase.sendFloat(receiver, value)
    }

    fun resendAll(context: Context) {
        faders.forEach { PdBase.sendFloat(it.receiver, get(context, it.receiver)) }
        PdBase.sendFloat(HOLD, get(context, HOLD))
    }
}
