package cl.droidnoise.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import org.puredata.android.io.AudioParameters
import org.puredata.android.io.PdAudio
import org.puredata.core.PdBase
import org.puredata.core.PdReceiver
import java.io.File

/** Dueño de libpd: audio, parche, sensores y hot reload. Todo lo de Pd corre en [pd]. */
class AudioService : Service() {

    /** Estado que la Activity muestra; se escribe desde el hilo de Pd. */
    object Status {
        @Volatile var running = false
        @Volatile var sampleRate = 0
        @Volatile var patch = "-"
        @Volatile var lastError = "-"
        @Volatile var sensors = "-"

        // Para la UI: últimos valores crudos de gesto y nivel (dB, 100 = 0 dBFS) de cada rama.
        val ori = FloatArray(3)
        @Volatile var light = 0f
        @Volatile var gyro = 0f
        val levels = HashMap<String, Float>()
    }

    private val pdThread = HandlerThread("pd")
    private lateinit var pd: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensors: SensorBridge? = null
    private var observer: FileObserver? = null
    private var patchHandle = 0
    private val reloadRunnable = Runnable { reloadPatch("cambio en ${Paths.root.path}") }

    override fun onCreate() {
        super.onCreate()
        pdThread.start()
        pd = Handler(pdThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pd.post {
                    shutdown()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (Status.running) pd.post { reloadPatch("pedido manual") }
                return START_STICKY
            }
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        pd.post { if (!Status.running) startUp() }
        return START_STICKY
    }

    override fun onDestroy() {
        pd.post { shutdown() }
        pdThread.quitSafely()
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Audio", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mono)
            .setContentTitle("DroidNoise")
            .setContentText("sonando")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(Notification.Action.Builder(null, "Recargar", serviceIntent(ACTION_RELOAD, 1)).build())
            .addAction(Notification.Action.Builder(null, "Detener", serviceIntent(ACTION_STOP, 2)).build())
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Típicamente: falta RECORD_AUDIO o el sistema no deja iniciar desde segundo plano.
            Status.lastError = "startForeground: ${e.message}"
            Log.e(TAG, "no pude pasar a foreground", e)
            false
        }
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, AudioService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )

    // --- hilo de Pd ---

    private fun startUp() {
        try {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidNoise:audio")
                .apply { acquire() }

            val config = Config.load()
            AudioParameters.init(this)
            val sr = AudioParameters.suggestSampleRate()
            Log.i(TAG, "sr sugerido $sr, low latency ${AudioParameters.supportsLowLatency()}")
            // Con OpenSL (lo que usa el AAR) ticksPerBuffer se ignora; el buffer es el blocksize de Pd.
            PdAudio.initAudio(sr, 1, 2, 8, true)
            Log.i(TAG, "audio: ${PdBase.audioImplementation()}, blocksize ${PdBase.blockSize()}")
            Status.sampleRate = sr

            PdBase.setReceiver(receiver)
            PdBase.subscribe("toapp")
            METERS.forEach { PdBase.subscribe(it) }
            PdBase.addToSearchPath(Paths.root.absolutePath)

            sensors = SensorBridge(this, config.sensorMaxHz).also {
                it.start()
                Status.sensors = it.available.joinToString(" ")
                if (it.missing.isNotEmpty()) Log.w(TAG, "faltan sensores: ${it.missing}")
            }

            loadPatch()
            PdAudio.startAudio(this)
            Status.running = true
            sendInfo()
            watchPatchDir()
            Log.i(TAG, "player arriba")
        } catch (e: Exception) {
            Status.lastError = "inicio: ${e.message}"
            Log.e(TAG, "falló el inicio", e)
            shutdown()
        }
    }

    private fun shutdown() {
        observer?.stopWatching()
        observer = null
        pd.removeCallbacks(reloadRunnable)
        sensors?.stop()
        sensors = null
        if (Status.running) {
            PdAudio.stopAudio()
            closePatch()
            PdAudio.release()
            PdBase.release()
        }
        Status.running = false
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Log.i(TAG, "player detenido")
    }

    private fun closePatch() {
        if (patchHandle != 0) {
            PdBase.closePatch(patchHandle)
            patchHandle = 0
        }
    }

    private fun loadPatch() {
        closePatch()
        val main = Paths.mainPatch
        try {
            if (!main.canRead()) throw java.io.FileNotFoundException("${main.path} no existe o no se puede leer")
            patchHandle = PdBase.openPatch(main)
            Status.patch = main.path
            Status.lastError = "-"
            Log.i(TAG, "parche cargado: ${main.path} (\$0=$patchHandle)")
        } catch (e: Exception) {
            Status.lastError = e.message ?: e.toString()
            Log.w(TAG, "uso el fallback: $e")
            patchHandle = PdBase.openPatch(extractFallback())
            Status.patch = "fallback (beep)"
        }
    }

    private fun reloadPatch(reason: String) {
        if (!Status.running) return
        Log.i(TAG, "recargando ($reason)")
        try {
            loadPatch()
            sendInfo()
        } catch (e: Exception) {
            Status.lastError = "recarga: ${e.message}"
            Log.e(TAG, "falló la recarga", e)
        }
    }

    private fun sendInfo() {
        PdBase.sendMessage("info", "sr", Status.sampleRate)
        Params.resendAll(this)
        val s = sensors ?: return
        PdBase.sendMessage("info", "sensors", *s.available.toTypedArray())
        s.resendLatched()
    }

    private fun extractFallback(): File {
        val out = File(filesDir, "fallback.pd")
        assets.open("fallback.pd").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /**
     * FileObserver sobre la carpeta y sus subcarpetas directas (menos `apk/`).
     * Debounce: cada evento reinicia el timer, así un push de varios archivos da una sola recarga.
     */
    private fun watchPatchDir() {
        val root = Paths.root
        if (!root.isDirectory && !root.mkdirs()) {
            Log.w(TAG, "no puedo crear ${root.path}; ¿falta 'Acceso a todos los archivos'?")
            return
        }
        val dirs = listOf(root) + (root.listFiles { f -> f.isDirectory && f.name != "apk" }?.toList() ?: emptyList())
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.DELETE or FileObserver.MOVED_FROM
        observer = object : FileObserver(dirs, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path.substringAfterLast('.') !in WATCHED_EXTENSIONS) return
                pd.removeCallbacks(reloadRunnable)
                pd.postDelayed(reloadRunnable, RELOAD_DEBOUNCE_MS)
            }
        }.also { it.startWatching() }
        Log.i(TAG, "vigilando ${dirs.joinToString { it.name }}")
    }

    private val receiver = object : PdReceiver {
        // libpd ya junta los pedazos (libpd_print_concatenator) y entrega la línea sin '\n'.
        override fun print(s: String) {
            val line = s.trimEnd()
            Log.i(TAG, "pd: $line")
            if ("couldn't create" in line || line.startsWith("error")) Status.lastError = line
        }

        override fun receiveMessage(source: String, symbol: String, vararg args: Any) {
            if (source != "toapp") return
            when (symbol) {
                "vibrate" -> vibrate(((args.firstOrNull() as? Number)?.toLong() ?: 50L).coerceIn(1, 5000))
                "log" -> Log.i(TAG, "toapp: ${args.joinToString(" ")}")
                else -> Log.w(TAG, "toapp: mensaje desconocido '$symbol'")
            }
        }

        override fun receiveList(source: String, vararg args: Any) {
            Log.w(TAG, "toapp: lista sin selector ${args.joinToString(" ")}")
        }

        override fun receiveBang(source: String) {}
        // Medidores dn-m-*: llegan en el hilo principal (pollPdMessageQueue), igual que la UI.
        override fun receiveFloat(source: String, x: Float) {
            Status.levels[source] = x
        }
        override fun receiveSymbol(source: String, symbol: String) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        const val ACTION_STOP = "cl.droidnoise.player.STOP"
        const val ACTION_RELOAD = "cl.droidnoise.player.RELOAD"
        private const val CHANNEL = "audio"
        private const val NOTIFICATION_ID = 1
        private const val RELOAD_DEBOUNCE_MS = 500L
        private val WATCHED_EXTENSIONS = setOf("pd", "txt", "wav", "aif", "aiff")
        val METERS = listOf("dn-m-out", "dn-m-mic", "dn-m-core", "dn-m-chaos", "dn-m-drone", "dn-m-grains")

        fun start(context: Context) =
            context.startForegroundService(Intent(context, AudioService::class.java))

        fun send(context: Context, action: String) =
            context.startService(Intent(context, AudioService::class.java).setAction(action))
    }
}
