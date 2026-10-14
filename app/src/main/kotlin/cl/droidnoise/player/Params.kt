package cl.droidnoise.player

import android.content.Context
import org.puredata.core.PdBase

/**
 * Faders (6 por parche, receivers dn-f1..dn-f6) y HOLD. Cada parche guarda sus propios valores
 * en SharedPreferences; se reenvían tras cada carga.
 */
object Params {
    const val HOLD = "hold"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("params", Context.MODE_PRIVATE)

    private fun key(patch: Bank.Patch, n: Int) = "${patch.id}:f$n"

    fun get(context: Context, patch: Bank.Patch, n: Int): Float =
        prefs(context).getFloat(key(patch, n), patch.faders[n - 1].default)

    fun set(context: Context, patch: Bank.Patch, n: Int, value: Float) {
        prefs(context).edit().putFloat(key(patch, n), value).apply()
        if (AudioService.Status.running && AudioService.Status.patch == patch.id) PdBase.sendFloat("dn-f$n", value)
    }

    fun hold(context: Context): Boolean = prefs(context).getFloat(HOLD, 0f) != 0f

    fun setHold(context: Context, on: Boolean) {
        prefs(context).edit().putFloat(HOLD, if (on) 1f else 0f).apply()
        if (AudioService.Status.running) PdBase.sendFloat(HOLD, if (on) 1f else 0f)
    }

    fun resendAll(context: Context, patch: Bank.Patch?) {
        if (patch != null) for (n in 1..6) PdBase.sendFloat("dn-f$n", get(context, patch, n))
        PdBase.sendFloat(HOLD, if (hold(context)) 1f else 0f)
    }
}
