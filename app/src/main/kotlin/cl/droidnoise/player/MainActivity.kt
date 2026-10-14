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
    private lateinit var rec: Button
    private lateinit var pad: MotionPadView
    private lateinit var bar: PatchBarView
    private val strips = mutableListOf<FaderStripView>()
    private var patches: List<Bank.Patch> = emptyList()
    private var patch: Bank.Patch? = null
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
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96f)))

        // Parche activo: ◄ anterior · número romano (abre el selector) · siguiente ►
        bar = PatchBarView(this, brutal, regular).apply {
            onPrev = { step(-1) }
            onNext = { step(1) }
            onOpen = { openSelector() }
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68f)).apply { topMargin = dp(4f) })

        // Performance: visor del gesto + transporte y HOLD.
        val perf = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        pad = MotionPadView(this)
        perf.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        val transport = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        toggle = bigButton("PLAY", Palette.SURFACE, 34f, Palette.ACCENT) { toggleService() }
        transport.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        hold = bigButton("HOLD", Palette.SURFACE, 34f) { toggleHold() }
        transport.addView(hold, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })
        rec = bigButton("REC", Palette.SURFACE, 34f, Palette.MUTED) { AudioService.send(this, AudioService.ACTION_REC) }
        transport.addView(rec, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8f) })
        perf.addView(transport, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(10f) })
        root.addView(perf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200f)).apply { topMargin = dp(8f) })

        // Mixer: 6 canales verticales, todos a la vista.
        val mixer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for (i in 0 until 6) {
            val strip = FaderStripView(this, i + 1, brutal, regular).apply {
                onChange = { v -> patch?.let { Params.set(this@MainActivity, it, index, v) } }
            }
            strips += strip
            mixer.addView(strip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = dp(8f)
            })
        }
        root.addView(mixer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14f) })
        setContentView(root)
        hideSystemBars()
        // Es la pantalla de inicio: "atrás" no tiene a dónde volver.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {}

        // Mismo tamaño de etiqueta en todos los canales: el mayor que quepa en la más ancha.
        refreshHold()
        loadPatches()

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
        if (Environment.isExternalStorageManager()) loadPatches()
        if (Environment.isExternalStorageManager()) WallpaperApplier.applyIfChanged(this)
    }

    // Al volver al frente (inicio, otra app, diálogo) el sistema puede mostrar las barras de nuevo.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Instrumento a pantalla completa: barras del sistema ocultas, aparecen deslizando el borde. */
    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(android.view.WindowInsets.Type.systemBars())
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
        // La UI manda: solo adopta el parche del servicio si todavía no tiene uno.
        if (patch == null && s.running && s.patch != "-") patches.firstOrNull { it.id == s.patch }?.let(::applyPatch)
        refreshRec()
        strips.forEach { st ->
            // dB de env~ (100 = 0 dBFS) → 0..1 sobre los últimos 60 dB.
            val db = s.levels["dn-m-${st.index}"] ?: 0f
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
            appendLine("parche  ${patch?.let { "${it.roman} (${it.id}) ${it.cat}" } ?: "-"}")
            if (s.recording) appendLine("REC     ${s.recPath.substringAfterLast('/')}")
            appendLine("sens.   ${s.sensors}")
            appendLine("error   ${s.lastError}")
            appendLine("DroidNoise · Adolfo Rosas")
            append("logo SÜDBÖS · Isidora Mansilla")
        }
    }

    private fun toggleHold() {
        Params.setHold(this, !Params.hold(this))
        hold.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        refreshHold()
    }

    private fun refreshHold() {
        val on = Params.hold(this)
        (hold.background as ShardDrawable).color = if (on) Palette.HOLD else Palette.SURFACE
        hold.setTextColor(if (on) Palette.BG else Palette.MUTED)
        pad.held = if (on) (pad.held ?: AudioService.Status.ori.copyOf()) else null
    }

    // --- banco de parches ---

    private fun loadPatches() {
        patches = Bank.scan()
        (Bank.current(this, patches) ?: patch)?.let(::applyPatch)
    }

    /** Pone en la UI los 6 faders del parche (etiquetas, defaults, valores guardados). */
    private fun applyPatch(p: Bank.Patch) {
        patch = p
        bar.roman = p.roman
        bar.cat = p.cat
        strips.forEach { st ->
            val f = p.faders[st.index - 1]
            st.labelText = f.label
            st.defaultValue = f.default
            st.setValue(Params.get(this, p, st.index), notify = false)
        }
        strips.firstOrNull()?.post { fitStripLabels() }
    }

    private fun selectPatch(p: Bank.Patch) {
        Bank.setCurrentId(this, p.id)
        applyPatch(p)
        AudioService.select(this, p.id)
    }

    private fun step(dir: Int) {
        if (patches.isEmpty()) return
        val i = patches.indexOfFirst { it.id == patch?.id }
        selectPatch(patches[Math.floorMod(i + dir, patches.size)])
    }

    private fun refreshRec() {
        val s = AudioService.Status
        val bg = rec.background as ShardDrawable
        if (s.recording) {
            val secs = (android.os.SystemClock.elapsedRealtime() - s.recStartMs) / 1000
            // Números en Caskaydia: los de Thorn Oath traen marca de agua.
            val t = "REC  %02d:%02d".format(secs / 60, secs % 60)
            val span = android.text.SpannableString(t)
            span.setSpan(android.text.style.TypefaceSpan(regular), 5, t.length, 0)
            if (rec.text.toString() != t) rec.text = span
            // Late a 1 Hz mientras graba.
            bg.color = if (secs % 2 == 0L) Palette.ACCENT else Palette.ACCENT_BG
            rec.setTextColor(Palette.FG)
        } else if (rec.text.toString() != "REC") {
            rec.text = "REC"
            bg.color = Palette.SURFACE
            rec.setTextColor(Palette.MUTED)
        }
    }

    /**
     * Selector para cientos de parches: filtro por categoría (todas / favoritos / cada categoría),
     * grilla con scroll que recicla celdas, toque = cargar, toque largo = favorito, DADO = al azar
     * dentro del filtro.
     */
    private var selectorFilter = "todas"

    private fun openSelector() {
        patches = Bank.scan()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, seed = 0x5E1)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        }
        lateinit var dialog: Dialog
        val cats = listOf("todas", "fav") + patches.map { it.cat }.distinct().sorted()
        fun visible() = patches.filter {
            when (selectorFilter) {
                "todas" -> true
                "fav" -> Bank.isFavorite(this, it.id)
                else -> it.cat == selectorFilter
            }
        }
        val cellBg = HashMap<String, ShardDrawable>()
        val adapter = object : android.widget.BaseAdapter() {
            var items = visible()
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                val p = items[i]
                val v = (convert as? TextView) ?: TextView(this@MainActivity).apply {
                    typeface = brutal
                    gravity = Gravity.CENTER
                    maxLines = 1
                    layoutParams = android.widget.AbsListView.LayoutParams(android.widget.AbsListView.LayoutParams.MATCH_PARENT, dp(64f))
                    setAutoSizeTextTypeUniformWithConfiguration(10, 30, 1, TypedValue.COMPLEX_UNIT_SP)
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                }
                val fav = Bank.isFavorite(this@MainActivity, p.id)
                val color = when {
                    p.id == patch?.id -> Palette.ACCENT_BG
                    fav -> Palette.DEEP
                    else -> Palette.BG
                }
                v.text = p.roman
                v.setTextColor(if (p.id == patch?.id || fav) Palette.FG else Palette.MUTED)
                v.background = cellBg.getOrPut(p.id) { ShardDrawable(color, p.id.hashCode(), density, thorns = 3) }
                    .also { it.color = color }
                return v
            }
        }
        val grid = android.widget.GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(6f)
            verticalSpacing = dp(6f)
            this.adapter = adapter
            setOnItemClickListener { _, _, i, _ -> dialog.dismiss(); selectPatch(adapter.items[i]) }
            setOnItemLongClickListener { _, view, i, _ ->
                Bank.toggleFavorite(this@MainActivity, adapter.items[i].id)
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                adapter.items = visible(); adapter.notifyDataSetChanged(); true
            }
        }
        val info = TextView(this).apply {
            typeface = regular
            setTextColor(Palette.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        fun refresh() {
            adapter.items = visible()
            adapter.notifyDataSetChanged()
            info.text = "${adapter.items.size} parches · toque largo = favorito"
        }
        // Filtros: fila horizontal con scroll.
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = mutableListOf<TextView>()
        cats.forEach { cat ->
            val n = when (cat) {
                "todas" -> patches.size
                "fav" -> patches.count { Bank.isFavorite(this, it.id) }
                else -> patches.count { it.cat == cat }
            }
            val chip = TextView(this).apply {
                text = "$cat $n"
                typeface = regular
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                setOnClickListener {
                    selectorFilter = cat
                    chipViews.forEach { c -> c.setTextColor(if (c == this) Palette.FG else Palette.MUTED); c.background = rounded(if (c == this) Palette.ACCENT_BG else Palette.BG, seed = c.text.hashCode()) }
                    refresh()
                }
            }
            chip.setTextColor(if (cat == selectorFilter) Palette.FG else Palette.MUTED)
            chip.background = rounded(if (cat == selectorFilter) Palette.ACCENT_BG else Palette.BG, seed = chip.text.hashCode())
            chipViews += chip
            chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
        }
        box.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        box.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55f).toInt()).apply { topMargin = dp(10f) })
        box.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        box.addView(bigButton("DADO", Palette.ACCENT_BG, 34f) {
            dialog.dismiss()
            adapter.items.filter { it.id != patch?.id }.randomOrNull()?.let(::selectPatch)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72f)).apply { topMargin = dp(8f) })
        refresh()
        // Arranca con el parche actual a la vista.
        grid.post { grid.setSelection(adapter.items.indexOfFirst { it.id == patch?.id }.coerceAtLeast(0)) }
        dialog = Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(box)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setLayout(resources.displayMetrics.widthPixels - dp(20f), WindowManager.LayoutParams.WRAP_CONTENT)
            show()
        }
    }

    private val launchable by lazy {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { Pair(it.loadLabel(packageManager).toString(), it.activityInfo) }
            .sortedBy { it.first.lowercase() }
    }

    /** Resultados del buscador: hasta 8 apps cuyo nombre contiene el texto (sin tildes ni mayúsculas). */
    private fun showApps(into: LinearLayout, query: String) {
        into.removeAllViews()
        val q = norm(query.trim())
        if (q.isEmpty()) return
        launchable.filter { norm(it.first).contains(q) }.take(8).forEach { (label, info) ->
            into.addView(TextView(this).apply {
                text = label
                typeface = regular
                setTextColor(Palette.FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(8f), dp(14f), dp(8f), dp(14f))
                setOnClickListener {
                    sheet?.dismiss()
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.packageName, info.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
    }

    private fun norm(t: String) =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    private fun fitStripLabels() {
        val strip = strips.firstOrNull() ?: return
        // Las espinas de la fuente pueden pasar del borde de la tira: se mide solo el cuerpo.
        val maxW = strip.width - dp(4f)
        val probe = android.graphics.Paint().apply { typeface = brutal }
        var size = dp(40f).toFloat()
        while (size > dp(10f)) {
            probe.textSize = size
            if (strips.all { probe.measureText(it.labelText) <= maxW }) break
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

        // Buscador de apps: las apps no están a la vista, pero todo se puede abrir desde aquí.
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = android.widget.EditText(this).apply {
            hint = "buscar app…"
            typeface = regular
            setTextColor(Palette.FG)
            setHintTextColor(Palette.DEEP)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = rounded(Palette.BG, seed = 0xF1D)
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) = showApps(results, e?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        box.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(results)
        box.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
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
            window?.setGravity(Gravity.TOP)
            // Que el teclado no tape los resultados.
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
