package cl.droidnoise.player

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Superficie de instrumento: cabecera con el logo, fila de performance (visor de gesto,
 * PLAY/STOP, HOLD) y mixer de 6 canales verticales con medidores. Mantenimiento (recargar,
 * actualizar, estado) en la hoja "•••", fuera del escenario.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var toggle: Button
    private lateinit var hold: Button
    private lateinit var pad: MotionPadView
    private val strips = mutableListOf<FaderStripView>()
    private var sheet: Dialog? = null
    private var sheetStatus: TextView? = null
    private val askedThisSession = mutableSetOf<String>()
    private var userStopped = false

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Float) = (v * density).toInt()

    // ~30 fps mientras la pantalla está visible: visor, medidores y estado del transporte.
    private val frame = object : Runnable {
        override fun run() {
            refreshFrame()
            ui.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.statusBarColor = Palette.BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = Palette.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(Palette.BG)
            setPadding(dp(14f), dp(10f), dp(14f), dp(14f))
        }

        // Cabecera: logo de SÜDBÖS (Isidora Mansilla) y acceso a la hoja de sistema.
        val header = FrameLayout(this).apply { clipChildren = false }
        // Halo carmín difuso detrás del logo, como el resplandor de la muestra.
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_sudbos)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Palette.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN)
            alpha = 0.9f
            setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(12f).toFloat(), dp(12f).toFloat(), android.graphics.Shader.TileMode.DECAL))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_sudbos)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Palette.FG, android.graphics.PorterDuff.Mode.SRC_IN)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        header.addView(TextView(this).apply {
            text = "•••"
            typeface = regular
            setTextColor(Palette.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(14f), dp(4f), dp(4f), dp(14f))
            setOnClickListener { openSheet() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(104f)))

        // Performance: visor del gesto + transporte y HOLD.
        val perf = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        pad = MotionPadView(this)
        perf.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        val transport = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        toggle = bigButton("PLAY", Palette.SURFACE, 34f, Palette.ACCENT) { toggleService() }
        transport.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        hold = bigButton("HOLD", Palette.SURFACE, 34f) { toggleHold() }
        transport.addView(hold, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10f) })
        perf.addView(transport, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(10f) })
        root.addView(perf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(188f)).apply { topMargin = dp(6f) })

        // Mixer: 6 canales verticales, todos a la vista.
        val mixer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        Params.faders.forEachIndexed { i, f ->
            val strip = FaderStripView(this, f, brutal, regular).apply {
                setValue(Params.get(this@MainActivity, f.receiver), notify = false)
                onChange = { v -> Params.set(this@MainActivity, f.receiver, v) }
            }
            strips += strip
            mixer.addView(strip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = dp(8f)
            })
        }
        root.addView(mixer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14f) })
        setContentView(root)

        // Mismo tamaño de etiqueta en todos los canales: el mayor que quepa en la más ancha.
        mixer.post { fitStripLabels() }
        refreshHold()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ui.post(frame)
        ensurePermissionsThenStart()
    }

    override fun onPause() {
        ui.removeCallbacks(frame)
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

    private fun refreshFrame() {
        val s = AudioService.Status
        toggle.text = if (s.running) "STOP" else "PLAY"
        (toggle.background as ShardDrawable).color = if (s.running) Palette.ACCENT_BG else Palette.SURFACE
        toggle.setTextColor(if (s.running) Palette.FG else Palette.ACCENT)
        pad.invalidate()
        strips.forEach { st ->
            // dB de env~ (100 = 0 dBFS) → 0..1 sobre los últimos 60 dB.
            val db = s.levels[Params.faders[strips.indexOf(st)].meter] ?: 0f
            st.level = if (s.running) ((db - 40f) / 60f).coerceIn(0f, 1f) else 0f
            st.invalidate()
        }
        sheetStatus?.text = statusText()
    }

    private fun statusText(): String {
        val s = AudioService.Status
        val vc = packageManager.getPackageInfo(packageName, 0).longVersionCode
        return buildString {
            appendLine("${if (s.running) "sonando" else "detenido"} · ${s.sampleRate} Hz · v$vc")
            appendLine("parche  ${s.patch.substringAfterLast('/')}")
            appendLine("sens.   ${s.sensors}")
            appendLine("error   ${s.lastError}")
            append("logo SÜDBÖS: Isidora Mansilla")
        }
    }

    private fun toggleHold() {
        val on = Params.get(this, Params.HOLD) == 0f
        Params.set(this, Params.HOLD, if (on) 1f else 0f)
        hold.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        refreshHold()
    }

    private fun refreshHold() {
        val on = Params.get(this, Params.HOLD) != 0f
        (hold.background as ShardDrawable).color = if (on) Palette.HOLD else Palette.SURFACE
        hold.setTextColor(if (on) Palette.BG else Palette.MUTED)
        pad.held = if (on) (pad.held ?: AudioService.Status.ori.copyOf()) else null
    }

    private fun fitStripLabels() {
        val strip = strips.firstOrNull() ?: return
        // Las espinas de la fuente pueden pasar del borde de la tira: se mide solo el cuerpo.
        val maxW = strip.width - dp(4f)
        val probe = android.graphics.Paint().apply { typeface = brutal }
        var size = dp(40f).toFloat()
        while (size > dp(10f)) {
            probe.textSize = size
            if (Params.faders.all { probe.measureText(it.label) <= maxW }) break
            size -= 1f
        }
        strips.forEach { it.labelSize = size }
    }

    /** Hoja de sistema: recargar parche, instalar actualización y estado técnico. */
    private fun openSheet() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, seed = 0x5E7)
            setPadding(dp(18f), dp(16f), dp(18f), dp(18f))
            clipChildren = false
        }
        val info = TextView(this).apply {
            typeface = regular
            setTextColor(Palette.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.2f)
            text = statusText()
        }
        sheetStatus = info
        box.addView(info)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        row.addView(bigButton("RLD", Palette.DEEP, 30f) { AudioService.send(this, AudioService.ACTION_RELOAD) },
            LinearLayout.LayoutParams(0, dp(72f), 1f))
        row.addView(bigButton("UPD", Palette.ACCENT_BG, 30f) { sheet?.dismiss(); installUpdate() },
            LinearLayout.LayoutParams(0, dp(72f), 1f).apply { leftMargin = dp(10f) })
        box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })
        sheet = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(box)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setLayout(resources.displayMetrics.widthPixels - dp(28f), WindowManager.LayoutParams.WRAP_CONTENT)
            setOnDismissListener { sheetStatus = null; sheet = null }
            show()
        }
    }

    private val regular by lazy { resources.getFont(R.font.caskaydia_regular) }
    // Thorn Oath (Denustudio, demo de uso personal) para botones y faders, en la línea del logo
    // de SÜDBÖS. Sus números traen marca de agua: no se muestran. Caskaydia queda para el estado.
    private val brutal by lazy { resources.getFont(R.font.thorn_oath) }

    // Fondos rasgados: cada uno con su semilla para que todos tengan forma distinta y estable.
    private fun rounded(color: Int, seed: Int = color) = ShardDrawable(color, seed, density)

    private fun bigButton(
        label: String, color: Int, sizeSp: Float, textColor: Int = Palette.FG, onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = brutal
        letterSpacing = 0f
        isAllCaps = true
        maxLines = 1
        // Que el texto se achique antes de partirse en dos líneas ("ACTUALIZAR").
        setAutoSizeTextTypeUniformWithConfiguration(10, sizeSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        stateListAnimator = null
        setTextColor(textColor)
        background = rounded(color, seed = label.hashCode())
        gravity = Gravity.CENTER
        setPadding(8, 10, 8, 10)
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_INSTALL = "install"
    }
}
