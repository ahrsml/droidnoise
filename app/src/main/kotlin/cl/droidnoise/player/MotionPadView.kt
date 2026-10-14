package cl.droidnoise.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
class MotionPadView(context: Context) : View(context) {

    /** alpha, beta, gamma congelados al activar HOLD; null = sin HOLD. */
    var held: FloatArray? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val bg = ShardDrawable(Palette.SURFACE, 0x5DB0, d, thorns = 10)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Palette.DEEP; strokeWidth = dp(1.6f); strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); strokeWidth = dp(1f) }
    private val lightOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND }
    private val lightOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303030.toInt(); strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND }
    private val compass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heldDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.LAVENDER }

    private var ringPath = Path()
    private var gridPath = Path()
    private var spikes = FloatArray(0)      // corona de luz: x0 y0 x1 y1 por espina
    private val star = Rough.thornStar(dp(15f), 7, 0xB0)
    private val heldStar = Rough.thornStar(dp(21f), 9, 0xD0)
    private val compassThorn = Path().apply {
        moveTo(-dp(3f), 0f); lineTo(0f, -dp(16f)); lineTo(dp(3f), 0f); close()
    }
    private var cx = 0f
    private var cy = 0f
    private var inner = 0f
    private var outer = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg.setBounds(0, 0, w, h)
        cx = w / 2f
        cy = h / 2f
        outer = min(w, h) / 2f - dp(22f)
        inner = outer - dp(14f)
        ringPath = Rough.roughCircle(cx, cy, outer, 0x41, d)
        gridPath = Path().apply {
            val rnd = java.util.Random(0x99)
            fun wob() = (rnd.nextFloat() - 0.5f) * dp(3f)
            moveTo(cx - inner, cy + wob()); lineTo(cx, cy + wob()); lineTo(cx + inner, cy + wob())
            moveTo(cx + wob(), cy - inner); lineTo(cx + wob(), cy); lineTo(cx + wob(), cy + inner)
        }
        val n = 40
        val rnd = java.util.Random(0x77)
        spikes = FloatArray(n * 4)
        for (i in 0 until n) {
            val a = -Math.PI / 2 + i * 2 * Math.PI / n
            val r0 = inner - dp(2f)
            val r1 = inner - dp(6f) - rnd.nextFloat() * dp(9f)
            spikes[i * 4] = cx + (cos(a) * r0).toFloat()
            spikes[i * 4 + 1] = cy + (sin(a) * r0).toFloat()
            spikes[i * 4 + 2] = cx + (cos(a) * r1).toFloat()
            spikes[i * 4 + 3] = cy + (sin(a) * r1).toFloat()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Cuadrado del alto disponible.
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(h, h)
    }

    override fun onDraw(c: Canvas) {
        val s = AudioService.Status
        bg.draw(c)
        c.drawPath(gridPath, grid.apply { style = Paint.Style.STROKE })
        c.drawPath(ringPath, ring)

        // Luz: corona de espinas hacia adentro; se encienden según el log de los lux.
        val lightNorm = (ln(s.light + 1f) / ln(1001f)).coerceIn(0f, 1f)
        val lit = (lightNorm * spikes.size / 4).toInt()
        for (i in 0 until spikes.size / 4) {
            c.drawLine(spikes[i * 4], spikes[i * 4 + 1], spikes[i * 4 + 2], spikes[i * 4 + 3], if (i < lit) lightOn else lightOff)
        }

        // Brújula: espina sobre el aro, apuntando hacia afuera.
        c.save()
        c.translate(cx, cy)
        c.rotate(s.ori[0])
        c.translate(0f, -outer + dp(4f))
        c.drawPath(compassThorn, compass)
        c.restore()

        fun pos(beta: Float, gamma: Float): Pair<Float, Float> =
            Pair(cx + inner * 0.82f * (gamma / 90f).coerceIn(-1f, 1f), cy + inner * 0.82f * (beta / 90f).coerceIn(-1f, 1f))

        val hv = held
        if (hv != null) {
            val (hx, hy) = pos(hv[1], hv[2])
            c.save(); c.translate(hx, hy); c.drawPath(heldStar, heldDot); c.restore()
        }
        // Gesto vivo: estrella de espinas que gira y crece con la velocidad angular.
        val (lx, ly) = pos(s.ori[1], s.ori[2])
        val spin = (s.gyro / 6f).coerceIn(0f, 1f)
        dot.color = if (hv == null) Palette.FG else 0xFF5A5A5A.toInt()
        c.save()
        c.translate(lx, ly)
        c.rotate(s.ori[0] * 2f)
        val k = 1f + spin * 0.9f
        c.scale(k, k)
        c.drawPath(star, dot)
        c.restore()
    }
}
