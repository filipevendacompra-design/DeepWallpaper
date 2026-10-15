package pt.filipe.profundo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Processa um vídeo UMA vez: frames + máscara do sujeito por frame, suavizada no tempo.
 * Corre numa thread de fundo (usa Tasks.await).
 */
object VideoProcessor {

    const val MAX_SECONDS = 4f
    const val MAX_FPS = 30f
    private const val FRAME_LONG_SIDE = 1600   // resolução dos frames guardados
    private const val MASK_LONG_SIDE = 720     // resolução do recorte (e da máscara)
    private const val TEMPORAL = 0.35f         // peso do frame anterior: mais alto = menos tremor, mais "arrasto"
    const val DEPTH_STEP = 3                   // profundidade calculada em 1 de cada 3 frames (resto interpolado)

    class Result(val dir: File, val frames: Int, val fps: Float, val depthMsg: String)

    fun process(ctx: Context, uri: Uri, outDir: File, progress: (done: Int, total: Int) -> Unit): Result {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val frameCount = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                ?: throw IllegalStateException("Não consegui ler o número de frames do vídeo.")
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Não consegui ler a duração do vídeo.")
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rawW = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dispPortrait = if (rot % 180 == 0) rawH >= rawW else rawW >= rawH

            val srcFps = frameCount * 1000f / max(1L, durMs)
            val step = max(1, (srcFps / MAX_FPS).roundToInt())
            val outFps = srcFps / step
            val total = min(frameCount / step, (MAX_SECONDS * outFps).toInt())
            if (total < 2) throw IllegalStateException("Vídeo demasiado curto.")

            outDir.deleteRecursively()
            outDir.mkdirs()

            val segmenter = SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
            )
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            val deflater = Deflater(Deflater.BEST_SPEED)

            // Profundidade (se o modelo falhar, o vídeo fica sem malha 3D)
            var depthErr: String? = null
            val estimator = try { DepthEstimator(ctx) } catch (e: Throwable) {
                depthErr = "${e.javaClass.simpleName}: ${e.message}"; null
            }
            val depthLines = StringBuilder()
            var depthMs = 0L
            var depthCount = 0

            var mw = 0; var mh = 0
            var prev: FloatArray? = null
            var avg: FloatArray? = null
            var bytes: ByteArray? = null
            var written = 0

            for (k in 0 until total) {
                val raw = r.getFrameAtIndex(k * step, params) ?: break
                val frame = orient(raw, rot, dispPortrait)

                // Frame a cores para reprodução
                val (fw, fh) = fit(frame.width, frame.height, FRAME_LONG_SIDE)
                val big = Bitmap.createScaledBitmap(frame, fw, fh, true)
                FileOutputStream(VideoClip.frameFile(outDir, k)).use {
                    big.compress(Bitmap.CompressFormat.JPEG, 88, it)
                }
                big.recycle()

                // Recorte numa versão mais pequena
                val (sw, sh) = fit(frame.width, frame.height, MASK_LONG_SIDE)
                val small = Bitmap.createScaledBitmap(frame, sw, sh, true)
                if (frame !== raw) frame.recycle()
                raw.recycle()
                if (mw == 0) {
                    mw = sw; mh = sh
                    prev = FloatArray(mw * mh); avg = FloatArray(mw * mh); bytes = ByteArray(mw * mh)
                }

                val res = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
                var keyDepth: FloatArray? = null
                if (estimator != null && depthErr == null && k % DEPTH_STEP == 0) {
                    try {
                        val dr = estimator.estimate(small)
                        keyDepth = DepthEstimator.downsample(dr.d, dr.w, dr.h, DepthMesh.DW, DepthMesh.DH)
                        depthMs += dr.ms; depthCount++
                    } catch (e: Throwable) {
                        depthErr = "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                small.recycle()
                val conf = res.foregroundConfidenceMask
                val p = prev!!; val a = avg!!; val b = bytes!!
                conf?.rewind()
                for (i in 0 until mw * mh) {
                    val v = if (conf != null && conf.hasRemaining()) conf.get() else 0f
                    // Suavização no tempo: tira o "fervilhar" das bordas
                    val s = if (k == 0) v else v * (1f - TEMPORAL) + p[i] * TEMPORAL
                    p[i] = s
                    a[i] += s
                    b[i] = (smoothstep(0.3f, 0.7f, s) * 255f).roundToInt().toByte()
                }
                writeDeflated(VideoClip.maskFile(outDir, k), b, deflater)
                if (keyDepth != null) {
                    // medianas sujeito/fundo com a máscara deste frame
                    val alpha = IntArray(DepthMesh.DW * DepthMesh.DH) { i ->
                        val x = (i % DepthMesh.DW) * mw / DepthMesh.DW
                        val y = (i / DepthMesh.DW) * mh / DepthMesh.DH
                        b[y * mw + x].toInt() and 0xFF
                    }
                    val med = DepthEstimator.medians(keyDepth, alpha)
                    val bytes = ByteArray(keyDepth.size) { (keyDepth[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }
                    VideoClip.depthFile(outDir, k / DEPTH_STEP).writeBytes(bytes)
                    depthLines.append("${med.first} ${med.second}\n")
                }
                written++
                progress(written, total)
            }
            deflater.end()
            segmenter.close()
            estimator?.close()
            val keys = depthLines.lines().count { it.isNotBlank() }
            val depthMsg = if (depthErr == null && keys > 0) {
                VideoClip.depthMetaFile(outDir).writeText("${DEPTH_STEP} $keys\n$depthLines")
                "Profundidade 3D ativa ($keys frames-chave, ~${depthMs / max(1, depthCount)} ms cada)."
            } else {
                VideoClip.depthMetaFile(outDir).delete()
                "Profundidade indisponível no vídeo (${depthErr ?: "sem frames"})."
            }
            if (written < 2) throw IllegalStateException("Não consegui extrair frames do vídeo.")

            // Máscara média → usada para escolher a posição do relógio
            val a = avg!!
            val pixels = IntArray(mw * mh) { i ->
                val al = (a[i] / written * 255f).roundToInt().coerceIn(0, 255)
                Color.argb(al, 0, 0, 0)
            }
            val avgBmp = Bitmap.createBitmap(pixels, mw, mh, Bitmap.Config.ARGB_8888)
            FileOutputStream(VideoClip.avgFile(outDir)).use { avgBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            avgBmp.recycle()

            VideoClip.metaFile(outDir).writeText("$written $outFps $mw $mh")
            return Result(outDir, written, outFps, depthMsg)
        } finally {
            r.release()
        }
    }

    /** Alguns telemóveis devolvem o frame sem aplicar a rotação do vídeo — corrige se for o caso. */
    private fun orient(b: Bitmap, rot: Int, dispPortrait: Boolean): Bitmap {
        val isPortrait = b.height >= b.width
        if (rot % 180 == 0 || isPortrait == dispPortrait) return b
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun fit(w: Int, h: Int, longSide: Int): Pair<Int, Int> {
        val s = min(1f, longSide.toFloat() / max(w, h))
        return Pair(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun writeDeflated(f: File, data: ByteArray, d: Deflater) {
        d.reset()
        d.setInput(data)
        d.finish()
        val buf = ByteArray(64 * 1024)
        FileOutputStream(f).use { out ->
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.write(buf, 0, n)
            }
        }
    }
}
