package cl.droidnoise.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Barra del parche activo: espina izquierda = anterior, espina derecha = siguiente,
 * centro (número romano + categoría) = abre el selector.
 */
@SuppressLint("ViewConstructor")
class PatchBarView(context: Context, private val romanFace: Typeface, private val smallFace: Typeface) : View(context) {
    var roman = "—"
        set(v) { field = v; invalidate() }
    var cat = ""
        set(v) { field = v; invalidate() }
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onOpen: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val bg = ShardDrawable(Palette.SURFACE, 0xBA2, d, thorns = 8)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = romanFace; color = Palette.FG; textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = smallFace; color = Palette.MUTED; textAlign = Paint.Align.CENTER; textSize = dp(12f)
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private var left = Path()
    private var right = Path()
    private var pressed = 0 // -1 izquierda, 0 nada, 1 derecha, 2 centro

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bg.setBounds(0, 0, w, h)
        big.textSize = h * 0.42f
        // Espinas-flecha: punta larga hacia afuera con dos púas hacia atrás.
        fun thornArrow(tipX: Float, dir: Float) = Path().apply {
            val cy = h / 2f
            moveTo(tipX, cy)
            lineTo(tipX - dir * dp(26f), cy - dp(9f))
            lineTo(tipX - dir * dp(20f), cy - dp(2f))
            lineTo(tipX - dir * dp(38f), cy - dp(4f))
            lineTo(tipX - dir * dp(22f), cy + dp(1f))
            lineTo(tipX - dir * dp(34f), cy + dp(10f))
            lineTo(tipX - dir * dp(18f), cy + dp(4f))
            close()
        }
        left = thornArrow(dp(22f), -1f)
        right = thornArrow(w - dp(22f), 1f)
    }

    override fun onDraw(c: Canvas) {
        bg.draw(c)
        arrow.color = if (pressed == -1) Palette.FG else Palette.ACCENT
        c.drawPath(left, arrow)
        arrow.color = if (pressed == 1) Palette.FG else Palette.ACCENT
        c.drawPath(right, arrow)
        big.color = if (pressed == 2) Palette.ACCENT else Palette.FG
        c.drawText(roman, width / 2f, height * 0.58f, big)
        c.drawText(cat, width / 2f, height - dp(12f), small)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val zone = when {
            e.x < width * 0.25f -> -1
            e.x > width * 0.75f -> 1
            else -> 2
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = zone; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (zone == pressed) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    when (zone) { -1 -> onPrev(); 1 -> onNext(); else -> onOpen() }
                }
                pressed = 0; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = 0; invalidate() }
        }
        return true
    }
}
