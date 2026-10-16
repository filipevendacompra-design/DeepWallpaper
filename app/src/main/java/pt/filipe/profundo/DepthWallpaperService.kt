package pt.filipe.profundo

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Imagens da foto partilhadas por todas as instâncias do fundo no processo
 * (bloqueio, ecrã inicial, pré-visualizações do sistema) — uma cópia em memória em vez de várias.
 */
object PhotoCache {
    class Content(val version: Long, val bg: Bitmap?, val fg: Bitmap?, val fill: Bitmap?, val depth: FloatArray?, val dw: Int, val dh: Int)

    private var current: Content? = null

    @Synchronized
    fun get(ctx: Context, version: Long): Content {
        current?.let { if (it.version == version) return it }
        current = null   // larga a anterior antes de carregar a nova (o GC liberta-a quando ninguém a usar)
        val c = try {
            val bg = Store.bgFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            val fg = Store.fgFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            val depthBmp = Store.depthFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            // Com profundidade não é preciso o fundo preenchido — poupa memória
            val fill = if (depthBmp == null)
                Store.bgFillFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } else null
            val depth = depthBmp?.let { DepthEstimator.fromBitmap(it) }
            val c = Content(version, bg, fg, fill, depth, depthBmp?.width ?: 0, depthBmp?.height ?: 0)
            depthBmp?.recycle()
            c
        } catch (_: Throwable) {   // inclui OutOfMemoryError: mais vale um fundo preto do que travar o sistema
            Content(version, null, null, null, null, 0, 0)
        }
        current = c
        return c
    }
}

class DepthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DepthEngine()

    /**
     * Todo o desenho acontece numa única thread de render.
     * Foto: redesenha quando há motivo (minuto, desbloqueio, inclinação) ou em loop se houver movimento automático.
     * Vídeo: loop à cadência do clipe, só enquanto visível.
     */
    inner class DepthEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener,
        SensorEventListener {

        private val ctx = this@DepthWallpaperService
        private val prefs = Store.prefs(ctx)
        private val keyguard = getSystemService(KeyguardManager::class.java)

        private val renderThread = HandlerThread("profundo-render").apply { start() }
        private val render = Handler(renderThread.looper)

        // Estado (só mexido na thread de render)
        private var cfg = ClockConfig.load(prefs)
        private var mode = Store.mode(prefs)
        private var loadedVersion = -1L
        private var bg: Bitmap? = null
        private var fg: Bitmap? = null
        private var bgFill: Bitmap? = null
        private var depth: DepthMesh? = null
        private var clip: VideoClip? = null
        private val clipMesh = DepthMesh()
        private var frameIndex = 0
        private val start = SystemClock.uptimeMillis()
        @Volatile private var locked = true
        @Volatile private var surfaceOk = false
        private val painter = ClockPainter()
        private var is24 = true
        private var is24Checked = 0L

        /** O efeito (sensor, animação) só corre se estiver visível — e no ecrã inicial só se não for "parado". */
        private fun effectActive() = isVisible && (locked || isPreview || !cfg.homeStatic)

        // Paralaxe
        private val sensors = getSystemService(SensorManager::class.java)
        private val tiltSensor: Sensor? =
            sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private var listening = false
        private var hasBase = false
        private var baseX = 0f; private var baseY = 0f
        private var tiltX = 0f; private var tiltY = 0f
        private var drawnX = 0f; private var drawnY = 0f

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                locked = keyguard?.isKeyguardLocked ?: true
                render.post {
                    updateSensor()
                    restartLoops()
                    if (isVisible) drawFrame()
                }
            }
        }

        private val drawRunnable = Runnable { drawFrame() }

        private val videoLoop = object : Runnable {
            override fun run() {
                val c = clip ?: return
                if (!isVisible) return
                val t0 = SystemClock.uptimeMillis()
                if (c.load(frameIndex)) {
                    c.applyDepth(frameIndex, clipMesh)
                    drawFrame()
                }
                frameIndex = (frameIndex + 1) % c.count
                render.postAtTime(this, t0 + (1000f / max(1f, c.fps)).toLong())
            }
        }

        /** Foto com movimento automático: ~30 fps só enquanto visível. */
        private val photoLoop = object : Runnable {
            override fun run() {
                if (!effectActive() || clip != null || !animatePhoto()) return
                val t0 = SystemClock.uptimeMillis()
                drawFrame()
                render.postAtTime(this, t0 + 33)
            }
        }

        private fun animatePhoto() = cfg.parallax && cfg.autoMotion

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            render.post {
                stopSensor()
                render.removeCallbacksAndMessages(null)
                clip?.release(); clip = null
            }
            renderThread.quitSafely()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            locked = keyguard?.isKeyguardLocked ?: true
            render.post { onVisibilityRender(visible) }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            requestDraw()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceOk = true
        }

        /**
         * O Android exige que deixemos de desenhar ANTES de devolver o ecrã.
         * Como todo o desenho corre na thread de render, esperamos que ela confirme que parou.
         */
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceOk = false
            val done = CountDownLatch(1)
            render.post { stopLoops(); stopSensor(); done.countDown() }
            try { done.await(800, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            super.onSurfaceDestroyed(holder)
        }

        private val prefsChanged = Runnable {
            cfg = ClockConfig.load(prefs)
            ensureLoaded()
            updateSensor()
            restartLoops()
            if (isVisible) drawFrame()
        }

        /** Guardar as definições dispara uma notificação por chave — junta-as numa só. */
        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            render.removeCallbacks(prefsChanged)
            render.postDelayed(prefsChanged, 60)
        }

        // ---------- thread de render ----------

        private fun requestDraw() {
            render.removeCallbacks(drawRunnable)
            render.post(drawRunnable)
        }

        private fun stopLoops() {
            render.removeCallbacks(videoLoop)
            render.removeCallbacks(photoLoop)
        }

        private fun restartLoops() {
            stopLoops()
            if (!isVisible) return
            when {
                clip != null && effectActive() -> render.post(videoLoop)
                clip != null -> { if (clip?.load(frameIndex) == true) clip?.applyDepth(frameIndex, clipMesh) }
                animatePhoto() && effectActive() -> render.post(photoLoop)
            }
        }

        private fun onVisibilityRender(visible: Boolean) {
            ensureLoaded()
            updateSensor()
            stopLoops()
            if (!visible) return
            restartLoops()
            if (!(effectActive() && (clip != null || animatePhoto()))) drawFrame()
        }

        private fun ensureLoaded() {
            val version = prefs.getLong("version", 0L)
            if (version == loadedVersion) return
            loadedVersion = version
            mode = Store.mode(prefs)
            cfg = ClockConfig.load(prefs)
            clip?.release(); clip = null
            bg = null; fg = null; bgFill = null; depth = null
            if (mode == Store.MODE_VIDEO) {
                clip = try { VideoClip.open(Store.videoDir(ctx)) } catch (_: Throwable) { null }
                frameIndex = 0
            } else {
                val content = PhotoCache.get(ctx, version)
                bg = content.bg; fg = content.fg; bgFill = content.fill
                depth = try {
                    content.depth?.let { DepthMesh.forPhoto(it, content.dw, content.dh, fg) }
                } catch (_: Throwable) { null }
            }
        }

        private fun drawFrame() {
            if (!surfaceOk) return
            ensureLoaded()
            val holder = surfaceHolder ?: return
            val canvas = try { holder.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
            try {
                // Movimento automático: um balanço lento somado à inclinação
                val active = effectActive()
                var tx = if (active) tiltX else 0f
                var ty = if (active) tiltY else 0f
                if (active && cfg.parallax && cfg.autoMotion) {
                    val t = (SystemClock.uptimeMillis() - start) / 1000f
                    tx += 0.45f * sin(t * 0.55f)
                    ty += 0.30f * sin(t * 0.37f + 1.3f)
                }
                val c = clip
                val nowMs = System.currentTimeMillis()
                if (nowMs - is24Checked > 60_000L) { is24 = DateFormat.is24HourFormat(ctx); is24Checked = nowMs }
                DepthRenderer.draw(
                    canvas,
                    bg = if (c != null) c.currentFrame else bg,
                    fg = if (c != null) null else fg,
                    cfg = cfg,
                    painter = painter,
                    showClock = locked || cfg.showOnHome || isPreview,
                    now = Date(nowMs),
                    is24 = is24,
                    tiltX = tx.coerceIn(-1f, 1f), tiltY = ty.coerceIn(-1f, 1f),
                    mask = c?.mask,
                    bgFill = if (c != null) null else bgFill,
                    depth = if (c != null) (if (c.hasDepth) clipMesh else null) else depth
                )
                drawnX = tiltX; drawnY = tiltY
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }

        // ---------- paralaxe ----------

        private fun updateSensor() {
            val want = effectActive() && cfg.parallax && tiltSensor != null
            if (want && !listening) {
                hasBase = false
                listening = sensors!!.registerListener(this, tiltSensor, SensorManager.SENSOR_DELAY_GAME, render)
            } else if (!want && listening) {
                stopSensor()
            }
            if (!want) { tiltX = 0f; tiltY = 0f }
        }

        private fun stopSensor() {
            if (listening) sensors?.unregisterListener(this)
            listening = false
        }

        override fun onSensorChanged(e: SensorEvent) {
            val rawX = -e.values[0] / SensorManager.GRAVITY_EARTH
            val rawY = e.values[1] / SensorManager.GRAVITY_EARTH
            if (!hasBase) { baseX = rawX; baseY = rawY; hasBase = true }
            baseX += (rawX - baseX) * 0.02f
            baseY += (rawY - baseY) * 0.02f
            // ~12° de inclinação chegam para o efeito máximo
            val targetX = ((rawX - baseX) / 0.21f).coerceIn(-1f, 1f)
            val targetY = ((rawY - baseY) / 0.21f).coerceIn(-1f, 1f)
            tiltX += (targetX - tiltX) * 0.2f
            tiltY += (targetY - tiltY) * 0.2f
            // Com loops ativos (vídeo / movimento automático) o próprio loop redesenha
            if (clip == null && !animatePhoto() && effectActive() &&
                (abs(tiltX - drawnX) > 0.01f || abs(tiltY - drawnY) > 0.01f)) drawFrame()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}
