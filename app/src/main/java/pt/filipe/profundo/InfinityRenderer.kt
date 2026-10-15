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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Personalização do túnel (prefixo "inf_" nas SharedPreferences). */
data class InfinityConfig(
    val theme: Int = THEME_MIRROR,
    val shape: Int = SHAPE_RECT,
    val leds: Int = LEDS_BOTH,
    val hue: Float = 195f,
    val saturation: Float = 0.85f,
    val colorCycle: Boolean = false,
    val reflect: Float = 0.86f,      // 0.5..0.95 — quanto a luz aguenta em profundidade
    val spacing: Float = 0.90f,      // 0.84..0.95 — mais alto = anéis mais juntos
    val speed: Float = 0.3f,         // LEDs a correr
    val clockDepth: Int = 2,         // anéis de profundidade do relógio (0 = à frente)
    val glow: Boolean = true,
    val wall: Float = 0.6f,          // intensidade das paredes
    val tint: Float = 0.4f,          // tinta esverdeada do vidro em profundidade
    val motion: Float = 0f           // avançar pelo túnel
) {
    fun save(p: SharedPreferences) = p.edit()
        .putInt("inf_theme", theme).putInt("inf_shape", shape).putInt("inf_leds", leds)
        .putFloat("inf_hue", hue).putFloat("inf_sat", saturation)
        .putBoolean("inf_cycle", colorCycle)
        .putFloat("inf_reflect", reflect).putFloat("inf_spacing", spacing)
        .putFloat("inf_speed", speed).putInt("inf_clockdepth", clockDepth)
        .putBoolean("inf_glow", glow)
        .putFloat("inf_wall", wall).putFloat("inf_tint", tint).putFloat("inf_motion", motion)
        .apply()

    /** Valores de partida que fazem sentido para cada ambiente. */
    fun withThemeDefaults(t: Int): InfinityConfig = when (t) {
        THEME_BRICK -> copy(theme = t, shape = SHAPE_RECT, leds = LEDS_NONE, reflect = 0.88f, motion = 0f)
        THEME_WELL -> copy(theme = t, shape = SHAPE_CIRCLE, leds = LEDS_NONE, reflect = 0.86f, motion = 0f)
        THEME_GRID -> copy(theme = t, leds = LEDS_NONE, reflect = 0.9f, motion = 0.35f, hue = 300f, saturation = 0.8f)
        THEME_HYPER -> copy(theme = t, shape = SHAPE_CIRCLE, leds = LEDS_NONE, reflect = 0.92f, motion = 0.6f, hue = 210f, saturation = 0.35f)
        else -> copy(theme = THEME_MIRROR, leds = LEDS_BOTH, motion = 0f)
    }

    companion object {
        const val THEME_MIRROR = 0
        const val THEME_BRICK = 1
        const val THEME_WELL = 2
        const val THEME_GRID = 3
        const val THEME_HYPER = 4

        const val SHAPE_RECT = 0
        const val SHAPE_CIRCLE = 1
        const val SHAPE_HEX = 2

        const val LEDS_DOTS = 0
        const val LEDS_LINE = 1
        const val LEDS_BOTH = 2
        const val LEDS_NONE = 3

        fun load(p: SharedPreferences) = InfinityConfig(
            theme = p.getInt("inf_theme", THEME_MIRROR),
            shape = p.getInt("inf_shape", SHAPE_RECT),
            leds = p.getInt("inf_leds", LEDS_BOTH),
            hue = p.getFloat("inf_hue", 195f),
            saturation = p.getFloat("inf_sat", 0.85f),
            colorCycle = p.getBoolean("inf_cycle", false),
            reflect = p.getFloat("inf_reflect", 0.86f),
            spacing = p.getFloat("inf_spacing", 0.90f),
            speed = p.getFloat("inf_speed", 0.3f),
            clockDepth = p.getInt("inf_clockdepth", 2),
            glow = p.getBoolean("inf_glow", true),
            wall = p.getFloat("inf_wall", 0.6f),
            tint = p.getFloat("inf_tint", 0.4f),
            motion = p.getFloat("inf_motion", 0f)
        )
    }
}

/**
 * Túnel em perspetiva real: cada anel está a uma distância constante do anterior,
 * por isso o tamanho projetado é 1/(1 + z·K) — encolhem devagar e acumulam-se no centro.
 * As paredes entre anéis são desenhadas num único lote de triângulos (drawVertices).
 */
object InfinityRenderer {

    private const val SEGMENTS = 40
    private const val TILT_X = 0.38f
    private const val TILT_Y = 0.30f

    // Polígono da secção do túnel (em px, relativo ao centro, à escala 1)
    private var polyX = FloatArray(0)
    private var polyY = FloatArray(0)
    private var polyN = 0
    private var polyKey = ""

    // Anéis já projetados
    private val rp = FloatArray(SEGMENTS + 2)
    private val rcx = FloatArray(SEGMENTS + 2)
    private val rcy = FloatArray(SEGMENTS + 2)
    private val rz = FloatArray(SEGMENTS + 2)
    private val rl = FloatArray(SEGMENTS + 2)

    // Lote de triângulos
    private const val MAX_VERTS = 16000
    private val verts = FloatArray(MAX_VERTS * 2)
    private val cols = IntArray(MAX_VERTS)
    private val idx = ShortArray(MAX_VERTS / 4 * 6)
    private var nv = 0
    private var ni = 0
    private val vertPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val path = Path()
    private val measure = PathMeasure()
    private val pos = FloatArray(2)
    private val rect = RectF()
    private val hsv = FloatArray(3)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val gridLines = FloatArray(4 * 512)

    // Hiperespaço: estrelas fixas (ângulo, profundidade inicial, brilho)
    private const val STARS = 260
    private val starA = FloatArray(STARS)
    private val starD = FloatArray(STARS)
    private val starB = FloatArray(STARS)

    init {
        var seed = 12345L
        fun rnd(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) and 0xFFFFFF).toFloat() / 0xFFFFFF }
        for (k in 0 until STARS) {
            starA[k] = rnd() * 2f * PI.toFloat()
            starD[k] = rnd() * SEGMENTS * 0.7f
            starB[k] = 0.4f + rnd() * 0.6f
        }
    }

    fun draw(
        c: Canvas, inf: InfinityConfig, clock: ClockConfig, showClock: Boolean,
        now: Date, is24: Boolean, tiltX: Float, tiltY: Float, timeSec: Float
    ) {
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        c.drawColor(Color.rgb(3, 3, 5))

        val shape = when (inf.theme) {
            InfinityConfig.THEME_WELL, InfinityConfig.THEME_HYPER -> InfinityConfig.SHAPE_CIRCLE
            else -> inf.shape
        }
        ensurePoly(shape, w, h)

        // spacing 0.95 → anéis juntos (K pequeno); 0.84 → afastados
        val k = 0.08f + (0.95f - inf.spacing) / 0.11f * 0.26f
        val motion = if (inf.theme == InfinityConfig.THEME_HYPER) max(inf.motion, 0.3f) else inf.motion
        val travel = timeSec * motion * 1.6f
        val cycles = floor(travel).toInt()
        val frac = travel - cycles
        val fogExp = if (inf.theme == InfinityConfig.THEME_WELL) 1.25f else 1f

        for (i in 0..SEGMENTS + 1) {
            val z = i - frac
            val p = 1f / (1f + max(z, -0.7f) * k)
            rz[i] = z
            rp[i] = p
            rcx[i] = w / 2f + tiltX * w * TILT_X * (1f - p)
            rcy[i] = h / 2f + tiltY * h * TILT_Y * (1f - p)
            rl[i] = inf.reflect.pow(max(z, 0f) * fogExp)
        }

        when (inf.theme) {
            InfinityConfig.THEME_HYPER -> drawHyper(c, inf, w, h, k, tiltX, tiltY, travel)
            else -> drawWalls(c, inf, w, cycles)
        }

        // Anéis de LEDs + relógio afundado (do fundo para a frente)
        var clockDrawn = !showClock
        for (i in SEGMENTS downTo 0) {
            if (!clockDrawn && rz[i] <= inf.clockDepth) {
                drawClock(c, inf, clock, now, is24, w, h, k, tiltX, tiltY)
                clockDrawn = true
            }
            if (inf.leds == InfinityConfig.LEDS_NONE) continue
            val a = rl[i]
            if (a < 0.012f) continue
            ringPath(shape, rcx[i], rcy[i], w, h, rp[i])
            val color = ringColor(inf, i + cycles, rz[i], timeSec)
            val alpha = (a * 255f).roundToInt().coerceIn(0, 255)
            val core = max(0.8f, 3.2f * rp[i] * (w / 1080f))
            if (inf.leds != InfinityConfig.LEDS_DOTS) {
                if (inf.glow) {
                    glowPaint.color = color; glowPaint.alpha = (alpha * 0.22f).roundToInt()
                    glowPaint.strokeWidth = core * 5f
                    c.drawPath(path, glowPaint)
                }
                linePaint.color = color; linePaint.alpha = alpha; linePaint.strokeWidth = core
                c.drawPath(path, linePaint)
            }
            if (inf.leds != InfinityConfig.LEDS_LINE) {
                drawDots(c, inf, color, alpha, rp[i], rz[i], w, timeSec)
            }
        }
        if (!clockDrawn) drawClock(c, inf, clock, now, is24, w, h, k, tiltX, tiltY)
    }

    // ---------------- Paredes ----------------

    private fun drawWalls(c: Canvas, inf: InfinityConfig, w: Float, cycles: Int) {
        nv = 0; ni = 0
        val theme = inf.theme
        val gridOn = theme == InfinityConfig.THEME_GRID
        var nLines = 0

        for (i in SEGMENTS downTo 0) {
            val ln = rl[i]
            val lf = rl[i + 1]
            if (ln < 0.008f) continue
            val g = i + cycles   // id do segmento que viaja com o movimento (a textura não "escorrega")

            for (j in 0 until polyN) {
                val j1 = (j + 1) % polyN
                val n0x = rcx[i] + polyX[j] * rp[i];      val n0y = rcy[i] + polyY[j] * rp[i]
                val n1x = rcx[i] + polyX[j1] * rp[i];     val n1y = rcy[i] + polyY[j1] * rp[i]
                val f0x = rcx[i + 1] + polyX[j] * rp[i + 1];  val f0y = rcy[i + 1] + polyY[j] * rp[i + 1]
                val f1x = rcx[i + 1] + polyX[j1] * rp[i + 1]; val f1y = rcy[i + 1] + polyY[j1] * rp[i + 1]

                // Luz vinda de cima: parede de cima clara, de baixo escura
                val mx = (polyX[j] + polyX[j1]) / 2f
                val my = (polyY[j] + polyY[j1]) / 2f
                val ang = atan2(my, mx)
                val shade = 0.62f - 0.38f * sin(ang)   // sin < 0 em cima (y para baixo)

                val base = wallBase(inf, theme, i + cycles, rz[i])
                // Fundo da parede (juntas / argamassa)
                val mortar = if (theme == InfinityConfig.THEME_BRICK || theme == InfinityConfig.THEME_WELL)
                    scale(base, 0.28f) else base
                quad(
                    n0x, n0y, n1x, n1y, f1x, f1y, f0x, f0y,
                    scale(mortar, shade * ln), scale(mortar, shade * lf)
                )

                when (theme) {
                    InfinityConfig.THEME_BRICK ->
                        blocks(inf, w, j, g, n0x, n0y, n1x, n1y, f0x, f0y, f1x, f1y, ln, lf, shade, base,
                            rows = 2, blockW = w * 0.12f, inset = 0.05f, variation = 0.14f, mossStart = 99f, z = rz[i])
                    InfinityConfig.THEME_WELL ->
                        blocks(inf, w, j, g, n0x, n0y, n1x, n1y, f0x, f0y, f1x, f1y, ln, lf, shade, base,
                            rows = 2, blockW = w * 0.15f, inset = 0.07f, variation = 0.24f, mossStart = 4f, z = rz[i])
                    InfinityConfig.THEME_GRID -> if (nLines + 4 <= gridLines.size / 4) {
                        // linha transversal (aresta do anel) + 3 longitudinais
                        nLines = addLine(nLines, n0x, n0y, n1x, n1y)
                        for (s in 0 until 3) {
                            val u = s / 3f
                            nLines = addLine(nLines, lerp(n0x, n1x, u), lerp(n0y, n1y, u), lerp(f0x, f1x, u), lerp(f0y, f1y, u))
                        }
                    }
                }
                if (nv > MAX_VERTS - 64) flush(c)
            }
            // A grelha é desenhada segmento a segmento para cada um ter a sua intensidade
            if (gridOn && nLines > 0) {
                flush(c)
                drawGrid(c, inf, w, nLines, ln, i + cycles, rz[i])
                nLines = 0
            }
        }
        flush(c)
    }

    /** Tijolos ou pedras: sub-quadriláteros com juntas, desencontrados linha sim linha não. */
    private fun blocks(
        inf: InfinityConfig, w: Float, j: Int, g: Int,
        n0x: Float, n0y: Float, n1x: Float, n1y: Float,
        f0x: Float, f0y: Float, f1x: Float, f1y: Float,
        ln: Float, lf: Float, shade: Float, base: Int,
        rows: Int, blockW: Float, inset: Float, variation: Float, mossStart: Float, z: Float
    ) {
        val sideLen = hypot(polyX[(j + 1) % polyN] - polyX[j], polyY[(j + 1) % polyN] - polyY[j])
        val m = max(1, (sideLen / blockW).roundToInt())
        val du = inset / m
        val dt = inset * 1.4f
        for (r in 0 until rows) {
            val t0 = r / rows.toFloat()
            val t1 = (r + 1) / rows.toFloat()
            val odd = ((g * rows + r) and 1) == 1
            val count = if (odd) m + 1 else m
            for (b in 0 until count) {
                var ua = if (odd) (b - 0.5f) / m else b / m.toFloat()
                var ub = ua + 1f / m
                ua = ua.coerceIn(0f, 1f); ub = ub.coerceIn(0f, 1f)
                if (ub - ua < 0.02f) continue
                val a0 = ua + du; val a1 = ub - du
                val s0 = t0 + dt; val s1 = t1 - dt
                val v = hash(g, j, r, b)
                var col = scale(base, 1f + variation * v)
                if (z > mossStart) {
                    val moss = min(1f, (z - mossStart) / 10f) * (0.5f + 0.5f * hash(b, r, j, g + 7))
                    col = mix(col, Color.rgb(64, 92, 52), moss * 0.7f)
                }
                val l0 = lerp(ln, lf, s0) * shade
                val l1 = lerp(ln, lf, s1) * shade
                quad(
                    bx(n0x, n1x, f0x, f1x, a0, s0), by(n0y, n1y, f0y, f1y, a0, s0),
                    bx(n0x, n1x, f0x, f1x, a1, s0), by(n0y, n1y, f0y, f1y, a1, s0),
                    bx(n0x, n1x, f0x, f1x, a1, s1), by(n0y, n1y, f0y, f1y, a1, s1),
                    bx(n0x, n1x, f0x, f1x, a0, s1), by(n0y, n1y, f0y, f1y, a0, s1),
                    scale(col, l0), scale(col, l1)
                )
                if (nv > MAX_VERTS - 8) return
            }
        }
    }

    private fun wallBase(inf: InfinityConfig, theme: Int, seg: Int, z: Float): Int = when (theme) {
        InfinityConfig.THEME_BRICK -> Color.rgb(156, 74, 48)
        InfinityConfig.THEME_WELL -> Color.rgb(128, 124, 114)
        InfinityConfig.THEME_GRID -> scale(ringColor(inf, seg, z, 0f), 0.06f)
        else -> scale(ringColor(inf, seg, z, 0f), 0.10f + 0.30f * inf.wall)
    }

    private fun drawGrid(c: Canvas, inf: InfinityConfig, w: Float, nLines: Int, light: Float, seg: Int, z: Float) {
        val color = ringColor(inf, seg, z, 0f)
        val a = (light * 255f).roundToInt().coerceIn(0, 255)
        val width = max(0.8f, 2.4f * (w / 1080f) * (0.4f + 0.6f * light))
        if (inf.glow) {
            gridPaint.color = color; gridPaint.alpha = (a * 0.2f).roundToInt(); gridPaint.strokeWidth = width * 4f
            c.drawLines(gridLines, 0, nLines * 4, gridPaint)
        }
        gridPaint.color = color; gridPaint.alpha = a; gridPaint.strokeWidth = width
        c.drawLines(gridLines, 0, nLines * 4, gridPaint)
    }

    private fun addLine(n: Int, x0: Float, y0: Float, x1: Float, y1: Float): Int {
        val o = n * 4
        gridLines[o] = x0; gridLines[o + 1] = y0; gridLines[o + 2] = x1; gridLines[o + 3] = y1
        return n + 1
    }

    // ---------------- Hiperespaço ----------------

    private fun drawHyper(c: Canvas, inf: InfinityConfig, w: Float, h: Float, k: Float, tx: Float, ty: Float, travel: Float) {
        val dMax = SEGMENTS * 0.7f
        val streak = 0.25f + inf.motion * 1.2f
        val rx = w * 0.62f
        val ry = h * 0.62f
        val color = ringColor(inf, 0, 0f, 0f)
        for (s in 0 until STARS) {
            var z = (starD[s] - travel * 2.2f) % dMax
            if (z < 0f) z += dMax
            val z2 = z + streak
            val p1 = 1f / (1f + z * k); val p2 = 1f / (1f + z2 * k)
            val c1x = w / 2f + tx * w * TILT_X * (1f - p1); val c1y = h / 2f + ty * h * TILT_Y * (1f - p1)
            val c2x = w / 2f + tx * w * TILT_X * (1f - p2); val c2y = h / 2f + ty * h * TILT_Y * (1f - p2)
            val ca = cos(starA[s]); val sa = sin(starA[s])
            val light = inf.reflect.pow(z) * starB[s]
            if (light < 0.02f) continue
            gridPaint.color = mix(Color.WHITE, color, 0.5f)
            gridPaint.alpha = (light * 255f).roundToInt().coerceIn(0, 255)
            gridPaint.strokeWidth = max(0.8f, 3f * p1 * (w / 1080f))
            c.drawLine(c1x + ca * rx * p1, c1y + sa * ry * p1, c2x + ca * rx * p2, c2y + sa * ry * p2, gridPaint)
        }
    }

    // ---------------- Relógio ----------------

    private fun drawClock(
        c: Canvas, inf: InfinityConfig, clock: ClockConfig, now: Date, is24: Boolean,
        w: Float, h: Float, k: Float, tiltX: Float, tiltY: Float
    ) {
        val p = 1f / (1f + inf.clockDepth * k)
        val dx = tiltX * w * TILT_X * (1f - p)
        val dy = tiltY * h * TILT_Y * (1f - p)
        c.save()
        c.translate(w / 2f + dx, h / 2f + dy)
        c.scale(p, p)
        c.translate(-w / 2f, -h / 2f)
        DepthRenderer.drawClock(c, clock, now, is24, withDate = true)
        c.restore()
    }

    // ---------------- LEDs ----------------

    private fun drawDots(c: Canvas, inf: InfinityConfig, color: Int, alpha: Int, s: Float, z: Float, w: Float, t: Float) {
        measure.setPath(path, true)
        val len = measure.length
        if (len <= 0f) return
        val gap = max(10f, 34f * (w / 1080f) * max(s, 0.35f))
        val n = max(8, (len / gap).toInt())
        val step = len / n
        val offset = (t * inf.speed * 120f * (w / 1080f)) % step
        // No fundo os LEDs ficam mais difusos (vidro)
        val blur = 1f + inf.tint * min(1f, max(z, 0f) / 14f) * 0.8f
        val r = max(0.7f, 3.4f * s * (w / 1080f) * blur)
        dotPaint.color = mix(color, Color.WHITE, 0.5f)
        dotPaint.alpha = alpha
        var d = offset
        for (q in 0 until n) {
            measure.getPosTan(d, pos, null)
            c.drawCircle(pos[0], pos[1], r, dotPaint)
            d += step
        }
    }

    private fun ringPath(shape: Int, cx: Float, cy: Float, w: Float, h: Float, s: Float) {
        path.reset()
        when (shape) {
            InfinityConfig.SHAPE_CIRCLE -> path.addCircle(cx, cy, w * 0.44f * s, Path.Direction.CW)
            InfinityConfig.SHAPE_HEX -> {
                for (q in 0 until polyN) {
                    val x = cx + polyX[q] * s; val y = cy + polyY[q] * s
                    if (q == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            else -> {
                val rw = w * 0.90f * s; val rh = h * 0.93f * s
                rect.set(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f)
                val rad = min(rw, rh) * 0.06f
                path.addRoundRect(rect, rad, rad, Path.Direction.CW)
            }
        }
    }

    private fun ensurePoly(shape: Int, w: Float, h: Float) {
        val key = "$shape-$w-$h"
        if (key == polyKey) return
        polyKey = key
        when (shape) {
            InfinityConfig.SHAPE_CIRCLE -> {
                polyN = 28
                polyX = FloatArray(polyN) { cos(2.0 * PI * it / polyN - PI / 2).toFloat() * w * 0.44f }
                polyY = FloatArray(polyN) { sin(2.0 * PI * it / polyN - PI / 2).toFloat() * w * 0.44f }
            }
            InfinityConfig.SHAPE_HEX -> {
                polyN = 6
                polyX = FloatArray(6) { cos(PI / 3 * it - PI / 2).toFloat() * w * 0.46f }
                polyY = FloatArray(6) { sin(PI / 3 * it - PI / 2).toFloat() * w * 0.46f }
            }
            else -> {
                polyN = 4
                val hw = w * 0.45f; val hh = h * 0.465f
                polyX = floatArrayOf(-hw, hw, hw, -hw)
                polyY = floatArrayOf(-hh, -hh, hh, hh)
            }
        }
    }

    // ---------------- Cor ----------------

    private fun ringColor(inf: InfinityConfig, seg: Int, z: Float, t: Float): Int {
        var hue = if (inf.colorCycle) inf.hue + seg * 9f + t * 18f else inf.hue
        var sat = inf.saturation
        // Tinta do vidro: no fundo puxa para verde e perde saturação
        if (inf.theme == InfinityConfig.THEME_MIRROR && inf.tint > 0f) {
            val g = inf.tint * min(1f, max(z, 0f) / 14f)
            hue = lerp(hue, 150f, g * 0.5f)
            sat *= (1f - 0.4f * g)
        }
        hsv[0] = ((hue % 360f) + 360f) % 360f
        hsv[1] = sat
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    // ---------------- Lote de triângulos ----------------

    private fun quad(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        cNear: Int, cFar: Int
    ) {
        if (nv + 4 > MAX_VERTS) return
        val b = nv
        val o = nv * 2
        verts[o] = x0; verts[o + 1] = y0
        verts[o + 2] = x1; verts[o + 3] = y1
        verts[o + 4] = x2; verts[o + 5] = y2
        verts[o + 6] = x3; verts[o + 7] = y3
        cols[b] = cNear; cols[b + 1] = cNear; cols[b + 2] = cFar; cols[b + 3] = cFar
        idx[ni] = b.toShort(); idx[ni + 1] = (b + 1).toShort(); idx[ni + 2] = (b + 2).toShort()
        idx[ni + 3] = b.toShort(); idx[ni + 4] = (b + 2).toShort(); idx[ni + 5] = (b + 3).toShort()
        nv += 4; ni += 6
    }

    private fun flush(c: Canvas) {
        if (ni > 0) {
            c.drawVertices(
                Canvas.VertexMode.TRIANGLES, nv * 2, verts, 0, null, 0, cols, 0, idx, 0, ni, vertPaint
            )
        }
        nv = 0; ni = 0
    }

    // ---------------- Utilitários ----------------

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun bx(n0: Float, n1: Float, f0: Float, f1: Float, u: Float, t: Float) = lerp(lerp(n0, n1, u), lerp(f0, f1, u), t)
    private fun by(n0: Float, n1: Float, f0: Float, f1: Float, u: Float, t: Float) = lerp(lerp(n0, n1, u), lerp(f0, f1, u), t)

    private fun scale(color: Int, k: Float): Int {
        val kk = max(0f, k)
        return Color.rgb(
            (Color.red(color) * kk).roundToInt().coerceIn(0, 255),
            (Color.green(color) * kk).roundToInt().coerceIn(0, 255),
            (Color.blue(color) * kk).roundToInt().coerceIn(0, 255)
        )
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), t).roundToInt().coerceIn(0, 255),
        lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), t).roundToInt().coerceIn(0, 255),
        lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), t).roundToInt().coerceIn(0, 255)
    )

    /** Pseudo-aleatório estável em [-1, 1] (a mesma pedra tem sempre a mesma cor). */
    private fun hash(a: Int, b: Int, c: Int, d: Int): Float {
        var x = a * 374761393 + b * 668265263 + c * 1274126177 + d * 1442695041
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 32767.5f - 1f
    }
}
