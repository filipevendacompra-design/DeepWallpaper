package pt.filipe.profundo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Inflater

/**
 * Clipe já processado, guardado numa pasta:
 *   meta.txt         → "count fps maskW maskH"
 *   f0000.jpg …      → frames a cores
 *   m0000.bin …      → máscara do sujeito (bytes 0..255, comprimidos com Deflate)
 *   avg.png          → máscara média (para posicionar o relógio)
 *
 * Reutiliza sempre os mesmos bitmaps para não encher a memória durante a reprodução.
 */
class VideoClip private constructor(
    private val dir: File,
    val count: Int,
    val fps: Float,
    val maskW: Int,
    val maskH: Int
) {
    private var frame: Bitmap? = null
    val mask: Bitmap = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ALPHA_8)
    private val maskBytes = ByteArray(maskW * maskH)
    private val inflater = Inflater()

    val currentFrame: Bitmap? get() = frame

    /** Carrega o frame i e a respetiva máscara. Devolve false se falhar. */
    fun load(i: Int): Boolean {
        return try {
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inBitmap = frame
            }
            val f = BitmapFactory.decodeFile(frameFile(dir, i).path, opts) ?: return false
            frame = f

            val packed = maskFile(dir, i).readBytes()
            inflater.reset()
            inflater.setInput(packed)
            var off = 0
            while (off < maskBytes.size && !inflater.finished()) {
                val n = inflater.inflate(maskBytes, off, maskBytes.size - off)
                if (n == 0 && inflater.needsInput()) break
                off += n
            }
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(maskBytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun release() {
        inflater.end()
    }

    companion object {
        fun frameFile(dir: File, i: Int) = File(dir, "f%04d.jpg".format(i))
        fun maskFile(dir: File, i: Int) = File(dir, "m%04d.bin".format(i))
        fun avgFile(dir: File) = File(dir, "avg.png")
        fun metaFile(dir: File) = File(dir, "meta.txt")

        fun open(dir: File): VideoClip? = try {
            val p = metaFile(dir).readText().trim().split(" ")
            VideoClip(dir, p[0].toInt(), p[1].toFloat(), p[2].toInt(), p[3].toInt())
                .takeIf { it.count > 0 }
        } catch (_: Exception) {
            null
        }
    }
}
