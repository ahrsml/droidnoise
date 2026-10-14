package cl.droidnoise.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Visor del gesto: punto = inclinación (gamma horizontal, beta vertical), marca en el aro =
 * brújula (alpha), arco interior = luz, halo del punto = giro. Con HOLD queda marcada la
 * posición congelada y el gesto vivo se ve atenuado.
 */
class MotionPadView(context: Context, numFace: Typeface) : View(context) {

    /** alpha, beta, gamma congelados al activar HOLD; null = sin HOLD. */
    var held: FloatArray? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SURFACE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.DEEP; strokeWidth = dp(2f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF323232.toInt(); strokeWidth = dp(1f) }
    private val lightArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.MUTED; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND
    }
    private val compass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ACCENT; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val heldDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.LAVENDER }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = numFace; color = 0xFF6B6B6B.toInt(); textSize = dp(11f)
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Cuadrado del alto disponible.
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(h, h)
    }

    override fun onDraw(c: Canvas) {
        val s = AudioService.Status
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, dp(14f), dp(14f), bg)

        val cx = w / 2
        val cy = h / 2
        val outer = min(w, h) / 2 - dp(14f)
        val inner = outer - dp(12f)

        c.drawLine(cx - inner, cy, cx + inner, cy, grid)
        c.drawLine(cx, cy - inner, cx, cy + inner, grid)
        c.drawCircle(cx, cy, outer, ring)
        c.drawCircle(cx, cy, inner / 2, grid.apply { style = Paint.Style.STROKE })
        grid.style = Paint.Style.FILL

        // Luz: arco interior proporcional al log de los lux (0..~1000).
        val lightNorm = (ln(s.light + 1f) / ln(1001f)).coerceIn(0f, 1f)
        rect.set(cx - inner, cy - inner, cx + inner, cy + inner)
        c.drawArc(rect, -90f, 360f * lightNorm, false, lightArc)

        // Brújula: marca en el aro.
        val a = Math.toRadians(s.ori[0].toDouble() - 90)
        c.drawLine(
            cx + (outer - dp(6f)) * cos(a).toFloat(), cy + (outer - dp(6f)) * sin(a).toFloat(),
            cx + (outer + dp(6f)) * cos(a).toFloat(), cy + (outer + dp(6f)) * sin(a).toFloat(), compass,
        )

        fun pos(beta: Float, gamma: Float): Pair<Float, Float> =
            Pair(cx + inner * (gamma / 90f).coerceIn(-1f, 1f), cy + inner * (beta / 90f).coerceIn(-1f, 1f))

        val hv = held
        val (lx, ly) = pos(s.ori[1], s.ori[2])
        val live = if (hv == null) Palette.FG else 0xFF5A5A5A.toInt()
        // Giro: halo que crece con la velocidad angular.
        val spin = (s.gyro / 6f).coerceIn(0f, 1f)
        halo.color = live
        halo.alpha = (60 + 140 * spin).toInt()
        c.drawCircle(lx, ly, dp(12f) + dp(18f) * spin, halo)
        dot.color = live
        c.drawCircle(lx, ly, dp(9f), dot)

        if (hv != null) {
            val (hx, hy) = pos(hv[1], hv[2])
            c.drawCircle(hx, hy, dp(11f), heldDot)
        }

        c.drawText("tilt · brújula · luz", dp(12f), h - dp(10f), caption)
    }
}
