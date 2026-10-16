package pt.filipe.profundo

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
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

    // ---- Conteúdo (versões completas: são as que se guardam) ----
    private var bgFull: Bitmap? = null
    private var fgFull: Bitmap? = null
    private var photoDepth: DepthEstimator.Result? = null

    // ---- Versões leves para a pré-visualização ----
    private var bgPrev: Bitmap? = null
    private var fgPrev: Bitmap? = null
    private var maskPrev: Bitmap? = null      // vídeo: máscara do 1.º frame
    private var avgMask: Bitmap? = null       // vídeo: máscara média
    private var previewMesh: DepthMesh? = null
    private var meter: CoverageMeter? = null

    private var pendingMode = Store.MODE_PHOTO
    private var busy = false
    private lateinit var cfg: ClockConfig
    private var depthMsg = ""
    private var is24 = true

    private var screenW = 1080
    private var screenH = 2340

    // ---- UI ----
    private lateinit var status: TextView
    private lateinit var coverageText: TextView
    private lateinit var preview: PreviewView
    private lateinit var progress: ProgressBar
    private lateinit var posBar: SeekBar
    private lateinit var applyBtn: TextView
    private lateinit var panelHost: ScrollView
    private val panels = mutableListOf<View>()
    private val tabs = mutableListOf<TextView>()
    private val chipGroups = mutableListOf<Pair<List<TextView>, () -> Int>>()
    private val swatchViews = mutableListOf<Pair<View, Int>>()

    // ---- Cores do tema ----
    private val bgColor = Color.rgb(11, 11, 13)
    private val surface = Color.rgb(22, 22, 26)
    private val surface2 = Color.rgb(32, 32, 38)
    private val textColor = Color.rgb(236, 236, 240)
    private val mutedColor = Color.rgb(142, 142, 152)
    private val accent = Color.rgb(122, 186, 255)
    private val onAccent = Color.rgb(8, 18, 30)

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

    private val ui = Handler(Looper.getMainLooper())
    private val coverageRunnable = Runnable { updateCoverageNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readScreenSize()
        cfg = ClockConfig.load(Store.prefs(this))
        is24 = DateFormat.is24HourFormat(this)
        window.statusBarColor = bgColor
        window.navigationBarColor = surface
        buildUi()
        loadSavedAsync()
    }

    override fun onResume() { super.onResume(); preview.running = true; preview.invalidate() }
    override fun onPause() { super.onPause(); preview.running = false }

    // ================= Pré-visualização =================

    /** Desenha diretamente no ecrã (acelerado por hardware) — não cria bitmaps a cada frame. */
    inner class PreviewView(ctx: Context) : View(ctx) {
        private val painter = ClockPainter()
        private val start = SystemClock.uptimeMillis()
        var running = true

        init {
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.BLACK) }
            clipToOutline = true
        }

        private fun animating() = running && previewMesh != null && cfg.parallax

        override fun onDraw(canvas: Canvas) {
            val bg = bgPrev
            if (bg == null) {
                painter.draw(canvas, cfg, Date(), is24, withDate = true)
                return
            }
            var tx = 0f; var ty = 0f
            if (animating()) {
                val t = (SystemClock.uptimeMillis() - start) / 1000f
                tx = 0.8f * sin(t * 1.1f)
                ty = 0.35f * sin(t * 0.7f + 1.2f)
            }
            DepthRenderer.draw(
                canvas, bg, fgPrev, cfg, painter, showClock = true, now = Date(),
                is24 = is24, tiltX = tx, tiltY = ty, mask = maskPrev, depth = previewMesh
            )
            if (animating()) postInvalidateOnAnimation()
        }
    }

    // ================= UI =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun haptic(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }
        // Edge-to-edge (Android 15): respeita a barra de estado e a de navegação
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        // ---------- Topo: título + escolher ----------
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(12), dp(6))
        }
        top.addView(TextView(this).apply {
            text = "Profundo"; textSize = 22f; setTextColor(textColor)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("Foto") {
            if (!busy) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(8) })
        top.addView(pill("Vídeo") {
            if (!busy) pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        root.addView(top)

        // ---------- Pré-visualização fixa ----------
        val ph = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        val pw = (ph * screenW / screenH.toFloat()).toInt()
        val previewBox = FrameLayout(this)
        preview = PreviewView(this)
        previewBox.addView(preview, FrameLayout.LayoutParams(pw, ph, Gravity.CENTER))
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        previewBox.addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        root.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ph).apply { topMargin = dp(4) })

        status = TextView(this).apply {
            text = "A carregar…"; textSize = 12.5f; setTextColor(mutedColor)
            gravity = Gravity.CENTER; setPadding(dp(18), dp(8), dp(18), 0)
        }
        root.addView(status)
        coverageText = TextView(this).apply {
            textSize = 12.5f; setTextColor(accent); gravity = Gravity.CENTER; setPadding(dp(18), dp(2), dp(18), dp(6))
        }
        root.addView(coverageText)

        // ---------- Separadores ----------
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(surface, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("Relógio", "Cor", "Efeito 3D", "Mais").forEachIndexed { i, t ->
            val tab = TextView(this).apply {
                text = t; textSize = 13.5f; gravity = Gravity.CENTER
                setOnClickListener { haptic(it); selectTab(i) }
            }
            tabs.add(tab)
            tabBar.addView(tab, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        root.addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(4)
        })

        // ---------- Painéis (só este bloco faz scroll) ----------
        panelHost = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val panelsBox = FrameLayout(this)
        panels.add(clockPanel()); panels.add(colorPanel()); panels.add(effectPanel()); panels.add(morePanel())
        panels.forEach { panelsBox.addView(it) }
        panelHost.addView(panelsBox)
        root.addView(panelHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---------- Botão fixo em baixo ----------
        val bottom = FrameLayout(this).apply { setBackgroundColor(surface); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        applyBtn = TextView(this).apply {
            text = "Aplicar como fundo"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(onAccent)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
            background = rounded(accent, 16)
            setOnClickListener { haptic(it); saveAndApply() }
        }
        bottom.addView(applyBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(bottom)

        setContentView(root)
        selectTab(0)
    }

    private fun selectTab(i: Int) {
        panels.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t ->
            val on = k == i
            t.setTextColor(if (on) onAccent else mutedColor)
            t.background = if (on) rounded(accent, 11) else null
        }
        panelHost.scrollTo(0, 0)
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(24))
    }

    private fun clockPanel() = panel().apply {
        addView(section("Estilo"))
        addView(chips(listOf("Horizontal", "Vertical", "Analógico"), { cfg.style }) { cfg = cfg.copy(style = it); onClockChanged() })
        addView(section("Letra"))
        addView(scrollChips(listOf("Moderno", "Fino", "Condensado", "Clássico", "Mono"), { cfg.font }) { cfg = cfg.copy(font = it); onClockChanged() })
        addView(section("Data"))
        addView(chips(listOf("Acima", "Abaixo", "Sem data"), { cfg.dateMode }) { cfg = cfg.copy(dateMode = it); onClockChanged() })
        addView(section("Posição e tamanho"))
        posBar = slider(this, "Posição", 0, 100, ((cfg.y - 0.05f) / 0.6f * 100).roundToInt(), { "$it" }) {
            cfg = cfg.copy(y = 0.05f + it / 100f * 0.6f); onClockChanged()
        }
        slider(this, "Tamanho", 0, 100, ((cfg.size - 0.12f) / 0.30f * 100).roundToInt(), { "$it" }) {
            cfg = cfg.copy(size = 0.12f + it / 100f * 0.30f); onClockChanged()
        }
        slider(this, "Opacidade", 30, 100, (cfg.opacity * 100).roundToInt(), { "$it%" }) {
            cfg = cfg.copy(opacity = it / 100f); onClockChanged()
        }
        addView(pill("Posição automática") { autoPosition() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        addView(section("Acabamento"))
        addView(toggle("Relógio à frente do sujeito", cfg.front) { cfg = cfg.copy(front = it); onClockChanged() })
        addView(toggle("Só contorno", cfg.outline) { cfg = cfg.copy(outline = it); onClockChanged() })
        addView(toggle("Sombra suave", cfg.shadow) { cfg = cfg.copy(shadow = it); onClockChanged() })
    }

    private fun colorPanel() = panel().apply {
        addView(section("Cores"))
        val grid = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        swatches.toList().chunked(6).forEach { rowColors ->
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { col ->
                val v = View(this@MainActivity).apply {
                    setOnClickListener { haptic(it); cfg = cfg.copy(color = col); onClockChanged(); refreshSwatches() }
                }
                swatchViews.add(v to col)
                row.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            grid.addView(row)
        }
        addView(grid)
        refreshSwatches()
        addView(pill("Cor automática a partir da foto") { autoColor() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        addView(section("Tom personalizado"))
        slider(this, "Tom", 0, 360, 0, { "${it}°" }) {
            cfg = cfg.copy(color = Color.HSVToColor(floatArrayOf(it.toFloat(), 0.65f, 1f))); onClockChanged(); refreshSwatches()
        }
    }

    private fun effectPanel() = panel().apply {
        addView(section("Profundidade"))
        addView(toggle("Efeito 3D com a inclinação", cfg.parallax) { cfg = cfg.copy(parallax = it); onEffectChanged() })
        slider(this, "Intensidade", 0, 100, ((cfg.intensity - 0.25f) / 3.75f * 100).roundToInt(),
            { "%.1f×".format(0.25f + it / 100f * 3.75f) }) {
            cfg = cfg.copy(intensity = 0.25f + it / 100f * 3.75f); onEffectChanged()
        }
        slider(this, "Separação de planos", 0, 100, (cfg.separation * 100).roundToInt(), { "$it%" }) {
            cfg = cfg.copy(separation = it / 100f); onEffectChanged()
        }
        slider(this, "Suavidade das bordas", 0, 100, (cfg.smooth * 100).roundToInt(), { "$it%" }) {
            cfg = cfg.copy(smooth = it / 100f); onEffectChanged()
        }
        addView(section("Ponto fixo"))
        addView(chips(listOf("Relógio", "Sujeito", "Fundo"), { cfg.focus }) { cfg = cfg.copy(focus = it); onEffectChanged() })
        addView(section("Movimento"))
        addView(toggle("Inverter direção", cfg.invert) { cfg = cfg.copy(invert = it); onEffectChanged() })
        addView(toggle("Balançar sozinho", cfg.autoMotion) { cfg = cfg.copy(autoMotion = it) })
    }

    private fun morePanel() = panel().apply {
        addView(section("Ecrã inicial"))
        addView(toggle("Mostrar o relógio no ecrã inicial", cfg.showOnHome) { cfg = cfg.copy(showOnHome = it) })
        addView(toggle("Ecrã inicial parado (poupa bateria)", cfg.homeStatic) { cfg = cfg.copy(homeStatic = it) })
        addView(hint("O efeito 3D continua no ecrã de bloqueio. No ecrã inicial a foto fica fixa e o sensor desliga-se."))
        addView(section("Armazenamento"))
        val used = TextView(this@MainActivity).apply { textSize = 14f; setTextColor(textColor) }
        addView(used)
        Thread { val mb = Store.usedMb(this@MainActivity); runOnUiThread { used.text = "A app ocupa %.0f MB".format(mb) } }.start()
        addView(pill("Apagar o que não está em uso") {
            if (busy) return@pill
            Thread {
                val freed = Store.cleanup(this@MainActivity)
                val mb = Store.usedMb(this@MainActivity)
                runOnUiThread { used.text = "A app ocupa %.0f MB (libertados %.0f MB)".format(mb, freed) }
            }.start()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        addView(section("Samsung"))
        addView(hint("No Good Lock → LockStar, usa um relógio só com espaços para esconder o da Samsung. " +
            "Aplica o fundo ao ecrã de bloqueio e ao inicial."))
    }

    // ---------- componentes ----------

    private fun section(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 11.5f; letterSpacing = 0.1f; setTextColor(mutedColor)
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 12.5f; setTextColor(mutedColor); setPadding(0, dp(6), 0, dp(4))
    }

    private fun pill(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; setTextColor(textColor)
        background = rounded(surface2, 12)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { haptic(it); onClick() }
    }

    private fun toggle(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@MainActivity).apply { text = t; textSize = 14.5f; setTextColor(textColor) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this@MainActivity).apply {
            isChecked = initial
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, Color.rgb(200, 200, 208)))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.argb(120, 122, 186, 255), Color.rgb(70, 70, 80)))
            setOnCheckedChangeListener { v, c -> haptic(v); onChange(c) }
        }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    private fun chipRow(labels: List<String>, current: () -> Int, fixed: Boolean, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = labels.mapIndexed { i, l ->
            TextView(this).apply {
                text = l; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { haptic(it); onPick(i); refreshChips() }
            }
        }
        buttons.forEach {
            val lp = if (fixed) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
                     else LinearLayout.LayoutParams(0, dp(40), 1f)
            row.addView(it, lp.apply { rightMargin = dp(8) })
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
                b.setTextColor(if (on) onAccent else textColor)
                b.background = rounded(if (on) accent else surface2, 11)
            }
        }
    }

    private fun refreshSwatches() {
        swatchViews.forEach { (v, col) ->
            val on = col == cfg.color
            v.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(col)
                setStroke(dp(if (on) 3 else 1), if (on) accent else Color.argb(60, 255, 255, 255))
            }
        }
    }

    private fun slider(root: LinearLayout, label: String, min: Int, max: Int, value: Int,
                       fmt: (Int) -> String, onChange: (Int) -> Unit): SeekBar {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply { text = label; setTextColor(textColor); textSize = 14.5f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val out = TextView(this).apply { text = fmt(value.coerceIn(min, max)); setTextColor(mutedColor); textSize = 14f }
        head.addView(out)
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(70, 70, 80))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    out.text = fmt(p + min); if (fromUser) onChange(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        return bar
    }

    private fun setBusy(b: Boolean, msg: String? = null) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        applyBtn.alpha = if (b) 0.5f else 1f
        if (msg != null) status.text = msg
    }

    private fun onClockChanged() { scheduleCoverage(); preview.invalidate() }
    private fun onEffectChanged() { rebuildMeter(); scheduleCoverage(); preview.invalidate() }

    // ================= Carregar o que já está aplicado =================

    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            screenW = resources.displayMetrics.widthPixels; screenH = resources.displayMetrics.heightPixels
        }
    }

    private val previewMaxSide get() = max(900, (resources.displayMetrics.heightPixels * 0.40f * 1.2f).toInt())

    /** Lê os ficheiros numa thread de fundo — a app abre logo, sem bloquear. */
    private fun loadSavedAsync() {
        setBusy(true, "A carregar…")
        Thread {
            try {
                if (Store.mode(Store.prefs(this)) == Store.MODE_VIDEO && loadVideoPreview(Store.videoDir(this))) {
                    runOnUiThread { setBusy(false, "Vídeo atual carregado."); afterContentChanged() }
                    return@Thread
                }
                val bf = Store.bgFile(this); val ff = Store.fgFile(this)
                if (bf.exists() && ff.exists()) {
                    val bg = BitmapFactory.decodeFile(bf.path)
                    val fg = BitmapFactory.decodeFile(ff.path)
                    val depth = Store.depthFile(this).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.let { db ->
                        DepthEstimator.Result(db.width, db.height, DepthEstimator.fromBitmap(db), 0).also { db.recycle() }
                    }
                    val mesh = depth?.let { DepthMesh.forPhoto(it.d, it.w, it.h, fg) }
                    val bgP = DepthRenderer.scaledDown(bg, previewMaxSide)
                    val fgP = DepthRenderer.scaledDown(fg, previewMaxSide)
                    runOnUiThread {
                        bgFull = bg; fgFull = fg; photoDepth = depth
                        bgPrev = bgP; fgPrev = fgP; maskPrev = null; avgMask = null; previewMesh = mesh
                        pendingMode = Store.MODE_PHOTO
                        setBusy(false, "Foto atual carregada.")
                        afterContentChanged()
                    }
                } else {
                    runOnUiThread { setBusy(false, "Escolhe uma foto ou um vídeo com um sujeito bem destacado.") }
                }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false, "Não consegui carregar o fundo atual (${e.javaClass.simpleName}). Escolhe outra foto.") }
            }
        }.start()
    }

    private fun afterContentChanged() {
        rebuildMeter()
        scheduleCoverage()
        preview.invalidate()
    }

    // ================= Fotos =================

    private fun importPhoto(uri: Uri) {
        setBusy(true, "A recortar o sujeito…")
        Thread {
            val bmp = try {
                decodeLimited(uri, 2400).let {
                    if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false)
                }
            } catch (e: Throwable) { null }
            if (bmp == null) { runOnUiThread { setBusy(false, "Não consegui abrir a foto.") }; return@Thread }
            segmenter.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    val cut = result.foregroundBitmap
                    if (cut == null) {
                        setBusy(false, "Não encontrei nenhum sujeito nesta foto. Tenta outra.")
                        return@addOnSuccessListener
                    }
                    bgFull = bmp; fgFull = cut; photoDepth = null
                    bgPrev = DepthRenderer.scaledDown(bmp, previewMaxSide)
                    fgPrev = DepthRenderer.scaledDown(cut, previewMaxSide)
                    maskPrev = null; avgMask = null; previewMesh = null
                    pendingMode = Store.MODE_PHOTO
                    rebuildMeter()
                    autoPosition()
                    computePhotoDepth(bmp, cut)
                }
                .addOnFailureListener { e ->
                    setBusy(false, "Falhou o recorte: ${e.message}. Se for a primeira vez, o modelo pode ainda estar a descarregar.")
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
                "Profundidade 3D pronta (${r.ms} ms)."
            } catch (e: Throwable) {
                "Profundidade indisponível (${e.javaClass.simpleName}). Vai usar 3 camadas."
            }
            runOnUiThread {
                depthMsg = msg
                setBusy(false, msg)
                preview.invalidate()
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
        setBusy(true, "A preparar o vídeo…")
        val out = Store.videoNewDir(this)
        Thread {
            try {
                val r = VideoProcessor.process(this, uri, out) { done, total ->
                    runOnUiThread { status.text = "A recortar e medir a profundidade… $done/$total" }
                }
                val ok = loadVideoPreview(r.dir)
                runOnUiThread {
                    if (ok) {
                        pendingMode = Store.MODE_VIDEO
                        depthMsg = r.depthMsg
                        setBusy(false, "Vídeo pronto: ${r.frames} frames a ${r.fps.roundToInt()} fps. ${r.depthMsg}")
                        afterContentChanged()
                        autoPosition()
                    } else setBusy(false, "O vídeo foi processado mas não consegui abri-lo.")
                }
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false, "Falhou o vídeo: ${e.message}") }
            }
        }.start()
    }

    /** Lê o 1.º frame do clipe (e a sua profundidade). Pode correr fora da thread principal. */
    private fun loadVideoPreview(dir: File): Boolean {
        val clip = VideoClip.open(dir) ?: return false
        try {
            if (!clip.load(0)) return false
            val frame = clip.currentFrame ?: return false
            val bgP = DepthRenderer.scaledDown(frame.copy(Bitmap.Config.ARGB_8888, false), previewMaxSide)
            val maskP = clip.mask.copy(Bitmap.Config.ALPHA_8, false)
            val avg = BitmapFactory.decodeFile(VideoClip.avgFile(dir).path)
            val mesh = if (clip.hasDepth) DepthMesh().also { clip.applyDepth(0, it) } else null
            runOnUiThread {
                bgFull = null; fgFull = null; photoDepth = null
                bgPrev = bgP; fgPrev = null; maskPrev = maskP; avgMask = avg; previewMesh = mesh
                pendingMode = Store.MODE_VIDEO
            }
            return true
        } finally {
            clip.release()
        }
    }

    // ================= Cobertura do relógio =================

    private fun rebuildMeter() {
        val f = (if (maskPrev != null) avgMask else fgPrev) ?: run { meter = null; return }
        val w = 200
        val h = (w * screenH.toFloat() / screenW).toInt()
        meter = CoverageMeter(DepthRenderer.fgForScreen(f, w, h, DepthRenderer.zoomFor(cfg)))
    }

    /** As barras disparam dezenas de vezes por segundo — mede só quando param. */
    private fun scheduleCoverage() {
        ui.removeCallbacks(coverageRunnable)
        ui.postDelayed(coverageRunnable, 120)
    }

    private fun updateCoverageNow() {
        val m = meter
        if (m == null) { coverageText.text = ""; return }
        if (cfg.front) { coverageText.text = "Relógio à frente do sujeito — sempre visível"; return }
        val cov = m.coverage(cfg, Date(), is24)
        val pct = (cov * 100).roundToInt()
        coverageText.text = when {
            cov < 0.05f -> "O sujeito quase não toca no relógio ($pct%)"
            cov > 0.60f -> "O sujeito tapa $pct% — experimenta \"Relógio à frente\""
            cov > 0.45f -> "O sujeito tapa $pct% — a hora fica difícil de ler"
            else -> "O sujeito tapa $pct% do relógio · bom equilíbrio"
        }
    }

    private fun autoPosition() {
        val m = meter ?: return
        val y = m.autoY(cfg.copy(front = false), Date(), is24)
        if (y != null) {
            cfg = cfg.copy(y = y)
            posBar.progress = ((y - 0.05f) / 0.6f * 100).roundToInt().coerceIn(0, 100)
        }
        scheduleCoverage()
        preview.invalidate()
    }

    private fun autoColor() {
        val b = bgPrev ?: return
        val p = Palette.from(b).maximumColorCount(16).generate()
        val col = p.lightVibrantSwatch?.rgb ?: p.vibrantSwatch?.rgb ?: p.lightMutedSwatch?.rgb ?: p.dominantSwatch?.rgb
        if (col != null) { cfg = cfg.copy(color = col); onClockChanged(); refreshSwatches() }
    }

    // ================= Guardar e aplicar =================

    private fun saveAndApply() {
        if (busy) { status.text = "Espera que termine o processamento."; return }
        if (pendingMode == Store.MODE_VIDEO) { saveVideoAndApply(); return }
        val b = bgFull ?: run { status.text = "Escolhe primeiro uma foto ou um vídeo."; return }
        val f = fgFull ?: return
        val depth = photoDepth
        setBusy(true, "A guardar…")
        Thread {
            try {
                writeAtomic(Store.bgFile(this)) { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                writeAtomic(Store.fgFile(this)) { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (depth != null) {
                    val db = DepthEstimator.toBitmap(depth.d, depth.w, depth.h)
                    writeAtomic(Store.depthFile(this)) { db.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    db.recycle()
                    Store.bgFillFile(this).delete()
                } else {
                    Store.depthFile(this).delete()
                    try {
                        val filled = BackgroundFill.fill(b, f)
                        writeAtomic(Store.bgFillFile(this)) { filled.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        filled.recycle()
                    } catch (_: Throwable) { Store.bgFillFile(this).delete() }
                }
                // O vídeo deixou de estar em uso
                Store.videoDir(this).deleteRecursively()
                commit(Store.MODE_PHOTO)
            } catch (e: Throwable) {
                runOnUiThread { setBusy(false, "Não consegui guardar (${e.javaClass.simpleName}).") }
            }
        }.start()
    }

    private fun saveVideoAndApply() {
        setBusy(true, "A guardar…")
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
            commit(Store.MODE_VIDEO)
        }.start()
    }

    private fun commit(mode: String) {
        val p = Store.prefs(this)
        cfg.save(p)
        p.edit().putString("mode", mode).putLong("version", System.currentTimeMillis()).apply()
        runOnUiThread {
            setBusy(false, "Guardado. $depthMsg")
            openWallpaperPicker()
        }
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
