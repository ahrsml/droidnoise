package cl.droidnoise.player

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Geometría "rasgada" en la línea del logo de SÜDBÖS: bordes irregulares, espinas que salen del
 * contorno y textura de tinta seca. Todo con semilla fija: cada elemento tiene su forma estable.
 */
object Rough {

    /**
     * Contorno rasgado de [body] con espinas hacia afuera. Las espinas no pasan de [room]
     * (el margen entre [body] y el borde de la vista) para que no se corten.
     */
    fun shard(body: RectF, seed: Int, d: Float, thorns: Int, room: Float): Path {
        val rnd = Random(seed.toLong())
        val path = Path()
        // Esquinas recortadas de forma desigual (nada de radios perfectos).
        val cut = { rnd.nextFloat() * 7f * d + 2f * d }
        val c = floatArrayOf(cut(), cut(), cut(), cut(), cut(), cut(), cut(), cut())
        val corners = arrayOf(
            floatArrayOf(body.left + c[0], body.top), floatArrayOf(body.right - c[1], body.top),
            floatArrayOf(body.right, body.top + c[2]), floatArrayOf(body.right, body.bottom - c[3]),
            floatArrayOf(body.right - c[4], body.bottom), floatArrayOf(body.left + c[5], body.bottom),
            floatArrayOf(body.left, body.bottom - c[6]), floatArrayOf(body.left, body.top + c[7]),
        )
        // Perímetro aproximado para repartir espinas: más probables cerca de las esquinas.
        val perim = 2 * (body.width() + body.height())
        val stepAvg = 8f * d
        val thornProb = thorns * stepAvg / perim
        var first = true
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            val tx = (b[0] - a[0]) / len
            val ty = (b[1] - a[1]) / len
            // Normal hacia afuera (recorrido en sentido horario con y hacia abajo).
            val nx = ty
            val ny = -tx
            var t = 0f
            while (t < len) {
                val jitter = (rnd.nextFloat() * 2f - 1f) * 2.2f * d
                val px = a[0] + tx * t + nx * jitter
                val py = a[1] + ty * t + ny * jitter
                if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
                val nearCorner = t < 14f * d || len - t < 14f * d
                if (rnd.nextFloat() < thornProb * (if (nearCorner) 2.6f else 0.6f)) {
                    // Espina: base angosta, punta larga y algo inclinada, como las del logo.
                    val w = (1.2f + rnd.nextFloat() * 2.2f) * d
                    val reach = (0.45f + rnd.nextFloat() * 0.55f) * room
                    val lean = (rnd.nextFloat() * 2f - 1f) * reach * 0.6f
                    path.lineTo(px + tx * w, py + ty * w)
                    path.lineTo(px + tx * (w + lean) + nx * reach, py + ty * (w + lean) + ny * reach)
                    path.lineTo(px + tx * w * 2.2f, py + ty * w * 2.2f)
                }
                t += (5f + rnd.nextFloat() * 7f) * d
            }
        }
        path.close()
        return path
    }

    /** Puntos y rayas para "tinta seca" dentro de [body], en coordenadas de la vista. */
    class Grain(body: RectF, seed: Int, d: Float) {
        val dots: FloatArray
        val scratches: FloatArray
        init {
            val rnd = Random(seed * 31L + 7)
            val n = (body.width() * body.height() / (11f * d * 11f * d) * 0.5f).toInt().coerceIn(6, 400)
            dots = FloatArray(n * 2) { i ->
                if (i % 2 == 0) body.left + rnd.nextFloat() * body.width()
                else body.top + rnd.nextFloat() * body.height()
            }
            val m = 2 + rnd.nextInt(4)
            scratches = FloatArray(m * 4)
            for (k in 0 until m) {
                val x = body.left + rnd.nextFloat() * body.width()
                val y = body.top + rnd.nextFloat() * body.height()
                val ang = (rnd.nextFloat() * 2f - 1f) * 0.45f + (if (rnd.nextBoolean()) 0f else PI.toFloat() / 2)
                val l = (8f + rnd.nextFloat() * 30f) * d
                scratches[k * 4] = x
                scratches[k * 4 + 1] = y
                scratches[k * 4 + 2] = x + cos(ang) * l
                scratches[k * 4 + 3] = y + sin(ang) * l
            }
        }

        fun draw(c: Canvas, clip: Path, hole: Paint) {
            c.save()
            c.clipPath(clip)
            c.drawPoints(dots, hole)
            c.drawLines(scratches, hole)
            c.restore()
        }
    }

    /** Círculo trazado a mano: radio con ruido, dos pasadas para que parezca brochazo. */
    fun roughCircle(cx: Float, cy: Float, r: Float, seed: Int, d: Float): Path {
        val rnd = Random(seed.toLong())
        val path = Path()
        val n = 72
        for (pass in 0..1) {
            val off = rnd.nextFloat() * 2f * PI.toFloat()
            for (i in 0..n) {
                val a = off + i * 2f * PI.toFloat() / n * (if (pass == 0) 1f else 0.93f)
                val rr = r + (rnd.nextFloat() * 2f - 1f) * 1.8f * d + pass * 1.5f * d
                val x = cx + cos(a) * rr
                val y = cy + sin(a) * rr
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        return path
    }

    /**
     * Tallo vertical espinoso en [x], de [top] a [bottom]: cuerpo de grosor irregular con
     * mini espinas que brotan a ambos lados inclinadas hacia arriba (path para rellenar).
     */
    fun thornyVine(x: Float, top: Float, bottom: Float, seed: Int, d: Float, width: Float = 2.2f): Path {
        val rnd = Random(seed.toLong())
        data class Node(val y: Float, val cx: Float, val hw: Float)
        val nodes = ArrayList<Node>()
        var y = top
        while (true) {
            nodes += Node(y, x + (rnd.nextFloat() - 0.5f) * 1.6f * d, (width * 0.6f + rnd.nextFloat() * width * 0.6f) * d)
            if (y >= bottom) break
            y = (y + (5f + rnd.nextFloat() * 8f) * d).coerceAtMost(bottom)
        }
        val path = Path()
        // Punta superior afilada.
        path.moveTo(nodes.first().cx, top - 3f * d)
        // Borde izquierdo hacia abajo.
        for (n in nodes) {
            val ex = n.cx - n.hw
            if (rnd.nextFloat() < 0.42f) {
                val b = (0.8f + rnd.nextFloat() * 1.2f) * d
                val len = (3.5f + rnd.nextFloat() * 6.5f) * d
                path.lineTo(ex, n.y - b)
                path.lineTo(ex - len, n.y - len * (0.5f + rnd.nextFloat() * 0.9f))
                path.lineTo(ex, n.y + b)
            } else {
                path.lineTo(ex, n.y)
            }
        }
        path.lineTo(nodes.last().cx, bottom + 3f * d)
        // Borde derecho hacia arriba.
        for (n in nodes.asReversed()) {
            val ex = n.cx + n.hw
            if (rnd.nextFloat() < 0.42f) {
                val b = (0.8f + rnd.nextFloat() * 1.2f) * d
                val len = (3.5f + rnd.nextFloat() * 6.5f) * d
                path.lineTo(ex, n.y + b)
                path.lineTo(ex + len, n.y - len * (0.5f + rnd.nextFloat() * 0.9f))
                path.lineTo(ex, n.y - b)
            } else {
                path.lineTo(ex, n.y)
            }
        }
        path.close()
        return path
    }

    /** Mini espinas a lo largo de un círculo, hacia afuera y adentro, inclinadas (path para rellenar). */
    fun thornRing(cx: Float, cy: Float, r: Float, seed: Int, d: Float, count: Int): Path {
        val rnd = Random(seed.toLong())
        val path = Path()
        for (i in 0 until count) {
            val a = (i + rnd.nextFloat() * 0.8f) * 2f * PI.toFloat() / count
            val out = rnd.nextFloat() < 0.7f
            val len = (3f + rnd.nextFloat() * 7f) * d * (if (out) 1f else 0.7f)
            val lean = (rnd.nextFloat() - 0.3f) * 0.5f
            val w = (0.03f + rnd.nextFloat() * 0.02f)
            val bx1 = cx + cos(a - w) * r; val by1 = sin(a - w) * r + cy
            val bx2 = cx + cos(a + w) * r; val by2 = sin(a + w) * r + cy
            val tr = if (out) r + len else r - len
            val tx = cx + cos(a + lean) * tr; val ty = cy + sin(a + lean) * tr
            path.moveTo(bx1, by1); path.lineTo(tx, ty); path.lineTo(bx2, by2); path.close()
        }
        return path
    }

    /** Estrella de espinas irregular centrada en el origen. */
    fun thornStar(r: Float, points: Int, seed: Int): Path {
        val rnd = Random(seed.toLong())
        val path = Path()
        val n = points * 2
        for (i in 0 until n) {
            val a = i * 2f * PI.toFloat() / n + (rnd.nextFloat() - 0.5f) * 0.25f
            val rr = if (i % 2 == 0) r * (0.75f + rnd.nextFloat() * 0.6f) else r * (0.22f + rnd.nextFloat() * 0.12f)
            val x = cos(a) * rr
            val y = sin(a) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }
}

/**
 * Fondo rasgado para botones y paneles: forma con espinas + tinta seca. El cuerpo queda
 * metido [room] px hacia adentro para que las espinas quepan en la vista.
 */
class ShardDrawable(color: Int, private val seed: Int, private val d: Float, private val thorns: Int = 6) : Drawable() {
    var color = color
        set(v) { if (field != v) { field = v; fill.color = v; invalidateSelf() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Palette.BG
        alpha = 150
        strokeWidth = 1.4f * d
        strokeCap = Paint.Cap.BUTT
    }
    private var path = Path()
    private var grain: Rough.Grain? = null
    val room = 9f * d

    override fun onBoundsChange(bounds: Rect) {
        val body = RectF(bounds).apply { inset(room, room) }
        path = Rough.shard(body, seed, d, thorns, room)
        grain = Rough.Grain(body, seed, d)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, fill)
        grain?.draw(canvas, path, hole)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
