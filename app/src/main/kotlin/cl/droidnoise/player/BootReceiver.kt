package cl.droidnoise.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Android 14 no deja iniciar un FGS de micrófono desde BOOT_COMPLETED.
 * Se abre la Activity (permitido con "Mostrar sobre otras apps") y ella arranca el service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Config.load().bootStart) {
            Log.i(TAG, "boot: boot=1 no está en config.txt, no arranco")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "boot: falta 'Mostrar sobre otras apps', no puedo abrir la app")
            return
        }
        Log.i(TAG, "boot: abriendo DroidNoise")
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
