package pt.filipe.profundo

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var bg: Bitmap? = null
    private var fg: Bitmap? = null
    private var fgSmall: Bitmap? = null   // sujeito à escala da pré-visualização (para medir cobertura)
    private var mask: Bitmap? = null      // vídeo: máscara do 1.º frame (pré-visualização)
    private var avgMask: Bitmap? = null   // vídeo: máscara média (posição do relógio)
    private var pendingMode = Store.MODE_PHOTO
    private var busy = false
    private lateinit var cfg: ClockConfig

    private var screenW = 1080
    private var screenH = 2340

    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var posBar: SeekBar
    private lateinit var sizeBar: SeekBar
    private lateinit var homeBox: CheckBox
    private lateinit var applyBtn: Button

    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importPhoto(uri)
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readScreenSize()
        cfg = ClockConfig.load(Store.prefs(this))
        buildUi()
        loadSaved()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(32))
            setBackgroundColor(Color.rgb(18, 18, 20))
        }
        fun label(t: String, sizeSp: Float = 14f) = TextView(this).apply {
            text = t; textSize = sizeSp; setTextColor(Color.rgb(220, 220, 225))
            setPadding(0, dp(10), 0, dp(4))
        }

        root.addView(label("Profundo", 24f))
        root.addView(Button(this).apply {
            text = "Escolher foto"
            setOnClickListener {
                if (!busy) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        })
        root.addView(Button(this).apply {
            text = "Escolher vídeo (até ${VideoProcessor.MAX_SECONDS.toInt()} s)"
            setOnClickListener {
                if (!busy) pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        })
        status = label("Escolhe uma foto com um sujeito bem destacado (pessoa, animal, objeto).")
        root.addView(status)

        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val previewH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, previewH).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8)
        })

        root.addView(label("Posição do relógio"))
        posBar = SeekBar(this).apply {
            max = 100
            progress = ((cfg.y - 0.05f) / 0.55f * 100).roundToInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(simpleListener { p ->
                cfg = cfg.copy(y = 0.05f + p / 100f * 0.55f); renderPreview()
            })
        }
        root.addView(posBar)

        root.addView(label("Tamanho do relógio"))
        sizeBar = SeekBar(this).apply {
            max = 100
            progress = ((cfg.size - 0.15f) / 0.25f * 100).roundToInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(simpleListener { p ->
                cfg = cfg.copy(size = 0.15f + p / 100f * 0.25f); renderPreview()
            })
        }
        root.addView(sizeBar)

        root.addView(Button(this).apply {
            text = "Posição automática"
            setOnClickListener { autoPosition() }
        })

        root.addView(label("Cor"))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Color.WHITE, Color.rgb(255, 233, 199), Color.rgb(143, 211, 255), Color.rgb(20, 20, 20))
            .forEach { col ->
                colors.addView(View(this).apply {
                    setBackgroundColor(col)
                    setOnClickListener { cfg = cfg.copy(color = col); renderPreview() }
                }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
            }
        root.addView(colors)

        homeBox = CheckBox(this).apply {
            text = "Mostrar o relógio também no ecrã inicial"
            setTextColor(Color.rgb(220, 220, 225))
            isChecked = cfg.showOnHome
            setOnCheckedChangeListener { _, c -> cfg = cfg.copy(showOnHome = c) }
        }
        root.addView(homeBox)

        root.addView(CheckBox(this).apply {
            text = "Efeito paralaxe (mexe com a inclinação do telemóvel)"
            setTextColor(Color.rgb(220, 220, 225))
            isChecked = cfg.parallax
            setOnCheckedChangeListener { _, c -> cfg = cfg.copy(parallax = c); prepareSmall(); renderPreview() }
        })

        root.addView(label("Intensidade da paralaxe"))
        root.addView(SeekBar(this).apply {
            max = 100
            // 0..100 → 0.25..2.0 (meio ≈ 1.1)
            progress = ((cfg.intensity - 0.25f) / 1.75f * 100).roundToInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(simpleListener { p ->
                cfg = cfg.copy(intensity = 0.25f + p / 100f * 1.75f)
                prepareSmall(); renderPreview()
            })
        })

        applyBtn = Button(this).apply {
            text = "Aplicar como fundo"
            isEnabled = false
            setOnClickListener { saveAndApply() }
        }
        root.addView(applyBtn)

        root.addView(label(
            "Antes de aplicar: no Good Lock → LockStar, cria um relógio personalizado só com espaços " +
            "para esconder o relógio da Samsung. Aplica o fundo ao ecrã de bloqueio e ao inicial."
        , 12f))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun simpleListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ---------- Fotos e recorte ----------

    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            screenW = resources.displayMetrics.widthPixels; screenH = resources.displayMetrics.heightPixels
        }
    }

    private fun loadSaved() {
        if (Store.mode(Store.prefs(this)) == Store.MODE_VIDEO) {
            if (showVideoPreview(Store.videoDir(this))) {
                status.text = "Vídeo atual carregado. Ajusta e aplica."
                return
            }
        }
        val bf = Store.bgFile(this); val ff = Store.fgFile(this)
        if (bf.exists() && ff.exists()) {
            bg = BitmapFactory.decodeFile(bf.path)
            fg = BitmapFactory.decodeFile(ff.path)
            prepareSmall()
            applyBtn.isEnabled = true
            status.text = "Foto atual carregada. Ajusta e aplica."
            renderPreview()
        }
    }

    private fun importPhoto(uri: Uri) {
        status.text = "A recortar o sujeito…"
        applyBtn.isEnabled = false
        Thread {
            val bmp = try {
                decodeLimited(uri, 2400).let {
                    if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false)
                }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (bmp == null) { status.text = "Não consegui abrir a foto."; return@runOnUiThread }
                segmenter.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { result ->
                        val cut = result.foregroundBitmap
                        if (cut == null) {
                            status.text = "Não encontrei nenhum sujeito nesta foto. Tenta outra."
                        } else {
                            bg = bmp; fg = cut; mask = null; avgMask = null
                            pendingMode = Store.MODE_PHOTO
                            prepareSmall()
                            autoPosition()
                            applyBtn.isEnabled = true
                        }
                    }
                    .addOnFailureListener { e ->
                        status.text = "Falhou o recorte: ${e.message}\n" +
                            "Se for a primeira vez, o modelo pode ainda estar a descarregar — tenta daqui a um minuto."
                    }
            }
        }.start()
    }

    // ---------- Vídeo ----------

    private fun importVideo(uri: Uri) {
        busy = true
        applyBtn.isEnabled = false
        status.text = "A preparar o vídeo…"
        val out = Store.videoNewDir(this)
        Thread {
            try {
                val r = VideoProcessor.process(this, uri, out) { done, total ->
                    runOnUiThread { status.text = "A recortar o sujeito frame a frame… $done/$total" }
                }
                runOnUiThread {
                    busy = false
                    if (showVideoPreview(r.dir)) {
                        pendingMode = Store.MODE_VIDEO
                        autoPosition()
                        status.text = "Vídeo pronto: ${r.frames} frames a ${r.fps.roundToInt()} fps. " + status.text
                    } else {
                        status.text = "O vídeo foi processado mas não consegui abri-lo."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    status.text = "Falhou o vídeo: ${e.message}\n" +
                        "Se for a primeira vez, o modelo de recorte pode ainda estar a descarregar."
                }
            }
        }.start()
    }

    /** Pré-visualização com o 1.º frame do clipe. */
    private fun showVideoPreview(dir: File): Boolean {
        val clip = VideoClip.open(dir) ?: return false
        if (!clip.load(0)) { clip.release(); return false }
        bg = clip.currentFrame
        mask = clip.mask
        fg = null
        avgMask = BitmapFactory.decodeFile(VideoClip.avgFile(dir).path)
        clip.release()
        prepareSmall()
        applyBtn.isEnabled = true
        renderPreview()
        return true
    }

    private fun decodeLimited(uri: Uri, maxSide: Int): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val s = maxSide.toFloat() / max(w, h)
            if (s < 1f) decoder.setTargetSize((w * s).toInt(), (h * s).toInt())
        }
    }

    /** Versão pequena do sujeito, à proporção do ecrã, para medir cobertura rapidamente. */
    private fun prepareSmall() {
        val f = (if (mask != null) avgMask else fg) ?: return
        val w = 270
        val h = (w * screenH.toFloat() / screenW).toInt()
        fgSmall = DepthRenderer.fgForScreen(f, w, h, DepthRenderer.zoomFor(cfg))
    }

    private fun autoPosition() {
        val small = fgSmall ?: return
        val is24 = DateFormat.is24HourFormat(this)
        val y = DepthRenderer.autoY(small, cfg, Date(), is24)
        if (y != null) {
            cfg = cfg.copy(y = y)
            posBar.progress = ((y - 0.05f) / 0.55f * 100).roundToInt().coerceIn(0, 100)
        }
        renderPreview()
    }

    private fun renderPreview() {
        val w = screenW / 3
        val h = screenH / 3
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val is24 = DateFormat.is24HourFormat(this)
        DepthRenderer.draw(Canvas(out), bg, fg, cfg, showClock = true, now = Date(), is24 = is24, mask = mask)
        preview.setImageBitmap(out)
        fgSmall?.let {
            val cov = DepthRenderer.coverage(it, cfg, Date(), is24)
            val pct = (cov * 100).roundToInt()
            status.text = when {
                cov < 0.05f -> "O sujeito quase não toca no relógio ($pct%) — não há efeito."
                cov > 0.45f -> "O sujeito tapa $pct% dos dígitos — a hora fica difícil de ler."
                else -> "O sujeito tapa $pct% dos dígitos. Bom equilíbrio."
            }
        }
    }

    // ---------- Guardar e aplicar ----------

    private fun saveAndApply() {
        if (busy) return
        if (pendingMode == Store.MODE_VIDEO || (mask != null && fg == null)) { saveVideoAndApply(); return }
        val b = bg ?: return
        val f = fg ?: return
        status.text = "A guardar…"
        Thread {
            writeAtomic(Store.bgFile(this)) { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            writeAtomic(Store.fgFile(this)) { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val p = Store.prefs(this)
            cfg.save(p)
            p.edit().putString("mode", Store.MODE_PHOTO).putLong("version", System.currentTimeMillis()).apply()
            runOnUiThread {
                status.text = "Guardado."
                openWallpaperPicker()
            }
        }.start()
    }

    private fun saveVideoAndApply() {
        status.text = "A guardar…"
        Thread {
            val fresh = Store.videoNewDir(this)
            val live = Store.videoDir(this)
            if (VideoClip.metaFile(fresh).exists()) {
                val old = File(filesDir, "video_old")
                old.deleteRecursively()
                if (live.exists()) live.renameTo(old)
                fresh.renameTo(live)
                old.deleteRecursively()
            }
            val p = Store.prefs(this)
            cfg.save(p)
            p.edit().putString("mode", Store.MODE_VIDEO).putLong("version", System.currentTimeMillis()).apply()
            runOnUiThread {
                status.text = "Guardado."
                openWallpaperPicker()
            }
        }.start()
    }

    private fun writeAtomic(target: File, write: (FileOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use(write)
        tmp.renameTo(target)
    }

    private fun openWallpaperPicker() {
        val current = WallpaperManager.getInstance(this).wallpaperInfo
        if (current?.packageName == packageName) {
            status.text = "Fundo atualizado."
            return
        }
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, DepthWallpaperService::class.java)
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
