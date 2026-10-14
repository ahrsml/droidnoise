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

/**
 * Dueño de libpd: audio, master, parche del banco (con crossfade al cambiar), REC, sensores y
 * hot reload. Todo lo de Pd corre en [pd].
 */
class AudioService : Service() {

    /** Estado que la Activity muestra; se escribe desde el hilo de Pd. */
    object Status {
        @Volatile var running = false
        @Volatile var sampleRate = 0
        /** id del parche del banco que suena ("-" si ninguno). */
        @Volatile var patch = "-"
        @Volatile var recording = false
        @Volatile var recStartMs = 0L
        @Volatile var recPath = ""
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
    private var masterHandle = 0
    private var patchHandle = 0
    private var current: Bank.Patch? = null
    private val reloadRunnable = Runnable { reloadPatch("cambio en ${current?.dir?.path}") }
    @Volatile private var pendingSelect: String? = null
    private val selectRunnable = Runnable {
        val id = pendingSelect ?: return@Runnable
        pendingSelect = null
        if (id != current?.id) select(id)
    }

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
            ACTION_REC -> {
                if (Status.running) pd.post { toggleRec() }
                return START_STICKY
            }
            ACTION_SELECT -> {
                // Toques rápidos (flechas) se juntan: solo se carga el último, 350 ms después.
                val id = intent.getStringExtra(EXTRA_PATCH)
                if (Status.running && id != null) {
                    pendingSelect = id
                    pd.removeCallbacks(selectRunnable)
                    pd.postDelayed(selectRunnable, SELECT_DEBOUNCE_MS)
                }
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
            PdBase.addToSearchPath(Bank.lib.absolutePath)
            PdBase.addToSearchPath(Paths.root.absolutePath)

            sensors = SensorBridge(this, config.sensorMaxHz).also {
                it.start()
                Status.sensors = it.available.joinToString(" ")
                if (it.missing.isNotEmpty()) Log.w(TAG, "faltan sensores: ${it.missing}")
            }

            // El master vive siempre: suma los parches, saca al jack y graba.
            masterHandle = PdBase.openPatch(extractAsset("master.pd"))
            PdAudio.startAudio(this)
            Status.running = true
            loadInitial()
            Log.i(TAG, "player arriba")
        } catch (e: Exception) {
            Status.lastError = "inicio: ${e.message}"
            Log.e(TAG, "falló el inicio", e)
            shutdown()
        }
    }

    private fun shutdown() {
        if (Status.recording) stopRec()
        observer?.stopWatching()
        observer = null
        pd.removeCallbacks(reloadRunnable)
        sensors?.stop()
        sensors = null
        if (Status.running) {
            PdAudio.stopAudio()
            if (patchHandle != 0) PdBase.closePatch(patchHandle)
            if (masterHandle != 0) PdBase.closePatch(masterHandle)
            patchHandle = 0
            masterHandle = 0
            PdAudio.release()
            PdBase.release()
        }
        Status.running = false
        Status.patch = "-"
        current = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Log.i(TAG, "player detenido")
    }

    /** Parche guardado del banco; sin banco, el main.pd suelto de la carpeta o el beep interno. */
    private fun loadInitial() {
        val patch = Bank.current(this)
        if (patch != null) {
            switchTo(patch, "inicio")
            return
        }
        val legacy = Paths.mainPatch
        try {
            patchHandle = PdBase.openPatch(if (legacy.canRead()) legacy else extractAsset("fallback.pd"))
            Status.patch = "-"
            Log.w(TAG, "banco vacío: uso ${if (legacy.canRead()) legacy.path else "el fallback"}")
        } catch (e: Exception) {
            Status.lastError = e.message ?: e.toString()
        }
        sendInfo()
    }

    private fun select(id: String) {
        val patch = Bank.scan().firstOrNull { it.id == id } ?: return
        Bank.setCurrentId(this, id)
        switchTo(patch, "selección")
    }

    /**
     * Abre el parche nuevo (entra solo con su fade) y saca el anterior: fade de 1,5 s por
     * [out~ $0] y lo cierra después. Durante el cruce suenan los dos.
     */
    private fun switchTo(patch: Bank.Patch, reason: String) {
        val old = patchHandle
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            patchHandle = PdBase.openPatch(patch.main)
            current = patch
            Status.patch = patch.id
            Status.lastError = "-"
            Log.i(TAG, "parche ${patch.id} (${patch.cat}) cargado por $reason en ${android.os.SystemClock.elapsedRealtime() - t0} ms, \$0=$patchHandle")
        } catch (e: Exception) {
            Status.lastError = "parche ${patch.id}: ${e.message}"
            Log.e(TAG, "no pude abrir ${patch.main}", e)
            return
        }
        sendInfo()
        if (patch.usesSamples) sendSample()
        if (old != 0) {
            PdBase.sendFloat("$old-fade", 0f)
            pd.postDelayed({ PdBase.closePatch(old) }, CROSSFADE_MS + 200)
        }
        watch(patch)
    }

    private fun reloadPatch(reason: String) {
        if (!Status.running) return
        Bank.invalidate()  // cambió algo dentro del parche: releer su faders.txt
        val patch = current?.let { c -> Bank.scan().firstOrNull { it.id == c.id } } ?: return
        switchTo(patch, reason)
    }

    private fun sendInfo() {
        PdBase.sendMessage("info", "sr", Status.sampleRate)
        Params.resendAll(this, current)
        val s = sensors ?: return
        PdBase.sendMessage("info", "sensors", *s.available.toTypedArray())
        s.resendLatched()
    }

    // --- samples (parches de categoría "samples") ---

    /** Un WAV al azar de samples/ o rec/ (sin la grabación en curso); si no hay, el parche graba el mic. */
    private fun sendSample() {
        val pool = listOf(File(Paths.root, "samples"), File(Paths.root, "rec"))
            .flatMap { d -> d.walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".wav", true) }.toList() }
            .filter { it.path != Status.recPath && it.length() > 44 }
        if (pool.isEmpty()) {
            PdBase.sendBang("dn-sample-none")
            Log.i(TAG, "sin samples: el parche graba el mic")
        } else {
            val f = pool.random()
            PdBase.sendSymbol("dn-sample", f.absolutePath)
            Log.i(TAG, "sample: ${f.name}")
        }
    }

    // --- REC ---

    private fun toggleRec() = if (Status.recording) stopRec() else startRec()

    private fun startRec() {
        // rec/<parche>/AAAA-MM-DD_HH-MM-SS.wav: una carpeta por parche, con su nombre.
        val dir = File(File(Paths.root, "rec"), Status.patch.takeIf { it != "-" } ?: "sin-parche").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "$stamp.wav")
        PdBase.sendMessage("dn-rec", "open", "-bytes", 3, file.absolutePath)
        // writesf~ rechaza "start" en el mismo instante lógico que "open".
        pd.postDelayed({ PdBase.sendMessage("dn-rec", "start") }, 100)
        Status.recPath = file.absolutePath
        Status.recStartMs = android.os.SystemClock.elapsedRealtime()
        Status.recording = true
        Log.i(TAG, "REC → ${file.path}")
    }

    private fun stopRec() {
        PdBase.sendMessage("dn-rec", "stop")
        Status.recording = false
        Log.i(TAG, "REC detenido: ${Status.recPath}")
    }

    private fun extractAsset(name: String): File {
        val out = File(filesDir, name)
        assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /**
     * FileObserver sobre la carpeta del parche activo y lib/. Debounce: cada evento reinicia el
     * timer, así un push de varios archivos da una sola recarga.
     */
    private fun watch(patch: Bank.Patch) {
        observer?.stopWatching()
        val dirs = listOf(patch.dir, Bank.lib).filter { it.isDirectory }
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.DELETE or FileObserver.MOVED_FROM
        observer = object : FileObserver(dirs, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path.substringAfterLast('.') !in WATCHED_EXTENSIONS) return
                pd.removeCallbacks(reloadRunnable)
                pd.postDelayed(reloadRunnable, RELOAD_DEBOUNCE_MS)
            }
        }.also { it.startWatching() }
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
                // Llega en el hilo principal: el envío a Pd va por el hilo de Pd.
                "sample" -> pd.post { sendSample() }
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
        const val ACTION_REC = "cl.droidnoise.player.REC"
        const val ACTION_SELECT = "cl.droidnoise.player.SELECT"
        const val EXTRA_PATCH = "patch"
        private const val CHANNEL = "audio"
        private const val NOTIFICATION_ID = 1
        private const val RELOAD_DEBOUNCE_MS = 500L
        private const val CROSSFADE_MS = 1500L
        private const val SELECT_DEBOUNCE_MS = 350L
        private val WATCHED_EXTENSIONS = setOf("pd", "txt", "wav", "aif", "aiff")
        val METERS = (1..6).map { "dn-m-$it" } + "dn-m-master"

        fun start(context: Context) =
            context.startForegroundService(Intent(context, AudioService::class.java))

        fun send(context: Context, action: String) =
            context.startService(Intent(context, AudioService::class.java).setAction(action))

        fun select(context: Context, id: String) =
            context.startService(Intent(context, AudioService::class.java).setAction(ACTION_SELECT).putExtra(EXTRA_PATCH, id))
    }
}
