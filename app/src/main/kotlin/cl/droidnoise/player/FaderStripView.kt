package cl.droidnoise.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Canal vertical de mixer: etiqueta, recorrido largo con cap, medidor segmentado de la rama
 * y lectura numérica.
 *
 * Toque relativo: se arrastra desde cualquier punto de la tira y el valor no salta a donde cae
 * el dedo. Doble toque vuelve al valor por defecto.
 */
@SuppressLint("ViewConstructor")
class FaderStripView(
    context: Context,
    private val fader: Params.Fader,
    private val labelFace: Typeface,
    private val numFace: Typeface,
) : View(context) {

    var value = 0f
        private set
    /** Nivel de la rama, 0..1 (lo actualiza la Activity desde los medidores de Pd). */
    var level = 0f
    var onChange: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SURFACE }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BG }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val segOff = Paint().apply { color = 0xFF2E2E2E.toInt() }
    private val segOn = Paint().apply { color = Palette.MUTED }
    private val segHot = Paint().apply { color = Palette.FG }
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capLine = Paint().apply { color = Palette.BG }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = labelFace
        textAlign = Paint.Align.CENTER
        color = Palette.FG
    }
    private val readout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = numFace
        textAlign = Paint.Align.CENTER
        color = Palette.MUTED
        textSize = dp(13f)
    }
    private val rect = RectF()

    private var dragging = false
    private var startY = 0f
    private var startValue = 0f
    private var moved = false
    private var lastTapUp = 0L

    /** Tamaño de etiqueta común para todos los canales (lo fija la Activity). */
    var labelSize = dp(22f)
        set(v) { field = v; label.textSize = v; invalidate() }

    fun setValue(v: Float, notify: Boolean) {
        val nv = v.coerceIn(0f, 1f)
        if (nv == value) return
        val crossedEdge = (nv == 0f || nv == 1f) && value != nv
        value = nv
        if (notify) {
            onChange?.invoke(nv)
            if (crossedEdge) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
    }

    private val labelH get() = dp(46f)
    private val readoutH get() = dp(26f)
    private val trackTop get() = labelH + dp(16f)
    private val trackBottom get() = height - readoutH - dp(16f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(14f)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, bg)

        // Etiqueta (Thorn Oath): las espinas pueden pasar el borde, el padre no recorta.
        label.color = if (dragging) Palette.ACCENT else Palette.FG
        c.drawText(fader.label, w / 2, labelH - dp(10f), label)

        val top = trackTop
        val bottom = trackBottom
        val cx = w / 2 - dp(5f)
        val y = bottom - (bottom - top) * value

        // Ranura y recorrido lleno.
        rect.set(cx - dp(3f), top, cx + dp(3f), bottom)
        c.drawRoundRect(rect, dp(3f), dp(3f), groove)
        rect.set(cx - dp(3f), y, cx + dp(3f), bottom)
        c.drawRoundRect(rect, dp(3f), dp(3f), fill)

        // Medidor segmentado de la rama, a la derecha de la ranura.
        val segs = 24
        val gap = dp(2f)
        val segH = ((bottom - top) - gap * (segs - 1)) / segs
        val mx = cx + dp(14f)
        val lit = (level * segs).roundToInt()
        for (i in 0 until segs) {
            val sy = bottom - (i + 1) * segH - i * gap
            val p = when {
                i >= lit -> segOff
                i >= segs - 3 -> segHot
                else -> segOn
            }
            c.drawRect(mx, sy, mx + dp(5f), sy + segH, p)
        }

        // Cap del fader.
        val capW = w - dp(22f)
        val capH = dp(30f)
        cap.color = if (dragging) Palette.ACCENT else Palette.FG
        rect.set(w / 2 - capW / 2, y - capH / 2, w / 2 + capW / 2, y + capH / 2)
        c.drawRoundRect(rect, dp(6f), dp(6f), cap)
        c.drawRect(rect.left + dp(6f), y - dp(1f), rect.right - dp(6f), y + dp(1f), capLine)

        c.drawText((value * 100).roundToInt().toString(), w / 2, h - dp(10f), readout)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val travel = (trackBottom - trackTop).coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                moved = false
                startY = e.y
                startValue = value
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - startY) > dp(4f)) moved = true
                if (moved) setValue(startValue + (startY - e.y) / travel, notify = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    val now = e.eventTime
                    if (now - lastTapUp < 320) {
                        setValue(fader.default, notify = true)
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        lastTapUp = 0
                    } else {
                        lastTapUp = now
                    }
                }
                invalidate()
            }
        }
        return true
    }
}
