package pt.filipe.profundo

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.Date
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ecrã de personalização do túnel infinito, com pré-visualização animada. */
class InfinityActivity : ComponentActivity() {

    private lateinit var inf: InfinityConfig
    private lateinit var clock: ClockConfig
    private lateinit var preview: PreviewView
    private val chipGroups = mutableListOf<Pair<List<Button>, () -> Int>>()

    private val bgColor = Color.rgb(14, 14, 16)
    private val cardColor = Color.rgb(26, 26, 30)
    private val textColor = Color.rgb(228, 228, 232)
    private val mutedColor = Color.rgb(140, 140, 150)
    private val accent = Color.rgb(120, 190, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Store.prefs(this)
        inf = InfinityConfig.load(p)
        clock = ClockConfig.load(p)
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
            setBackgroundColor(bgColor)
        }
        root.addView(TextView(this).apply {
            text = "Túnel infinito"; textSize = 24f; setTextColor(textColor)
        })
        root.addView(TextView(this).apply {
            text = "Inclina o telemóvel na pré-visualização para ver a profundidade."
            textSize = 13f; setTextColor(mutedColor); setPadding(0, dp(4), 0, dp(12))
        })

        // Pré-visualização com a proporção do ecrã
        val (sw, sh) = screenSize()
        val pw = resources.displayMetrics.widthPixels - dp(32)
        val ph = (pw * 0.62f * sh / sw).toInt()
        preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams((ph * sw / sh.toFloat()).toInt(), ph).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Forma
        root.addView(section("Forma"))
        root.addView(chips(listOf("Ecrã", "Círculo", "Hexágono"), { inf.shape }) { inf = inf.copy(shape = it) })

        // LEDs
        root.addView(section("LEDs"))
        root.addView(chips(listOf("Pontos", "Linha", "Ambos"), { inf.leds }) { inf = inf.copy(leds = it) })
        root.addView(check("Brilho suave", inf.glow) { inf = inf.copy(glow = it) })

        // Cor
        root.addView(section("Cor"))
        val presets = listOf(
            "Gelo" to (195f to 0.85f), "Âmbar" to (34f to 0.9f), "Magenta" to (310f to 0.8f),
            "Esmeralda" to (150f to 0.8f), "Branco" to (0f to 0f)
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { (name, hs) ->
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.HSVToColor(floatArrayOf(hs.first, hs.second, 1f)))
                    setStroke(dp(1), Color.argb(80, 255, 255, 255))
                }
                contentDescription = name
                setOnClickListener {
                    inf = inf.copy(hue = hs.first, saturation = hs.second, colorCycle = false)
                    hueBar?.progress = hs.first.roundToInt()
                    cycleBox?.isChecked = false
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(14) })
        }
        root.addView(row)
        hueBar = slider(root, "Tom", 0, 360, inf.hue.roundToInt(), { "${it}°" }) {
            inf = inf.copy(hue = it.toFloat(), saturation = if (inf.saturation == 0f) 0.85f else inf.saturation)
        }
        val cycle = check("Ciclo de cor em profundidade", inf.colorCycle) { inf = inf.copy(colorCycle = it) }
        cycleBox = cycle
        root.addView(cycle)

        // Profundidade
        root.addView(section("Profundidade"))
        slider(root, "Reflexos", 50, 95, (inf.reflect * 100).roundToInt(), { "$it%" }) {
            inf = inf.copy(reflect = it / 100f)
        }
        slider(root, "Espaçamento", 84, 95, (inf.spacing * 100).roundToInt(), { "$it" }) {
            inf = inf.copy(spacing = it / 100f)
        }
        slider(root, "LEDs a correr", 0, 100, (inf.speed * 100).roundToInt(), { if (it == 0) "parado" else "$it" }) {
            inf = inf.copy(speed = it / 100f)
        }

        // Relógio
        root.addView(section("Relógio"))
        slider(root, "Afundado", 0, 5, inf.clockDepth, { if (it == 0) "à frente" else "$it anéis" }) {
            inf = inf.copy(clockDepth = it)
        }

        root.addView(Button(this).apply {
            text = "Aplicar túnel"
            setTextColor(Color.rgb(10, 20, 30))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(accent) }
            setOnClickListener { apply() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(24) })

        setContentView(ScrollView(this).apply { setBackgroundColor(bgColor); addView(root) })
    }

    private var hueBar: SeekBar? = null
    private var cycleBox: CheckBox? = null

    private fun section(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.08f; setTextColor(mutedColor)
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun check(t: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t; setTextColor(textColor); isChecked = initial
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun chips(labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = labels.mapIndexed { i, label ->
            Button(this).apply {
                text = label; isAllCaps = false; textSize = 14f
                setOnClickListener { onPick(i); refreshChips() }
            }
        }
        buttons.forEach {
            row.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        }
        chipGroups.add(buttons to current)
        refreshChips()
        return row
    }

    private fun refreshChips() {
        chipGroups.forEach { (buttons, current) ->
            val sel = current()
            buttons.forEachIndexed { i, b ->
                val on = i == sel
                b.setTextColor(if (on) Color.rgb(10, 20, 30) else textColor)
                b.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (on) accent else cardColor)
                }
            }
        }
    }

    private fun slider(
        root: LinearLayout, label: String, min: Int, max: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ): SeekBar {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply { text = label; setTextColor(textColor); textSize = 14f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val out = TextView(this).apply { text = fmt(value); setTextColor(mutedColor); textSize = 14f }
        head.addView(out)
        root.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    out.text = fmt(p + min)
                    if (fromUser) onChange(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(bar)
        return bar
    }

    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        b.width() to b.height()
    } else resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels

    private fun apply() {
        val p = Store.prefs(this)
        inf.save(p)
        p.edit().putString("mode", Store.MODE_INFINITY).putLong("version", System.currentTimeMillis()).apply()
        val current = WallpaperManager.getInstance(this).wallpaperInfo
        if (current?.packageName == packageName) {
            finish(); return
        }
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, DepthWallpaperService::class.java)
        )
        try { startActivity(intent) } catch (_: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    /** Pré-visualização animada; balança sozinha para simular a inclinação. */
    inner class PreviewView(ctx: Context) : View(ctx) {
        private val start = SystemClock.uptimeMillis()
        init {
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.BLACK) }
            clipToOutline = true
        }
        override fun onDraw(canvas: Canvas) {
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            val tx = sin(t / 1.7f) * 0.6f
            val ty = cos(t / 2.3f) * 0.4f
            InfinityRenderer.draw(
                canvas, inf, clock, showClock = true, now = Date(),
                is24 = DateFormat.is24HourFormat(context), tiltX = tx, tiltY = ty, timeSec = t
            )
            postInvalidateOnAnimation()
        }
    }
}
