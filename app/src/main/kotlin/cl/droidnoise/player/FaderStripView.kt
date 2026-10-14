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

    private val seed = fader.receiver.hashCode()
    private val bg = ShardDrawable(Palette.SURFACE, seed, d, thorns = 9)
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.BG; style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ACCENT; style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val segOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); strokeWidth = dp(2.2f) }
    private val segOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; strokeWidth = dp(2.2f) }
    private val segHot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.FG; strokeWidth = dp(2.6f) }
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capScar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BG; strokeWidth = dp(2f) }
    private var groovePath = android.graphics.Path()
    private var capPath = android.graphics.Path()
    private val segJitter = FloatArray(48) { (java.util.Random(seed.toLong() + it).nextFloat() - 0.5f) }
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg.setBounds(0, 0, w, h)
        // Ranura: trazo vertical tembloroso, como hecho a mano.
        val rnd = java.util.Random(seed.toLong() * 7)
        val cx = w / 2f - dp(5f)
        groovePath = android.graphics.Path().apply {
            var y = trackTop
            moveTo(cx, y)
            while (y < trackBottom) {
                y = (y + dp(10f) + rnd.nextFloat() * dp(10f)).coerceAtMost(trackBottom)
                lineTo(cx + (rnd.nextFloat() - 0.5f) * dp(2.4f), y)
            }
        }
        // Cap: esquirla con espinas, centrada en el origen.
        val capW = w - dp(18f)
        val capH = dp(34f)
        val body = RectF(-capW / 2 + dp(6f), -capH / 2 + dp(6f), capW / 2 - dp(6f), capH / 2 - dp(6f))
        capPath = Rough.shard(body, seed * 13, d, thorns = 4, room = dp(6f))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.draw(c)

        // Etiqueta (Thorn Oath): las espinas pueden pasar el borde, el padre no recorta.
        label.color = if (dragging) Palette.ACCENT else Palette.FG
        c.drawText(fader.label, w / 2, labelH - dp(10f), label)

        val top = trackTop
        val bottom = trackBottom
        val cx = w / 2 - dp(5f)
        val y = bottom - (bottom - top) * value

        // Ranura y recorrido lleno (el lleno es la misma ranura recortada desde el cap hacia abajo).
        c.drawPath(groovePath, groove)
        c.save()
        c.clipRect(0f, y, w, bottom + dp(6f))
        c.drawPath(groovePath, fill)
        c.restore()

        // Medidor de la rama: rasguños finos en diagonal, de largo irregular (zarpazos).
        val segs = 24
        val gap = dp(4f)
        val segH = ((bottom - top) - gap * (segs - 1)) / segs
        val mx = cx + dp(12f)
        val lit = (level * segs).roundToInt()
        for (i in 0 until segs) {
            val sy = bottom - (i + 1) * segH - i * gap
            val p = when {
                i >= lit -> segOff
                i >= segs - 3 -> segHot
                else -> segOn
            }
            val len = dp(9f) + segJitter[i] * dp(6f)
            c.drawLine(mx, sy + segH, mx + len, sy - segH * 0.3f + segJitter[i + 24] * dp(2f), p)
        }

        // Cap: esquirla espinosa con una cicatriz al centro.
        cap.color = if (dragging) Palette.ACCENT else Palette.FG
        c.save()
        c.translate(w / 2, y)
        c.drawPath(capPath, cap)
        c.drawLine(-w / 2 + dp(18f), dp(1f), w / 2 - dp(20f), -dp(1f), capScar)
        c.restore()

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
