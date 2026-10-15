package pt.filipe.profundo

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Personalização do túnel infinito (prefixo "inf_" nas SharedPreferences). */
data class InfinityConfig(
    val shape: Int = SHAPE_RECT,      // forma dos anéis
    val leds: Int = LEDS_BOTH,        // pontos, linha ou ambos
    val hue: Float = 195f,            // 0..360
    val saturation: Float = 0.85f,    // 0 = branco
    val colorCycle: Boolean = false,  // cor muda ao longo da profundidade e do tempo
    val reflect: Float = 0.84f,       // 0.5..0.95 — quanto "dura" cada reflexo
    val spacing: Float = 0.90f,       // 0.84..0.95 — escala entre anéis consecutivos
    val speed: Float = 0.3f,          // 0..1 — LEDs a correr
    val clockDepth: Int = 2,          // em quantos anéis o relógio fica "afundado" (0 = à frente)
    val glow: Boolean = true
) {
    fun save(p: SharedPreferences) = p.edit()
        .putInt("inf_shape", shape).putInt("inf_leds", leds)
        .putFloat("inf_hue", hue).putFloat("inf_sat", saturation)
        .putBoolean("inf_cycle", colorCycle)
        .putFloat("inf_reflect", reflect).putFloat("inf_spacing", spacing)
        .putFloat("inf_speed", speed).putInt("inf_clockdepth", clockDepth)
        .putBoolean("inf_glow", glow)
        .apply()

    companion object {
        const val SHAPE_RECT = 0
        const val SHAPE_CIRCLE = 1
        const val SHAPE_HEX = 2
        const val LEDS_DOTS = 0
        const val LEDS_LINE = 1
        const val LEDS_BOTH = 2

        fun load(p: SharedPreferences) = InfinityConfig(
            shape = p.getInt("inf_shape", SHAPE_RECT),
            leds = p.getInt("inf_leds", LEDS_BOTH),
            hue = p.getFloat("inf_hue", 195f),
            saturation = p.getFloat("inf_sat", 0.85f),
            colorCycle = p.getBoolean("inf_cycle", false),
            reflect = p.getFloat("inf_reflect", 0.84f),
            spacing = p.getFloat("inf_spacing", 0.90f),
            speed = p.getFloat("inf_speed", 0.3f),
            clockDepth = p.getInt("inf_clockdepth", 2),
            glow = p.getBoolean("inf_glow", true)
        )
    }
}

/**
 * Simula um espelho infinito: o mesmo anel de LEDs repetido para dentro,
 * cada reflexo mais pequeno e mais escuro. A inclinação desloca o ponto de fuga.
 */
object InfinityRenderer {

    private const val MAX_RINGS = 48
    private const val TILT_X = 0.38f   // deslocamento máximo do ponto de fuga (fração da largura)
    private const val TILT_Y = 0.30f

    private val path = Path()
    private val measure = PathMeasure()
    private val pos = FloatArray(2)
    private val rect = RectF()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hsv = FloatArray(3)

    fun draw(
        c: Canvas, inf: InfinityConfig, clock: ClockConfig, showClock: Boolean,
        now: Date, is24: Boolean, tiltX: Float, tiltY: Float, timeSec: Float
    ) {
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        c.drawColor(Color.rgb(4, 4, 6))

        // Do fundo para a frente, para os anéis próximos ficarem por cima
        for (i in MAX_RINGS downTo 0) {
            val s = inf.spacing.pow(i)
            val a = inf.reflect.pow(i)
            if (a < 0.012f) continue

            val depth = 1f - s
            val cx = w / 2f + tiltX * w * TILT_X * depth
            val cy = h / 2f + tiltY * h * TILT_Y * depth
            buildShape(inf.shape, cx, cy, w, h, s)

            val color = ringColor(inf, i, timeSec)
            val alpha = (a * 255f).roundToInt().coerceIn(0, 255)
            val core = max(0.8f, 3.2f * s * (w / 1080f))

            if (inf.leds != InfinityConfig.LEDS_DOTS) {
                if (inf.glow) {
                    glowPaint.color = color
                    glowPaint.alpha = (alpha * 0.22f).roundToInt()
                    glowPaint.strokeWidth = core * 5f
                    c.drawPath(path, glowPaint)
                }
                linePaint.color = color
                linePaint.alpha = alpha
                linePaint.strokeWidth = core
                c.drawPath(path, linePaint)
            }
            if (inf.leds != InfinityConfig.LEDS_LINE) {
                drawDots(c, inf, color, alpha, s, w, timeSec)
            }

            // Relógio "afundado": desenha-se depois do anel da sua profundidade,
            // e os anéis mais à frente (i menores) passam por cima dele.
            if (showClock && i == inf.clockDepth) {
                drawClock(c, inf, clock, now, is24, w, h, tiltX, tiltY)
            }
        }
    }

    private fun drawClock(
        c: Canvas, inf: InfinityConfig, clock: ClockConfig, now: Date, is24: Boolean,
        w: Float, h: Float, tiltX: Float, tiltY: Float
    ) {
        val s = inf.spacing.pow(inf.clockDepth)
        val depth = 1f - s
        val dx = tiltX * w * TILT_X * depth
        val dy = tiltY * h * TILT_Y * depth
        c.save()
        // escala à volta do centro do ecrã, como se o relógio estivesse dentro do túnel
        c.translate(w / 2f + dx, h / 2f + dy)
        c.scale(s, s)
        c.translate(-w / 2f, -h / 2f)
        DepthRenderer.drawClock(c, clock, now, is24, withDate = true)
        c.restore()
    }

    private fun drawDots(c: Canvas, inf: InfinityConfig, color: Int, alpha: Int, s: Float, w: Float, t: Float) {
        measure.setPath(path, true)
        val len = measure.length
        if (len <= 0f) return
        val gap = max(10f, 34f * (w / 1080f) * max(s, 0.35f))
        val n = max(8, (len / gap).toInt())
        val step = len / n
        val offset = (t * inf.speed * 120f * (w / 1080f)) % step
        val r = max(0.7f, 3.4f * s * (w / 1080f))
        dotPaint.color = lighten(color)
        dotPaint.alpha = alpha
        var d = offset
        for (k in 0 until n) {
            measure.getPosTan(d, pos, null)
            c.drawCircle(pos[0], pos[1], r, dotPaint)
            d += step
        }
    }

    private fun buildShape(shape: Int, cx: Float, cy: Float, w: Float, h: Float, s: Float) {
        path.reset()
        when (shape) {
            InfinityConfig.SHAPE_CIRCLE -> {
                path.addCircle(cx, cy, w * 0.44f * s, Path.Direction.CW)
            }
            InfinityConfig.SHAPE_HEX -> {
                val r = w * 0.46f * s
                for (k in 0 until 6) {
                    val ang = (PI / 3.0 * k - PI / 2.0)
                    val x = cx + (r * cos(ang)).toFloat()
                    val y = cy + (r * sin(ang)).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            else -> {
                val rw = w * 0.90f * s
                val rh = h * 0.93f * s
                rect.set(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f)
                val rad = min(rw, rh) * 0.09f
                path.addRoundRect(rect, rad, rad, Path.Direction.CW)
            }
        }
    }

    private fun ringColor(inf: InfinityConfig, i: Int, t: Float): Int {
        hsv[0] = if (inf.colorCycle) ((inf.hue + i * 9f + t * 18f) % 360f + 360f) % 360f else inf.hue
        hsv[1] = inf.saturation
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    private fun lighten(color: Int): Int {
        val r = (Color.red(color) + 255) / 2
        val g = (Color.green(color) + 255) / 2
        val b = (Color.blue(color) + 255) / 2
        return Color.rgb(r, g, b)
    }
}
