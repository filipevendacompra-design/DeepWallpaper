package pt.filipe.profundo

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.palette.graphics.Palette
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    // ---- Conteúdo ----
    private var bg: Bitmap? = null
    private var fg: Bitmap? = null
    private var fgSmall: Bitmap? = null       // sujeito à escala da pré-visualização (cobertura)
    private var mask: Bitmap? = null          // vídeo: máscara do 1.º frame
    private var avgMask: Bitmap? = null       // vídeo: máscara média
    private var pendingMode = Store.MODE_PHOTO
    private var busy = false
    private lateinit var cfg: ClockConfig

    // Profundidade da foto escolhida (calculada logo ao importar)
    private var photoDepth: DepthEstimator.Result? = null
    private var previewMesh: DepthMesh? = null
    private var depthMsg = ""

    private var screenW = 1080
    private var screenH = 2340

    // ---- UI ----
    private lateinit var status: TextView
    private lateinit var coverageText: TextView
    private lateinit var preview: ImageView
    private lateinit var posBar: SeekBar
    private lateinit var applyBtn: Button
    private var previewBmp: Bitmap? = null
    private val chipGroups = mutableListOf<Pair<List<Button>, () -> Int>>()

    private val bgColor = Color.rgb(14, 14, 16)
    private val cardColor = Color.rgb(28, 28, 32)
    private val textColor = Color.rgb(228, 228, 232)
    private val mutedColor = Color.rgb(140, 140, 150)
    private val accent = Color.rgb(120, 190, 255)

    private val swatches = intArrayOf(
        Color.WHITE, Color.rgb(255, 236, 205), Color.rgb(255, 214, 102), Color.rgb(255, 159, 90),
        Color.rgb(255, 107, 107), Color.rgb(255, 128, 191), Color.rgb(196, 140, 255), Color.rgb(120, 150, 255),
        Color.rgb(102, 204, 255), Color.rgb(94, 234, 200), Color.rgb(150, 230, 120), Color.rgb(24, 24, 28)
    )

    private val segmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importPhoto(uri)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importVideo(uri)
    }

    // ---- Pré-visualização animada (mostra o 3D a balançar) ----
    private val ui = Handler(Looper.getMainLooper())
    private val animStart = SystemClock.uptimeMillis()
    private val animLoop = object : Runnable {
        override fun run() {
            renderPreview(animated = true)
            if (previewMesh != null && cfg.parallax) ui.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readScreenSize()
        cfg = ClockConfig.load(Store.prefs(this))
        buildUi()
        loadSaved()
    }

    override fun onResume() { super.onResume(); startAnim() }
    override fun onPause() { super.onPause(); ui.removeCallbacks(animLoop) }

    private fun startAnim() {
        ui.removeCallbacks(animLoop)
        if (previewMesh != null && cfg.parallax) ui.post(animLoop) else renderPreview(animated = false)
    }

    // ================= UI =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(40))
            setBackgroundColor(bgColor)
        }
        root.addView(TextView(this).apply { text = "Profundo"; textSize = 26f; setTextColor(textColor) })

        val pick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pick.addView(pill("Escolher foto") {
            if (!busy) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        pick.addView(pill("Vídeo (${VideoProcessor.MAX_SECONDS.toInt()} s)") {
            if (!busy) pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(pick, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        status = TextView(this).apply {
            text = "Escolhe uma foto com um sujeito bem destacado."
            textSize = 13f; setTextColor(mutedColor); setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(status)

        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.BLACK) }
            clipToOutline = true
        }
        val ph = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val pw = (ph * screenW / screenH.toFloat()).toInt()
        root.addView(preview, LinearLayout.LayoutParams(pw, ph).apply { gravity = Gravity.CENTER_HORIZONTAL })
        coverageText = TextView(this).apply {
            textSize = 13f; setTextColor(mutedColor); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        }
        root.addView(coverageText)

        // ---------- Relógio ----------
        root.addView(section("Relógio"))
        root.addView(chips(listOf("Horizontal", "Vertical", "Analógico"), { cfg.style }) {
            cfg = cfg.copy(style = it); onClockChanged()
        })
        root.addView(scrollChips(listOf("Moderno", "Fino", "Condensado", "Clássico", "Mono"), { cfg.font }) {
            cfg = cfg.copy(font = it); onClockChanged()
        })
        root.addView(chips(listOf("Data acima", "Data abaixo", "Sem data"), { cfg.dateMode }) {
            cfg = cfg.copy(dateMode = it); onClockChanged()
        })
        posBar = slider(root, "Posição", 0, 100, ((cfg.y - 0.05f) / 0.6f * 100).roundToInt(), { "$it" }) {
            cfg = cfg.copy(y = 0.05f + it / 100f * 0.6f); onClockChanged()
        }
        slider(root, "Tamanho", 0, 100, ((cfg.size - 0.12f) / 0.30f * 100).roundToInt(), { "$it" }) {
            cfg = cfg.copy(size = 0.12f + it / 100f * 0.30f); onClockChanged()
        }
        slider(root, "Opacidade", 30, 100, (cfg.opacity * 100).roundToInt(), { "$it%" }) {
            cfg = cfg.copy(opacity = it / 100f); onClockChanged()
        }
        root.addView(check("Contorno (só o traço)", cfg.outline) { cfg = cfg.copy(outline = it); onClockChanged() })
        root.addView(check("Sombra suave", cfg.shadow) { cfg = cfg.copy(shadow = it); onClockChanged() })
        root.addView(check("Relógio à frente do sujeito", cfg.front) { cfg = cfg.copy(front = it); onClockChanged() })
        root.addView(pill("Posição automática") { autoPosition() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        // ---------- Cor ----------
        root.addView(section("Cor"))
        val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sw.addView(pill("Auto") { autoColor() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(10) })
        swatches.forEach { col ->
            sw.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(col); setStroke(dp(1), Color.argb(90, 255, 255, 255))
                }
                setOnClickListener { cfg = cfg.copy(color = col); onClockChanged() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10); gravity = Gravity.CENTER_VERTICAL })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sw) })
        slider(root, "Tom personalizado", 0, 360, 0, { "${it}°" }) {
            cfg = cfg.copy(color = Color.HSVToColor(floatArrayOf(it.toFloat(), 0.65f, 1f))); onClockChanged()
        }

        // ---------- Efeito 3D ----------
        root.addView(section("Efeito 3D"))
        root.addView(check("Profundidade com a inclinação", cfg.parallax) { cfg = cfg.copy(parallax = it); onEffectChanged() })
        slider(root, "Intensidade", 0, 100, ((cfg.intensity - 0.25f) / 3.75f * 100).roundToInt(),
            { "%.1f×".format(0.25f + it / 100f * 3.75f) }) {
            cfg = cfg.copy(intensity = 0.25f + it / 100f * 3.75f); onEffectChanged()
        }
        slider(root, "Separação de planos", 0, 100, (cfg.separation * 100).roundToInt(), { "$it%" }) {
            cfg = cfg.copy(separation = it / 100f); onEffectChanged()
        }
        root.addView(label("Ponto fixo (o que fica parado)"))
        root.addView(chips(listOf("Relógio", "Sujeito", "Fundo"), { cfg.focus }) { cfg = cfg.copy(focus = it); onEffectChanged() })
        slider(root, "Suavidade das bordas", 0, 100, (cfg.smooth * 100).roundToInt(), { "$it%" }) {
            cfg = cfg.copy(smooth = it / 100f); onEffectChanged()
        }
        root.addView(check("Inverter direção", cfg.invert) { cfg = cfg.copy(invert = it); onEffectChanged() })
        root.addView(check("Movimento automático (balança sozinho)", cfg.autoMotion) { cfg = cfg.copy(autoMotion = it) })

        // ---------- Ecrã inicial ----------
        root.addView(section("Ecrã inicial"))
        root.addView(check("Mostrar o relógio também no ecrã inicial", cfg.showOnHome) { cfg = cfg.copy(showOnHome = it) })

        applyBtn = Button(this).apply {
            text = "Aplicar como fundo"; isAllCaps = false; textSize = 16f
            setTextColor(Color.rgb(10, 20, 30))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(accent) }
            setOnClickListener { saveAndApply() }
        }
        root.addView(applyBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(24) })

        root.addView(TextView(this).apply {
            text = "No Good Lock → LockStar, usa um relógio só com espaços para esconder o da Samsung. " +
                "Aplica o fundo ao ecrã de bloqueio e ao inicial."
            textSize = 12f; setTextColor(mutedColor); setPadding(0, dp(14), 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(bgColor); addView(root) })
    }

    private fun section(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.08f; setTextColor(mutedColor)
        setPadding(0, dp(26), 0, dp(8))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(textColor); setPadding(0, dp(10), 0, dp(6))
    }

    private fun pill(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; setTextColor(textColor)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(cardColor) }
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun check(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t; setTextColor(textColor); isChecked = initial
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun chipRow(labels: List<String>, current: () -> Int, fixed: Boolean, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = labels.mapIndexed { i, l ->
            Button(this).apply {
                text = l; isAllCaps = false; textSize = 14f
                if (fixed) setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { onPick(i); refreshChips() }
            }
        }
        buttons.forEach {
            val lp = if (fixed) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42))
                     else LinearLayout.LayoutParams(0, dp(42), 1f)
            row.addView(it, lp.apply { rightMargin = dp(8); bottomMargin = dp(8) })
        }
        chipGroups.add(buttons to current)
        refreshChips()
        return row
    }

    private fun chips(labels: List<String>, current: () -> Int, onPick: (Int) -> Unit) = chipRow(labels, current, false, onPick)

    private fun scrollChips(labels: List<String>, current: () -> Int, onPick: (Int) -> Unit) =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow(labels, current, true, onPick))
        }

    private fun refreshChips() {
        chipGroups.forEach { (buttons, current) ->
            val sel = current()
            buttons.forEachIndexed { i, b ->
                val on = i == sel
                b.setTextColor(if (on) Color.rgb(10, 20, 30) else textColor)
                b.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (on) accent else cardColor) }
            }
        }
    }

    private fun slider(root: LinearLayout, label: String, min: Int, max: Int, value: Int,
                       fmt: (Int) -> String, onChange: (Int) -> Unit): SeekBar {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply { text = label; setTextColor(textColor); textSize = 14f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val out = TextView(this).apply { text = fmt(value.coerceIn(min, max)); setTextColor(mutedColor); textSize = 14f }
        head.addView(out)
        root.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    out.text = fmt(p + min); if (fromUser) onChange(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(bar)
        return bar
    }

    private fun onClockChanged() { updateCoverage(); if (previewMesh == null || !cfg.parallax) renderPreview(false) }
    private fun onEffectChanged() { prepareSmall(); updateCoverage(); startAnim() }

    // ================= Fotos =================

    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            screenW = resources.displayMetrics.widthPixels; screenH = resources.displayMetrics.heightPixels
        }
    }

    private fun loadSaved() {
        if (Store.mode(Store.prefs(this)) == Store.MODE_VIDEO && showVideoPreview(Store.videoDir(this))) {
            status.text = "Vídeo atual carregado. Ajusta e aplica."
            return
        }
        val bf = Store.bgFile(this); val ff = Store.fgFile(this)
        if (bf.exists() && ff.exists()) {
            bg = BitmapFactory.decodeFile(bf.path)
            fg = BitmapFactory.decodeFile(ff.path)
            Store.depthFile(this).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.let { db ->
                val d = DepthEstimator.fromBitmap(db)
                photoDepth = DepthEstimator.Result(db.width, db.height, d, 0)
                previewMesh = DepthMesh.forPhoto(d, db.width, db.height, fg)
            }
            prepareSmall()
            status.text = "Foto atual carregada. Ajusta e aplica."
            updateCoverage()
            startAnim()
        }
    }

    private fun importPhoto(uri: Uri) {
        busy = true
        status.text = "A recortar o sujeito…"
        Thread {
            val bmp = try {
                decodeLimited(uri, 2400).let {
                    if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false)
                }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (bmp == null) { busy = false; status.text = "Não consegui abrir a foto."; return@runOnUiThread }
                segmenter.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { result ->
                        val cut = result.foregroundBitmap
                        if (cut == null) {
                            busy = false
                            status.text = "Não encontrei nenhum sujeito nesta foto. Tenta outra."
                        } else {
                            bg = bmp; fg = cut; mask = null; avgMask = null
                            pendingMode = Store.MODE_PHOTO
                            photoDepth = null; previewMesh = null
                            prepareSmall()
                            autoPosition()
                            computePhotoDepth(bmp, cut)
                        }
                    }
                    .addOnFailureListener { e ->
                        busy = false
                        status.text = "Falhou o recorte: ${e.message}\n" +
                            "Se for a primeira vez, o modelo pode ainda estar a descarregar — tenta daqui a um minuto."
                    }
            }
        }.start()
    }

    /** Profundidade logo ao importar → a pré-visualização já mostra o 3D. */
    private fun computePhotoDepth(photo: Bitmap, cut: Bitmap) {
        status.text = "A calcular a profundidade…"
        Thread {
            val msg = try {
                val r = DepthEstimator(this).use { it.estimate(photo) }
                val mesh = DepthMesh.forPhoto(r.d, r.w, r.h, cut)
                runOnUiThread { photoDepth = r; previewMesh = mesh }
                "Profundidade 3D pronta (${r.ms} ms). Ajusta e aplica."
            } catch (e: Throwable) {
                "Profundidade indisponível (${e.javaClass.simpleName}: ${e.message}). Vai usar 3 camadas."
            }
            runOnUiThread {
                busy = false
                depthMsg = msg
                status.text = msg
                startAnim()
            }
        }.start()
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

    // ================= Vídeo =================

    private fun importVideo(uri: Uri) {
        busy = true
        status.text = "A preparar o vídeo…"
        val out = Store.videoNewDir(this)
        Thread {
            try {
                val r = VideoProcessor.process(this, uri, out) { done, total ->
                    runOnUiThread { status.text = "A recortar e medir a profundidade… $done/$total" }
                }
                runOnUiThread {
                    busy = false
                    if (showVideoPreview(r.dir)) {
                        pendingMode = Store.MODE_VIDEO
                        autoPosition()
                        depthMsg = r.depthMsg
                        status.text = "Vídeo pronto: ${r.frames} frames a ${r.fps.roundToInt()} fps. ${r.depthMsg}"
                    } else {
                        status.text = "O vídeo foi processado mas não consegui abri-lo."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    status.text = "Falhou o vídeo: ${e.message}"
                }
            }
        }.start()
    }

    /** Pré-visualização com o 1.º frame do clipe (e a sua profundidade, se houver). */
    private fun showVideoPreview(dir: File): Boolean {
        val clip = VideoClip.open(dir) ?: return false
        if (!clip.load(0)) { clip.release(); return false }
        bg = clip.currentFrame
        mask = clip.mask
        fg = null
        avgMask = BitmapFactory.decodeFile(VideoClip.avgFile(dir).path)
        previewMesh = if (clip.hasDepth) DepthMesh().also { clip.applyDepth(0, it) } else null
        photoDepth = null
        clip.release()
        prepareSmall()
        updateCoverage()
        startAnim()
        return true
    }

    // ================= Pré-visualização =================

    private fun prepareSmall() {
        val f = (if (mask != null) avgMask else fg) ?: return
        val w = 270
        val h = (w * screenH.toFloat() / screenW).toInt()
        fgSmall = DepthRenderer.fgForScreen(f, w, h, DepthRenderer.zoomFor(cfg))
    }

    private fun autoPosition() {
        val small = fgSmall ?: return
        val y = DepthRenderer.autoY(small, cfg.copy(front = false), Date(), DateFormat.is24HourFormat(this))
        if (y != null) {
            cfg = cfg.copy(y = y)
            posBar.progress = ((y - 0.05f) / 0.6f * 100).roundToInt().coerceIn(0, 100)
        }
        updateCoverage()
        renderPreview(false)
    }

    private fun autoColor() {
        val b = bg ?: return
        val p = Palette.from(b).maximumColorCount(16).generate()
        val col = p.lightVibrantSwatch?.rgb ?: p.vibrantSwatch?.rgb ?: p.lightMutedSwatch?.rgb ?: p.dominantSwatch?.rgb
        if (col != null) { cfg = cfg.copy(color = col); onClockChanged() }
    }

    private fun updateCoverage() {
        val small = fgSmall
        if (small == null) { coverageText.text = ""; return }
        if (cfg.front) { coverageText.text = "Relógio à frente do sujeito — sempre visível."; return }
        val cov = DepthRenderer.coverage(small, cfg, Date(), DateFormat.is24HourFormat(this))
        val pct = (cov * 100).roundToInt()
        coverageText.text = when {
            cov < 0.05f -> "O sujeito quase não toca no relógio ($pct%) — não há efeito."
            cov > 0.60f -> "O sujeito tapa $pct% do relógio. Experimenta \"Relógio à frente do sujeito\"."
            cov > 0.45f -> "O sujeito tapa $pct% do relógio — a hora fica difícil de ler."
            else -> "O sujeito tapa $pct% do relógio. Bom equilíbrio."
        }
    }

    private fun renderPreview(animated: Boolean) {
        val w = screenW / 3
        val h = screenH / 3
        val out = previewBmp?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { previewBmp = it }
        var tx = 0f; var ty = 0f
        if (animated) {
            val t = (SystemClock.uptimeMillis() - animStart) / 1000f
            tx = 0.8f * sin(t * 1.1f)
            ty = 0.35f * sin(t * 0.7f + 1.2f)
        }
        DepthRenderer.draw(
            Canvas(out), bg, fg, cfg, showClock = true, now = Date(),
            is24 = DateFormat.is24HourFormat(this), tiltX = tx, tiltY = ty,
            mask = mask, depth = previewMesh
        )
        preview.setImageBitmap(out)
        preview.invalidate()
    }

    // ================= Guardar e aplicar =================

    private fun saveAndApply() {
        if (busy) { status.text = "Espera que termine o processamento."; return }
        if (pendingMode == Store.MODE_VIDEO || (mask != null && fg == null)) { saveVideoAndApply(); return }
        val b = bg ?: return
        val f = fg ?: return
        val depth = photoDepth
        busy = true
        status.text = "A guardar…"
        Thread {
            writeAtomic(Store.bgFile(this)) { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            writeAtomic(Store.fgFile(this)) { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (depth != null) {
                val db = DepthEstimator.toBitmap(depth.d, depth.w, depth.h)
                writeAtomic(Store.depthFile(this)) { db.compress(Bitmap.CompressFormat.PNG, 100, it) }
                db.recycle()
                Store.bgFillFile(this).delete()
            } else {
                // Sem profundidade: plano B com o fundo preenchido (3 camadas)
                Store.depthFile(this).delete()
                try {
                    val filled = BackgroundFill.fill(b, f)
                    writeAtomic(Store.bgFillFile(this)) { filled.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    filled.recycle()
                } catch (_: Throwable) { Store.bgFillFile(this).delete() }
            }
            val p = Store.prefs(this)
            cfg.save(p)
            p.edit().putString("mode", Store.MODE_PHOTO).putLong("version", System.currentTimeMillis()).apply()
            runOnUiThread {
                busy = false
                status.text = "Guardado."
                openWallpaperPicker()
            }
        }.start()
    }

    private fun saveVideoAndApply() {
        busy = true
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
                busy = false
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
            status.text = "Fundo atualizado. $depthMsg"
            return
        }
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, DepthWallpaperService::class.java)
        )
        try { startActivity(intent) } catch (_: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
