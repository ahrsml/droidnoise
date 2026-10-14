package cl.droidnoise.player

import android.content.Context
import org.puredata.core.PdBase

/**
 * Faders y HOLD de la app → receivers `dn-*` / `hold` en Pd.
 * Se guardan en SharedPreferences y se reenvían tras cada carga del parche.
 */
object Params {
    data class Fader(val receiver: String, val label: String, val default: Float)

    val faders = listOf(
        Fader("dn-vol", "VOL", 0.8f),
        Fader("dn-mic", "MIC", 0.5f),
        Fader("dn-core", "FDBK", 0.6f),
        Fader("dn-chaos", "FM", 0.3f),
        Fader("dn-drone", "DRN", 0.5f),
        Fader("dn-grains", "GRN", 0.6f),
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
