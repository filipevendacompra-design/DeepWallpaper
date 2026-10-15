package pt.filipe.profundo

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Definições do relógio, guardadas em SharedPreferences. */
data class ClockConfig(
    val y: Float,          // centro vertical do relógio, fração da altura (0..1)
    val size: Float,       // tamanho do texto, fração da largura
    val color: Int,
    val showOnHome: Boolean
) {
    fun save(p: SharedPreferences) = p.edit()
        .putFloat("y", y).putFloat("size", size)
        .putInt("color", color).putBoolean("home", showOnHome)
        .apply()

    companion object {
        fun load(p: SharedPreferences) = ClockConfig(
            y = p.getFloat("y", 0.22f),
            size = p.getFloat("size", 0.30f),
            color = p.getInt("color", Color.WHITE),
            showOnHome = p.getBoolean("home", false)
        )
    }
}

object Store {
    const val PREFS = "profundo"
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun bgFile(c: Context) = File(c.filesDir, "bg.jpg")
    fun fgFile(c: Context) = File(c.filesDir, "fg.png")
}

/** Desenha as três camadas: fundo → relógio → sujeito recortado. */
object DepthRenderer {

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clockTypeface: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    private val dateLocale = Locale("pt", "PT")

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
        showClock: Boolean, now: Date, is24: Boolean
    ) {
        val w = c.width
        val h = c.height
        val dst = Rect(0, 0, w, h)
        if (bg != null) {
            c.drawBitmap(bg, srcRectFor(bg.width, bg.height, w, h), dst, bmpPaint)
        } else {
            c.drawColor(Color.BLACK)
        }
        if (showClock) drawClock(c, cfg, now, is24, withDate = true)
        if (fg != null) {
            c.drawBitmap(fg, srcRectFor(fg.width, fg.height, w, h), dst, bmpPaint)
        }
    }

    fun drawClock(c: Canvas, cfg: ClockConfig, now: Date, is24: Boolean, withDate: Boolean) {
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        val textSize = cfg.size * w
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = clockTypeface
            color = cfg.color
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
        }
        val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        val baseline = cfg.y * h + textSize * 0.36f
        c.drawText(time, w / 2f, baseline, p)

        if (withDate) {
            p.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            p.textSize = textSize * 0.17f
            val date = SimpleDateFormat("EEEE, d 'de' MMMM", dateLocale).format(now)
                .replaceFirstChar { it.uppercase() }
            c.drawText(date, w / 2f, baseline - textSize * 0.82f, p)
        }
    }

    /** Sujeito redimensionado para um ecrã w×h (usado para medir cobertura). */
    fun fgForScreen(fg: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(fg, srcRectFor(fg.width, fg.height, w, h), Rect(0, 0, w, h), bmpPaint)
        return out
    }

    /** Fração dos píxeis dos dígitos que ficam tapados pelo sujeito (0..1). */
    fun coverage(fgScreen: Bitmap, cfg: ClockConfig, now: Date, is24: Boolean): Float {
        val w = fgScreen.width
        val h = fgScreen.height
        val clk = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawClock(Canvas(clk), cfg.copy(color = Color.BLACK), now, is24, withDate = false)
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

    /**
     * Procura a posição vertical em que o sujeito tapa ~25% dos dígitos.
     * Devolve null se o sujeito nunca toca no relógio na zona de cima do ecrã.
     */
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
