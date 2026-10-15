package pt.filipe.profundo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Profundidade monocular com Depth Anything V2 Small (export da Qualcomm AI Hub, licença MIT).
 * Entrada: RGB em [0,1] (a normalização ImageNet está dentro do modelo).
 * Saída: disparidade relativa — valores maiores = mais perto.
 * O formato dos tensores (NHWC/NCHW) é lido do próprio modelo.
 */
object DepthEstimator {

    private const val ASSET = "depth.tflite"

    class Result(val w: Int, val h: Int, val d: FloatArray, val ms: Long)

    fun estimate(ctx: Context, photo: Bitmap): Result {
        val t0 = System.currentTimeMillis()
        val fd = ctx.assets.openFd(ASSET)
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        try {
            val inT = interp.getInputTensor(0)
            if (inT.dataType() != DataType.FLOAT32) error("Modelo com entrada ${inT.dataType()} não suportada")
            val s = inT.shape()                       // [1,H,W,3] ou [1,3,H,W]
            val nchw = s.size == 4 && s[1] == 3
            val ih = if (nchw) s[2] else s[1]
            val iw = if (nchw) s[3] else s[2]

            val scaled = Bitmap.createScaledBitmap(photo, iw, ih, true)
            val px = IntArray(iw * ih)
            scaled.getPixels(px, 0, iw, 0, 0, iw, ih)
            if (scaled !== photo) scaled.recycle()

            val input = ByteBuffer.allocateDirect(4 * 3 * iw * ih).order(ByteOrder.nativeOrder())
            if (nchw) {
                for (ch in 0 until 3) for (p in px) {
                    val v = when (ch) { 0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p) }
                    input.putFloat(v / 255f)
                }
            } else {
                for (p in px) {
                    input.putFloat(Color.red(p) / 255f)
                    input.putFloat(Color.green(p) / 255f)
                    input.putFloat(Color.blue(p) / 255f)
                }
            }
            input.rewind()

            val outT = interp.getOutputTensor(0)
            val os = outT.shape()
            val total = os.fold(1) { a, b -> a * b }
            val dims = os.filter { it > 1 }
            val oh = if (dims.size >= 2) dims[dims.size - 2] else ih
            val ow = if (dims.size >= 2) dims[dims.size - 1] else total / oh
            val output = ByteBuffer.allocateDirect(4 * total).order(ByteOrder.nativeOrder())
            interp.run(input, output)
            output.rewind()

            val raw = FloatArray(ow * oh) { output.float }
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (v in raw) { if (v < lo) lo = v; if (v > hi) hi = v }
            val span = max(1e-6f, hi - lo)
            val d = FloatArray(ow * oh) { ((raw[it] - lo) / span).coerceIn(0f, 1f) }
            return Result(ow, oh, smooth(d, ow, oh, max(2, ow / 130)), System.currentTimeMillis() - t0)
        } finally {
            interp.close()
        }
    }

    /** Desfoca ligeiramente a profundidade: as bordas esticam de forma suave em vez de rasgar. */
    private fun smooth(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        var a = src
        repeat(2) {
            val tmp = FloatArray(w * h)
            for (y in 0 until h) {
                var acc = 0f; var n = 0
                for (x in -r until w + r) {
                    if (x + r < w) { acc += a[y * w + x + r]; n++ }
                    if (x - r - 1 >= 0) { acc -= a[y * w + x - r - 1]; n-- }
                    if (x in 0 until w) tmp[y * w + x] = acc / max(1, n)
                }
            }
            val out = FloatArray(w * h)
            for (x in 0 until w) {
                var acc = 0f; var n = 0
                for (y in -r until h + r) {
                    if (y + r < h) { acc += tmp[(y + r) * w + x]; n++ }
                    if (y - r - 1 >= 0) { acc -= tmp[(y - r - 1) * w + x]; n-- }
                    if (y in 0 until h) out[y * w + x] = acc / max(1, n)
                }
            }
            a = out
        }
        return a
    }

    /** Guarda como imagem em tons de cinzento (0 = longe, 255 = perto). */
    fun toBitmap(r: Result): Bitmap {
        val px = IntArray(r.w * r.h) {
            val g = (r.d[it] * 255f).roundToInt().coerceIn(0, 255)
            Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(px, r.w, r.h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Malha deformada pela profundidade: cada vértice desloca-se com a inclinação,
 * proporcionalmente à distância ao "plano do relógio" (que fica parado).
 */
class DepthMesh(depthBmp: Bitmap, fg: Bitmap?) {
    private val dw = depthBmp.width
    private val dh = depthBmp.height
    private val d = FloatArray(dw * dh)
    val clockPlane: Float

    init {
        val px = IntArray(dw * dh)
        depthBmp.getPixels(px, 0, dw, 0, 0, dw, dh)
        for (i in px.indices) d[i] = Color.red(px[i]) / 255f

        // Plano do relógio: entre a profundidade do sujeito e a do fundo
        var plane = 0.5f
        if (fg != null) {
            val m = Bitmap.createScaledBitmap(fg, dw, dh, true)
            val a = IntArray(dw * dh)
            m.getPixels(a, 0, dw, 0, 0, dw, dh)
            val subj = ArrayList<Float>(); val back = ArrayList<Float>()
            for (i in a.indices) {
                val al = a[i] ushr 24
                if (al > 128) subj.add(d[i]) else if (al < 20) back.add(d[i])
            }
            if (subj.size > 50 && back.size > 50) {
                subj.sort(); back.sort()
                val sm = subj[subj.size / 2]; val bm = back[back.size / 2]
                plane = bm + (sm - bm) * 0.55f
            }
        }
        clockPlane = plane.coerceIn(0.05f, 0.95f)
    }

    private val norm = 1f / max(clockPlane, 1f - clockPlane)

    // Malha em cache (só muda quando muda o tamanho do ecrã / imagem / zoom)
    private var key = ""
    private var baseX = FloatArray(0)
    private var baseY = FloatArray(0)
    private var dv = FloatArray(0)
    var verts = FloatArray(0); private set

    fun update(bw: Int, bh: Int, w: Int, h: Int, zoom: Float, ampX: Float, ampY: Float) {
        val k = "$bw-$bh-$w-$h-$zoom"
        if (k != key) {
            key = k
            val scale = max(w.toFloat() / bw, h.toFloat() / bh) * zoom
            val left = (w - bw * scale) / 2f
            val top = (h - bh * scale) / 2f
            val n = (MESH_W + 1) * (MESH_H + 1)
            baseX = FloatArray(n); baseY = FloatArray(n); dv = FloatArray(n); verts = FloatArray(n * 2)
            var i = 0
            for (j in 0..MESH_H) for (q in 0..MESH_W) {
                val u = q / MESH_W.toFloat(); val v = j / MESH_H.toFloat()
                baseX[i] = left + u * bw * scale
                baseY[i] = top + v * bh * scale
                dv[i] = (sample(u, v) - clockPlane) * norm   // -1..1
                i++
            }
        }
        for (i in baseX.indices) {
            verts[i * 2] = baseX[i] + ampX * dv[i]
            verts[i * 2 + 1] = baseY[i] + ampY * dv[i]
        }
    }

    private fun sample(u: Float, v: Float): Float {
        val x = (u * (dw - 1)).coerceIn(0f, (dw - 1).toFloat())
        val y = (v * (dh - 1)).coerceIn(0f, (dh - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, dw - 1); val y1 = min(y0 + 1, dh - 1)
        val tx = x - x0; val ty = y - y0
        val a = d[y0 * dw + x0] * (1 - tx) + d[y0 * dw + x1] * tx
        val b = d[y1 * dw + x0] * (1 - tx) + d[y1 * dw + x1] * tx
        return a * (1 - ty) + b * ty
    }

    companion object {
        const val MESH_W = 48
        const val MESH_H = 96
    }
}
