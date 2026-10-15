package pt.filipe.profundo

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Definições do relógio e do efeito, guardadas em SharedPreferences. */
data class ClockConfig(
    val y: Float = 0.22f,          // centro vertical do relógio, fração da altura
    val size: Float = 0.30f,       // tamanho, fração da largura
    val color: Int = Color.WHITE,
    val showOnHome: Boolean = false,
    val parallax: Boolean = true,
    val intensity: Float = 1f,     // 0.25..4
    // Relógio
    val style: Int = STYLE_HORIZONTAL,
    val font: Int = FONT_MODERN,
    val outline: Boolean = false,
    val shadow: Boolean = true,
    val opacity: Float = 1f,       // 0.3..1
    val dateMode: Int = DATE_ABOVE,
    val front: Boolean = false,    // relógio à frente do sujeito
    // Efeito 3D
    val focus: Int = FOCUS_CLOCK,  // que plano fica parado
    val smooth: Float = 0.4f,      // suavidade das bordas 0..1
    val invert: Boolean = false,
    val autoMotion: Boolean = false,
    val separation: Float = 0.5f   // 0..1 — exagera a diferença entre planos próximos
) {
    fun save(p: SharedPreferences) = p.edit()
        .putFloat("y", y).putFloat("size", size)
        .putInt("color", color).putBoolean("home", showOnHome)
        .putBoolean("parallax", parallax).putFloat("intensity", intensity)
        .putInt("style", style).putInt("font", font)
        .putBoolean("outline", outline).putBoolean("shadow", shadow)
        .putFloat("opacity", opacity).putInt("date", dateMode).putBoolean("front", front)
        .putInt("focus", focus).putFloat("smooth", smooth)
        .putBoolean("invert", invert).putBoolean("auto", autoMotion)
        .putFloat("sep", separation)
        .apply()

    companion object {
        const val STYLE_HORIZONTAL = 0
        const val STYLE_VERTICAL = 1
        const val STYLE_ANALOG = 2

        const val FONT_MODERN = 0
        const val FONT_THIN = 1
        const val FONT_CONDENSED = 2
        const val FONT_CLASSIC = 3
        const val FONT_MONO = 4

        const val DATE_ABOVE = 0
        const val DATE_BELOW = 1
        const val DATE_HIDDEN = 2

        const val FOCUS_CLOCK = 0
        const val FOCUS_SUBJECT = 1
        const val FOCUS_BACKGROUND = 2

        fun load(p: SharedPreferences) = ClockConfig(
            y = p.getFloat("y", 0.22f),
            size = p.getFloat("size", 0.30f),
            color = p.getInt("color", Color.WHITE),
            showOnHome = p.getBoolean("home", false),
            parallax = p.getBoolean("parallax", true),
            intensity = p.getFloat("intensity", 1f),
            style = p.getInt("style", STYLE_HORIZONTAL),
            font = p.getInt("font", FONT_MODERN),
            outline = p.getBoolean("outline", false),
            shadow = p.getBoolean("shadow", true),
            opacity = p.getFloat("opacity", 1f),
            dateMode = p.getInt("date", DATE_ABOVE),
            front = p.getBoolean("front", false),
            focus = p.getInt("focus", FOCUS_CLOCK),
            smooth = p.getFloat("smooth", 0.4f),
            invert = p.getBoolean("invert", false),
            autoMotion = p.getBoolean("auto", false),
            separation = p.getFloat("sep", 0.5f)
        )
    }
}

object Store {
    const val PREFS = "profundo"
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun bgFile(c: Context) = File(c.filesDir, "bg.jpg")
    fun fgFile(c: Context) = File(c.filesDir, "fg.png")
    fun bgFillFile(c: Context) = File(c.filesDir, "bg_fill.jpg")   // fundo sem o sujeito (plano B sem profundidade)
    fun depthFile(c: Context) = File(c.filesDir, "depth.png")       // mapa de profundidade (malha 3D)
    fun videoDir(c: Context) = File(c.filesDir, "video")
    fun videoNewDir(c: Context) = File(c.filesDir, "video_new")
    const val MODE_PHOTO = "photo"
    const val MODE_VIDEO = "video"
    fun mode(p: SharedPreferences): String = p.getString("mode", MODE_PHOTO) ?: MODE_PHOTO
}

/** Desenha fundo → relógio → sujeito (ou relógio por cima, se pedido). */
object DepthRenderer {

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val dateLocale = Locale("pt", "PT")
    private val fonts = arrayOf(
        Typeface.create(Typeface.DEFAULT, 700, false),
        Typeface.create(Typeface.DEFAULT, 200, false),
        Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 700, false),
        Typeface.create(Typeface.SERIF, 500, false),
        Typeface.create(Typeface.MONOSPACE, 500, false)
    )

    // Paralaxe sem profundidade (vídeo antigo / falha do modelo)
    private const val LAYER_BG = 0.3f
    private const val LAYER_CLOCK = 0.6f
    private const val LAYER_FG = 1.0f
    private const val CLOCK_FACTOR = 0.4f
    private const val PARALLAX_ZOOM = 0.06f
    private const val IMAGE_AMPLITUDE = 0.025f

    fun zoomFor(cfg: ClockConfig) = if (cfg.parallax) 1f + PARALLAX_ZOOM * cfg.intensity else 1f

    /** Recorte central da imagem para preencher o ecrã (center-crop). */
    fun srcRectFor(bw: Int, bh: Int, dw: Int, dh: Int): Rect {
        val scale = max(dw.toFloat() / bw, dh.toFloat() / bh)
        val cw = (dw / scale).toInt().coerceAtMost(bw)
        val ch = (dh / scale).toInt().coerceAtMost(bh)
        val l = (bw - cw) / 2
        val t = (bh - ch) / 2
        return Rect(l, t, l + cw, t + ch)
    }

    fun draw(
        c: Canvas, bg: Bitmap?, fg: Bitmap?, cfg: ClockConfig,
        showClock: Boolean, now: Date, is24: Boolean,
        tiltX: Float = 0f, tiltY: Float = 0f,
        mask: Bitmap? = null,     // vídeo: sujeito = frame cortado por esta máscara (ALPHA_8)
        bgFill: Bitmap? = null,   // fundo sem o sujeito → 3 camadas
        depth: DepthMesh? = null  // profundidade → malha 3D (tem prioridade)
    ) {
        val w = c.width
        val h = c.height
        val zoom = zoomFor(cfg)
        val sign = if (cfg.invert) -1f else 1f
        val amp = if (cfg.parallax) IMAGE_AMPLITUDE * cfg.intensity * w * sign else 0f
        val hasSubject = fg != null || mask != null

        // ---- Malha 3D ----
        if (depth != null && cfg.parallax && bg != null && hasSubject) {
            depth.update(bg.width, bg.height, w, h, zoom, tiltX * amp, tiltY * amp, cfg)
            c.drawColor(Color.BLACK)
            c.drawBitmapMesh(bg, DepthMesh.MESH_W, DepthMesh.MESH_H, depth.verts, 0, null, 0, bmpPaint)
            if (showClock && !cfg.front) drawClock(c, cfg, now, is24, withDate = true)
            if (mask != null) {
                val layer = c.saveLayer(null, null)
                c.drawBitmapMesh(bg, DepthMesh.MESH_W, DepthMesh.MESH_H, depth.verts, 0, null, 0, bmpPaint)
                c.drawBitmapMesh(mask, DepthMesh.MESH_W, DepthMesh.MESH_H, depth.verts, 0, null, 0, maskPaint)
                c.restoreToCount(layer)
            } else if (fg != null) {
                c.drawBitmapMesh(fg, DepthMesh.MESH_W, DepthMesh.MESH_H, depth.verts, 0, null, 0, bmpPaint)
            }
            if (showClock && cfg.front) drawClock(c, cfg, now, is24, withDate = true)
            return
        }

        // ---- Sem profundidade: camadas planas ----
        val dx = (tiltX * amp).toInt()
        val dy = (tiltY * amp).toInt()
        val ox = (w * (zoom - 1f) / 2f).toInt()
        val oy = (h * (zoom - 1f) / 2f).toInt()
        val dst = Rect(-ox + dx, -oy + dy, w + ox + dx, h + oy + dy)
        val layered = cfg.parallax && bgFill != null && fg != null && mask == null
        val back = if (layered) bgFill else bg
        val bgK = if (layered) LAYER_BG else 1f
        val clockK = if (layered) LAYER_CLOCK else CLOCK_FACTOR
        val bdx = (dx * bgK).toInt(); val bdy = (dy * bgK).toInt()
        val dstBack = Rect(-ox + bdx, -oy + bdy, w + ox + bdx, h + oy + bdy)
        val fk = if (layered) LAYER_FG else 1f
        val fdx = (dx * fk).toInt(); val fdy = (dy * fk).toInt()
        val dstFg = Rect(-ox + fdx, -oy + fdy, w + ox + fdx, h + oy + fdy)

        if (back != null) c.drawBitmap(back, srcRectFor(back.width, back.height, w, h), dstBack, bmpPaint)
        else c.drawColor(Color.BLACK)

        fun clock() {
            c.save(); c.translate(dx * clockK, dy * clockK)
            drawClock(c, cfg, now, is24, withDate = true)
            c.restore()
        }
        if (showClock && !cfg.front) clock()
        if (mask != null && bg != null) {
            val layer = c.saveLayer(null, null)
            c.drawBitmap(bg, srcRectFor(bg.width, bg.height, w, h), dst, bmpPaint)
            c.drawBitmap(mask, srcRectFor(mask.width, mask.height, w, h), dst, maskPaint)
            c.restoreToCount(layer)
        } else if (fg != null) {
            c.drawBitmap(fg, srcRectFor(fg.width, fg.height, w, h), dstFg, bmpPaint)
        }
        if (showClock && cfg.front) clock()
    }

    // ---------------- Relógio ----------------

    fun drawClock(c: Canvas, cfg: ClockConfig, now: Date, is24: Boolean, withDate: Boolean) {
        when (cfg.style) {
            ClockConfig.STYLE_ANALOG -> drawAnalog(c, cfg, now, withDate)
            ClockConfig.STYLE_VERTICAL -> drawVertical(c, cfg, now, is24, withDate)
            else -> drawHorizontal(c, cfg, now, is24, withDate)
        }
    }

    private fun basePaint(cfg: ClockConfig, textSize: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fonts[cfg.font.coerceIn(0, fonts.size - 1)]
        color = cfg.color
        alpha = (cfg.opacity.coerceIn(0.1f, 1f) * 255).roundToInt()
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        if (cfg.outline) { style = Paint.Style.STROKE; strokeWidth = max(2f, textSize * 0.03f) }
        if (cfg.shadow && Color.alpha(cfg.color) > 0) setShadowLayer(textSize * 0.06f, 0f, textSize * 0.02f, Color.argb(110, 0, 0, 0))
    }

    private fun datePaint(cfg: ClockConfig, textSize: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        color = cfg.color
        alpha = (cfg.opacity.coerceIn(0.1f, 1f) * 255).roundToInt()
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        if (cfg.shadow) setShadowLayer(textSize * 0.15f, 0f, textSize * 0.05f, Color.argb(110, 0, 0, 0))
    }

    private fun dateText(now: Date) =
        SimpleDateFormat("EEEE, d 'de' MMMM", dateLocale).format(now).replaceFirstChar { it.uppercase() }

    private fun drawHorizontal(c: Canvas, cfg: ClockConfig, now: Date, is24: Boolean, withDate: Boolean) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        val ts = cfg.size * w
        val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        val baseline = cfg.y * h + ts * 0.36f
        c.drawText(time, w / 2f, baseline, basePaint(cfg, ts))
        if (withDate && cfg.dateMode != ClockConfig.DATE_HIDDEN) {
            val dy = if (cfg.dateMode == ClockConfig.DATE_ABOVE) baseline - ts * 0.82f else baseline + ts * 0.30f
            c.drawText(dateText(now), w / 2f, dy, datePaint(cfg, ts * 0.17f))
        }
    }

    /** Horas por cima dos minutos, grandes — estilo ecrã de bloqueio do iPhone. */
    private fun drawVertical(c: Canvas, cfg: ClockConfig, now: Date, is24: Boolean, withDate: Boolean) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        val ts = cfg.size * w * 1.35f
        val cal = Calendar.getInstance().apply { time = now }
        val hh = if (is24) "%02d".format(cal.get(Calendar.HOUR_OF_DAY))
                 else "%d".format(((cal.get(Calendar.HOUR) + 11) % 12) + 1)
        val mm = "%02d".format(cal.get(Calendar.MINUTE))
        val p = basePaint(cfg, ts)
        val gap = ts * 0.86f
        val center = cfg.y * h
        val b1 = center - gap / 2f + ts * 0.36f - ts * 0.08f
        val b2 = b1 + gap
        c.drawText(hh, w / 2f, b1, p)
        c.drawText(mm, w / 2f, b2, p)
        if (withDate && cfg.dateMode != ClockConfig.DATE_HIDDEN) {
            val dy = if (cfg.dateMode == ClockConfig.DATE_ABOVE) b1 - ts * 0.82f else b2 + ts * 0.28f
            c.drawText(dateText(now), w / 2f, dy, datePaint(cfg, cfg.size * w * 0.17f))
        }
    }

    private fun drawAnalog(c: Canvas, cfg: ClockConfig, now: Date, withDate: Boolean) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        val r = cfg.size * w * 1.15f
        val cx = w / 2f; val cy = cfg.y * h + r * 0.35f
        val alpha = (cfg.opacity.coerceIn(0.1f, 1f) * 255).roundToInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cfg.color; this.alpha = alpha
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            if (cfg.shadow) setShadowLayer(r * 0.03f, 0f, r * 0.01f, Color.argb(110, 0, 0, 0))
        }
        // Mostrador
        p.strokeWidth = r * (if (cfg.outline) 0.02f else 0.035f)
        c.drawCircle(cx, cy, r, p)
        for (k in 0 until 12) {
            val a = (k / 12.0 * 2 * PI).toFloat()
            val long = k % 3 == 0
            val r0 = r * (if (long) 0.78f else 0.86f); val r1 = r * 0.93f
            p.strokeWidth = r * (if (long) 0.05f else 0.025f)
            c.drawLine(cx + sin(a) * r0, cy - cos(a) * r0, cx + sin(a) * r1, cy - cos(a) * r1, p)
        }
        val cal = Calendar.getInstance().apply { time = now }
        val min = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f
        val ah = (hr / 12f * 2 * PI).toFloat()
        val am = (min / 60f * 2 * PI).toFloat()
        p.strokeWidth = r * 0.075f
        c.drawLine(cx, cy, cx + sin(ah) * r * 0.5f, cy - cos(ah) * r * 0.5f, p)
        p.strokeWidth = r * 0.05f
        c.drawLine(cx, cy, cx + sin(am) * r * 0.78f, cy - cos(am) * r * 0.78f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r * 0.07f, p)
        if (withDate && cfg.dateMode != ClockConfig.DATE_HIDDEN) {
            val dy = if (cfg.dateMode == ClockConfig.DATE_ABOVE) cy - r * 1.18f else cy + r * 1.32f
            c.drawText(dateText(now), cx, dy, datePaint(cfg, cfg.size * w * 0.17f))
        }
    }

    // ---------------- Cobertura do relógio ----------------

    /** Sujeito redimensionado para um ecrã w×h (usado para medir cobertura). */
    fun fgForScreen(fg: Bitmap, w: Int, h: Int, zoom: Float = 1f): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ox = (w * (zoom - 1f) / 2f).toInt()
        val oy = (h * (zoom - 1f) / 2f).toInt()
        Canvas(out).drawBitmap(fg, srcRectFor(fg.width, fg.height, w, h), Rect(-ox, -oy, w + ox, h + oy), bmpPaint)
        return out
    }

    /** Fração dos píxeis do relógio tapados pelo sujeito (0..1). */
    fun coverage(fgScreen: Bitmap, cfg: ClockConfig, now: Date, is24: Boolean): Float {
        val w = fgScreen.width
        val h = fgScreen.height
        val clk = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawClock(Canvas(clk), cfg.copy(color = Color.BLACK, opacity = 1f, shadow = false, outline = false), now, is24, withDate = false)
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        clk.getPixels(a, 0, w, 0, 0, w, h)
        fgScreen.getPixels(b, 0, w, 0, 0, w, h)
        clk.recycle()
        var text = 0
        var hidden = 0
        for (i in a.indices) {
            if ((a[i] ushr 24) > 128) {
                text++
                if ((b[i] ushr 24) > 128) hidden++
            }
        }
        return if (text == 0) 0f else hidden.toFloat() / text
    }

    /** Posição vertical em que o sujeito tapa ~25% do relógio; null se nunca lhe toca. */
    fun autoY(fgScreen: Bitmap, cfg: ClockConfig, now: Date, is24: Boolean, target: Float = 0.25f): Float? {
        var bestY: Float? = null
        var bestErr = Float.MAX_VALUE
        var y = 0.12f
        while (y <= 0.50f) {
            val cov = coverage(fgScreen, cfg.copy(y = y), now, is24)
            if (cov > 0.05f && cov < 0.45f) {
                val err = abs(cov - target)
                if (err < bestErr) { bestErr = err; bestY = y }
            }
            y += 0.01f
        }
        return bestY
    }
}
