package cl.droidnoise.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Pantalla rota: pocos botones, enormes y de alto contraste. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var hold: Button
    private val askedThisSession = mutableSetOf<String>()
    private var userStopped = false

    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = bigButton("INICIAR", Color.YELLOW, 18f) { toggleService() }
        top.addView(toggle, rowItem())
        top.addView(bigButton("RECARGAR", Color.CYAN, 18f) { AudioService.send(this, AudioService.ACTION_RELOAD) }, rowItem())
        top.addView(bigButton("ACTUALIZAR", Color.MAGENTA, 18f) { installUpdate() }, rowItem())
        root.addView(top, weighted(1.2f))

        hold = bigButton("HOLD", Color.DKGRAY, 26f) {
            val on = Params.get(this, Params.HOLD) == 0f
            Params.set(this, Params.HOLD, if (on) 1f else 0f)
            refreshHold()
        }
        root.addView(hold, weighted(1.2f))
        refreshHold()

        Params.faders.forEach { root.addView(fader(it), weighted(1f)) }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
        }
        root.addView(status, weighted(1.3f))
        setContentView(root)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
        ensurePermissionsThenStart()
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    /** deploy.sh lanza la app con `--ez install true` para abrir directo el instalador. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_INSTALL, false) == true) {
            intent.removeExtra(EXTRA_INSTALL)
            ui.postDelayed({ installUpdate() }, 300)
        }
    }

    /** Pide un permiso por vez; cada vuelta a onResume sigue con el siguiente. */
    private fun ensurePermissionsThenStart() {
        val runtime = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (runtime.isNotEmpty() && askOnce("runtime")) {
            requestPermissions(runtime.toTypedArray(), 1)
            return
        }
        if (!Environment.isExternalStorageManager() && askOnce("storage")) {
            openSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && askOnce("battery")) {
            @Suppress("BatteryLife")
            openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            return
        }
        if (Config.load().bootStart && !Settings.canDrawOverlays(this) && askOnce("overlay")) {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            return
        }
        if (!userStopped && !AudioService.Status.running) startAudio()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ensurePermissionsThenStart()
    }

    private fun askOnce(key: String) = askedThisSession.add(key)

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Log.w(TAG, "no pude abrir $action: $e")
        }
    }

    private fun startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AudioService.Status.lastError = "falta permiso de micrófono"
            return
        }
        AudioService.start(this)
    }

    private fun toggleService() {
        if (AudioService.Status.running) {
            userStopped = true
            AudioService.send(this, AudioService.ACTION_STOP)
        } else {
            userStopped = false
            startAudio()
        }
    }

    private fun installUpdate() {
        if (!packageManager.canRequestPackageInstalls()) {
            AudioService.Status.lastError = "habilita 'Instalar apps desconocidas' y vuelve a tocar"
            openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            return
        }
        if (!Paths.apk.isFile) {
            AudioService.Status.lastError = "no hay ${Paths.apk.path}"
            return
        }
        // Tras actualizar, la tarea se recrea con el intent de deploy.sh (install=true):
        // sin esta comparación el instalador se abriría de nuevo con el mismo APK.
        val installed = packageManager.getPackageInfo(packageName, 0).longVersionCode
        val pending = packageManager.getPackageArchiveInfo(Paths.apk.path, 0)?.longVersionCode
        if (pending == null || pending <= installed) {
            AudioService.Status.lastError = "el APK de la carpeta (${pending ?: "ilegible"}) no es más nuevo que $installed"
            Log.i(TAG, "no instalo: APK $pending, instalada $installed")
            return
        }
        Log.i(TAG, "abriendo instalador para ${Paths.apk.path}")
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(ApkProvider.uri, ApkProvider.MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun refreshStatus() {
        val s = AudioService.Status
        val vc = packageManager.getPackageInfo(packageName, 0).longVersionCode
        toggle.text = if (s.running) "DETENER" else "INICIAR"
        status.text = buildString {
            appendLine("estado : ${if (s.running) "SONANDO" else "detenido"}")
            appendLine("versión: $vc")
            appendLine("sr     : ${s.sampleRate}")
            appendLine("parche : ${s.patch}")
            appendLine("error  : ${s.lastError}")
            append("sens.  : ${s.sensors}")
        }
    }

    private fun refreshHold() {
        val on = Params.get(this, Params.HOLD) != 0f
        hold.text = if (on) "HOLD: GESTOS CONGELADOS" else "HOLD"
        hold.setBackgroundColor(if (on) Color.RED else Color.DKGRAY)
        hold.setTextColor(if (on) Color.WHITE else Color.LTGRAY)
    }

    /** Fader horizontal grueso: etiqueta + SeekBar 0..1000 → 0..1 en Pd. */
    private fun fader(f: Params.Fader): LinearLayout {
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        fun show(v: Float) { label.text = "%s  %d%%".format(f.label, (v * 100).toInt()) }
        val bar = SeekBar(this).apply {
            max = 1000
            progress = (Params.get(this@MainActivity, f.receiver) * 1000).toInt()
            progressTintList = ColorStateList.valueOf(Color.YELLOW)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            minimumHeight = 90
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = value / 1000f
                    Params.set(this@MainActivity, f.receiver, v)
                    show(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        show(bar.progress / 1000f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun rowItem() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(6, 0, 6, 0) }

    private fun bigButton(label: String, color: Int, sizeSp: Float, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.BLACK)
        setBackgroundColor(color)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun weighted(weight: Float) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight).apply {
            setMargins(0, 6, 0, 6)
        }

    companion object {
        const val EXTRA_INSTALL = "install"
    }
}
