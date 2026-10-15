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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Profundidade monocular com Depth Anything V2 Small (export Qualcomm AI Hub, licença MIT).
 * Entrada RGB em [0,1] (a normalização ImageNet está dentro do modelo).
 * Saída: disparidade relativa — maior = mais perto. Normalizada para 0..1.
 */
class DepthEstimator(ctx: Context) : AutoCloseable {

    class Result(val w: Int, val h: Int, val d: FloatArray, val ms: Long)

    private val interp: Interpreter
    private val nchw: Boolean
    private val iw: Int
    private val ih: Int
    private val ow: Int
    private val oh: Int
    private val outTotal: Int
    private val input: ByteBuffer
    private val output: ByteBuffer

    init {
        val fd = ctx.assets.openFd("depth.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        val inT = interp.getInputTensor(0)
        if (inT.dataType() != DataType.FLOAT32) error("Entrada ${inT.dataType()} não suportada")
        val s = inT.shape()                       // [1,H,W,3] ou [1,3,H,W]
        nchw = s.size == 4 && s[1] == 3
        ih = if (nchw) s[2] else s[1]
        iw = if (nchw) s[3] else s[2]
        val os = interp.getOutputTensor(0).shape()
        outTotal = os.fold(1) { a, b -> a * b }
        val dims = os.filter { it > 1 }
        oh = if (dims.size >= 2) dims[dims.size - 2] else ih
        ow = if (dims.size >= 2) dims[dims.size - 1] else outTotal / oh
        input = ByteBuffer.allocateDirect(4 * 3 * iw * ih).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(4 * outTotal).order(ByteOrder.nativeOrder())
    }

    fun estimate(photo: Bitmap): Result {
        val t0 = System.currentTimeMillis()
        val scaled = Bitmap.createScaledBitmap(photo, iw, ih, true)
        val px = IntArray(iw * ih)
        scaled.getPixels(px, 0, iw, 0, 0, iw, ih)
        if (scaled !== photo) scaled.recycle()

        input.clear()
        if (nchw) {
            for (p in px) input.putFloat(Color.red(p) / 255f)
            for (p in px) input.putFloat(Color.green(p) / 255f)
            for (p in px) input.putFloat(Color.blue(p) / 255f)
        } else {
            for (p in px) {
                input.putFloat(Color.red(p) / 255f)
                input.putFloat(Color.green(p) / 255f)
                input.putFloat(Color.blue(p) / 255f)
            }
        }
        input.rewind()
        output.clear()
        interp.run(input, output)
        output.rewind()

        val raw = FloatArray(ow * oh) { output.float }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in raw) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = max(1e-6f, hi - lo)
        val d = FloatArray(ow * oh) { ((raw[it] - lo) / span).coerceIn(0f, 1f) }
        return Result(ow, oh, boxBlur(d, ow, oh, max(1, ow / 200)), System.currentTimeMillis() - t0)
    }

    override fun close() = interp.close()

    companion object {
        /** Reduz um mapa para w×h (média por blocos). */
        fun downsample(src: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val x0 = x * sw / w; val x1 = max(x0 + 1, (x + 1) * sw / w)
                val y0 = y * sh / h; val y1 = max(y0 + 1, (y + 1) * sh / h)
                var acc = 0f; var n = 0
                for (yy in y0 until min(y1, sh)) for (xx in x0 until min(x1, sw)) { acc += src[yy * sw + xx]; n++ }
                out[y * w + x] = if (n > 0) acc / n else 0f
            }
            return out
        }

        /** Desfoque em caixa (2 passagens ≈ gaussiano). */
        fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            if (r <= 0) return src
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

        /** Medianas da profundidade no sujeito e no fundo (alpha do sujeito à mesma resolução). */
        fun medians(d: FloatArray, alpha: IntArray): Pair<Float, Float> {
            val subj = ArrayList<Float>(); val back = ArrayList<Float>()
            for (i in d.indices) {
                val al = alpha[i]
                if (al > 128) subj.add(d[i]) else if (al < 20) back.add(d[i])
            }
            if (subj.size < 20 || back.size < 20) return Pair(0.7f, 0.3f)
            subj.sort(); back.sort()
            return Pair(subj[subj.size / 2], back[back.size / 2])
        }

        fun toBitmap(d: FloatArray, w: Int, h: Int): Bitmap {
            val px = IntArray(w * h) {
                val g = (d[it] * 255f).roundToInt().coerceIn(0, 255)
                Color.rgb(g, g, g)
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        fun fromBitmap(b: Bitmap): FloatArray {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            return FloatArray(px.size) { Color.red(px[it]) / 255f }
        }
    }
}

/**
 * Malha deformada pela profundidade. Cada vértice desloca-se com a inclinação,
 * proporcionalmente à distância ao plano que fica parado (relógio, sujeito ou fundo).
 * Trabalha sobre um mapa pequeno (DW×DH) para ser leve, mesmo a mudar a cada frame no vídeo.
 */
class DepthMesh {
    private var d = FloatArray(DW * DH)
    private var subj = 0.7f
    private var back = 0.3f
    private var frameId = 0L

    // cache
    private var key = ""
    private var baseX = FloatArray(0)
    private var baseY = FloatArray(0)
    private var dv = FloatArray(0)
    private var smoothed = FloatArray(0)
    var verts = FloatArray(0); private set

    /** Define a profundidade (já à resolução DW×DH) e as medianas sujeito/fundo. */
    fun set(depth: FloatArray, subjectDepth: Float, backDepth: Float) {
        d = depth; subj = subjectDepth; back = backDepth; frameId++
    }

    fun planeFor(cfg: ClockConfig) = when (cfg.focus) {
        ClockConfig.FOCUS_SUBJECT -> subj
        ClockConfig.FOCUS_BACKGROUND -> back
        else -> back + (subj - back) * 0.55f
    }.coerceIn(0.02f, 0.98f)

    fun update(bw: Int, bh: Int, w: Int, h: Int, zoom: Float, ampX: Float, ampY: Float, cfg: ClockConfig) {
        val radius = (cfg.smooth.coerceIn(0f, 1f) * 6f).roundToInt()
        val plane = planeFor(cfg)
        val geo = "$bw-$bh-$w-$h-$zoom"
        val sep = (cfg.separation.coerceIn(0f, 1f) * 20f).roundToInt()
        val k = "$geo-$radius-$plane-$sep-$frameId"
        if (k != key) {
            val geoChanged = !key.startsWith("$geo-")
            key = k
            val n = (MESH_W + 1) * (MESH_H + 1)
            if (geoChanged || baseX.size != n) {
                val scale = max(w.toFloat() / bw, h.toFloat() / bh) * zoom
                val left = (w - bw * scale) / 2f
                val top = (h - bh * scale) / 2f
                baseX = FloatArray(n); baseY = FloatArray(n); dv = FloatArray(n); verts = FloatArray(n * 2)
                var i = 0
                for (j in 0..MESH_H) for (q in 0..MESH_W) {
                    baseX[i] = left + q / MESH_W.toFloat() * bw * scale
                    baseY[i] = top + j / MESH_H.toFloat() * bh * scale
                    i++
                }
            }
            smoothed = DepthEstimator.boxBlur(d, DW, DH, radius)
            // Normaliza para que o maior deslocamento nunca passe a margem de zoom
            val norm = 1f / max(plane, 1f - plane)
            // Separação de planos: expoente < 1 amplia as diferenças pequenas de profundidade
            val exp = 1f - 0.65f * (sep / 20f)
            var i = 0
            for (j in 0..MESH_H) for (q in 0..MESH_W) {
                val v = ((sample(q / MESH_W.toFloat(), j / MESH_H.toFloat()) - plane) * norm).coerceIn(-1f, 1f)
                dv[i] = if (v >= 0f) abs(v).pow(exp) else -abs(v).pow(exp)
                i++
            }
        }
        for (i in baseX.indices) {
            verts[i * 2] = baseX[i] + ampX * dv[i]
            verts[i * 2 + 1] = baseY[i] + ampY * dv[i]
        }
    }

    private fun sample(u: Float, v: Float): Float {
        val s = smoothed
        val x = (u * (DW - 1)).coerceIn(0f, (DW - 1).toFloat())
        val y = (v * (DH - 1)).coerceIn(0f, (DH - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, DW - 1); val y1 = min(y0 + 1, DH - 1)
        val tx = x - x0; val ty = y - y0
        val a = s[y0 * DW + x0] * (1 - tx) + s[y0 * DW + x1] * tx
        val b = s[y1 * DW + x0] * (1 - tx) + s[y1 * DW + x1] * tx
        return a * (1 - ty) + b * ty
    }

    companion object {
        const val MESH_W = 48
        const val MESH_H = 96
        const val DW = 128   // resolução de trabalho da profundidade
        const val DH = 128

        /** Prepara a profundidade de uma foto (mapa do modelo + sujeito) para a malha. */
        fun forPhoto(depthFull: FloatArray, fw: Int, fh: Int, fg: Bitmap?): DepthMesh {
            val small = DepthEstimator.downsample(depthFull, fw, fh, DW, DH)
            val m = DepthMesh()
            var sm = 0.7f; var bm = 0.3f
            if (fg != null) {
                val a = Bitmap.createScaledBitmap(fg, DW, DH, true)
                val px = IntArray(DW * DH)
                a.getPixels(px, 0, DW, 0, 0, DW, DH)
                val med = DepthEstimator.medians(small, IntArray(px.size) { px[it] ushr 24 })
                sm = med.first; bm = med.second
            }
            m.set(small, sm, bm)
            return m
        }
    }
}
