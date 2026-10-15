package pt.filipe.profundo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cria uma versão do fundo SEM o sujeito: a zona do sujeito é preenchida com as cores
 * à volta (push-pull: média em pirâmide, do grosso para o fino).
 * É o que permite mexer o sujeito e o fundo a velocidades diferentes sem aparecer o "fantasma".
 */
object BackgroundFill {

    private const val WORK_SIDE = 640   // resolução de trabalho
    private const val DILATE = 4        // alarga a zona a preencher (px à resolução de trabalho)

    fun fill(bg: Bitmap, fg: Bitmap): Bitmap {
        val s = min(1f, WORK_SIDE.toFloat() / max(bg.width, bg.height))
        val w = max(1, (bg.width * s).roundToInt())
        val h = max(1, (bg.height * s).roundToInt())

        val small = Bitmap.createScaledBitmap(bg, w, h, true)
        val smallFg = Bitmap.createScaledBitmap(fg, w, h, true)
        val px = IntArray(w * h)
        val fa = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        smallFg.getPixels(fa, 0, w, 0, 0, w, h)
        small.recycle(); smallFg.recycle()

        // Zona do sujeito, alargada
        val hole = BooleanArray(w * h) { (fa[it] ushr 24) > 20 }
        val holeD = dilate(hole, w, h, DILATE)

        // Nível 0
        val levels = ArrayList<Level>()
        var lv = Level(w, h)
        for (i in 0 until w * h) {
            if (!holeD[i]) {
                val c = px[i]
                lv.r[i] = Color.red(c).toFloat(); lv.g[i] = Color.green(c).toFloat(); lv.b[i] = Color.blue(c).toFloat()
                lv.wt[i] = 1f
            }
        }
        levels.add(lv)

        // Push: reduz para metade somando só os píxeis conhecidos
        while (lv.w > 1 || lv.h > 1) {
            val nw = (lv.w + 1) / 2
            val nh = (lv.h + 1) / 2
            val nx = Level(nw, nh)
            for (y in 0 until lv.h) for (x in 0 until lv.w) {
                val i = y * lv.w + x
                val o = (y / 2) * nw + (x / 2)
                nx.r[o] += lv.r[i]; nx.g[o] += lv.g[i]; nx.b[o] += lv.b[i]; nx.wt[o] += lv.wt[i]
            }
            levels.add(nx)
            lv = nx
        }

        // Normaliza o topo
        val top = levels.last()
        for (i in top.r.indices) {
            if (top.wt[i] > 0f) { top.r[i] /= top.wt[i]; top.g[i] /= top.wt[i]; top.b[i] /= top.wt[i] }
            else { top.r[i] = 90f; top.g[i] = 90f; top.b[i] = 90f }
            top.wt[i] = 1f
        }

        // Pull: do grosso para o fino, os buracos herdam a cor do nível de cima
        for (L in levels.size - 2 downTo 0) {
            val f = levels[L]
            val c = levels[L + 1]
            val full = if (L == 0) 1f else 4f.pow(L) * 0.25f
            for (y in 0 until f.h) for (x in 0 until f.w) {
                val i = y * f.w + x
                // amostra bilinear do nível grosso
                val gx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (c.w - 1).toFloat())
                val gy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (c.h - 1).toFloat())
                val x0 = gx.toInt(); val y0 = gy.toInt()
                val x1 = min(x0 + 1, c.w - 1); val y1 = min(y0 + 1, c.h - 1)
                val tx = gx - x0; val ty = gy - y0
                val i00 = y0 * c.w + x0; val i10 = y0 * c.w + x1
                val i01 = y1 * c.w + x0; val i11 = y1 * c.w + x1
                val w00 = (1 - tx) * (1 - ty); val w10 = tx * (1 - ty)
                val w01 = (1 - tx) * ty; val w11 = tx * ty
                val cr = c.r[i00] * w00 + c.r[i10] * w10 + c.r[i01] * w01 + c.r[i11] * w11
                val cg = c.g[i00] * w00 + c.g[i10] * w10 + c.g[i01] * w01 + c.g[i11] * w11
                val cb = c.b[i00] * w00 + c.b[i10] * w10 + c.b[i01] * w01 + c.b[i11] * w11
                val wt = f.wt[i]
                if (wt > 0f) {
                    val k = min(1f, wt / full)
                    f.r[i] = cr + (f.r[i] / wt - cr) * k
                    f.g[i] = cg + (f.g[i] / wt - cg) * k
                    f.b[i] = cb + (f.b[i] / wt - cb) * k
                } else {
                    f.r[i] = cr; f.g[i] = cg; f.b[i] = cb
                }
                f.wt[i] = 1f
            }
        }

        val base = levels[0]
        val outPx = IntArray(w * h) {
            Color.rgb(
                base.r[it].roundToInt().coerceIn(0, 255),
                base.g[it].roundToInt().coerceIn(0, 255),
                base.b[it].roundToInt().coerceIn(0, 255)
            )
        }
        val fillSmall = Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)

        // Máscara suave da zona preenchida
        val maskPx = IntArray(w * h) { if (holeD[it]) Color.argb(255, 0, 0, 0) else 0 }
        val maskSmall = Bitmap.createBitmap(maskPx, w, h, Bitmap.Config.ARGB_8888)

        // Compõe à resolução total: original fora do buraco, preenchimento dentro
        val out = bg.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(out)
        val dst = Rect(0, 0, out.width, out.height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val layer = cv.saveLayer(null, null)
        cv.drawBitmap(fillSmall, null, dst, paint)
        cv.drawBitmap(maskSmall, null, dst, Paint(paint).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        cv.restoreToCount(layer)
        fillSmall.recycle(); maskSmall.recycle()
        return out
    }

    private class Level(val w: Int, val h: Int) {
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h); val wt = FloatArray(w * h)
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, rad: Int): BooleanArray {
        // duas passagens separáveis (horizontal + vertical) — aproximação quadrada
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            var last = -10_000
            for (x in 0 until w) { if (m[y * w + x]) last = x; if (x - last <= rad) tmp[y * w + x] = true }
            last = 10_000
            for (x in w - 1 downTo 0) { if (m[y * w + x]) last = x; if (last - x <= rad) tmp[y * w + x] = true }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -10_000
            for (y in 0 until h) { if (tmp[y * w + x]) last = y; if (y - last <= rad) out[y * w + x] = true }
            last = 10_000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x]) last = y; if (last - y <= rad) out[y * w + x] = true }
        }
        return out
    }

    private fun Float.pow(n: Int): Float { var r = 1f; repeat(n) { r *= this }; return r }
}
