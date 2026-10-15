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
import kotlin.math.abs
import kotlin.math.max

class DepthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DepthEngine()

    /**
     * Todo o desenho acontece numa única thread de render (renderHandler).
     * Foto: redesenha quando há motivo (minuto, desbloqueio, inclinação).
     * Vídeo: loop contínuo à cadência do clipe, só enquanto o fundo está visível.
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
        private var frameIndex = 0
        @Volatile private var locked = true

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
                if (isVisible) requestDraw()
            }
        }

        private val drawRunnable = Runnable { drawFrame() }

        private val videoLoop = object : Runnable {
            override fun run() {
                val c = clip ?: return
                if (!isVisible) return
                val start = SystemClock.uptimeMillis()
                if (c.load(frameIndex)) drawFrame()
                frameIndex = (frameIndex + 1) % c.count
                val period = (1000f / max(1f, c.fps)).toLong()
                render.postAtTime(this, start + period)
            }
        }

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

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            render.post {
                render.removeCallbacks(videoLoop)
                stopSensor()
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            render.post {
                cfg = ClockConfig.load(prefs)
                ensureLoaded()
                updateSensor()
                restartLoopIfNeeded()
                if (isVisible) drawFrame()
            }
        }

        // ---------- thread de render ----------

        private fun requestDraw() {
            render.removeCallbacks(drawRunnable)
            render.post(drawRunnable)
        }

        private fun onVisibilityRender(visible: Boolean) {
            ensureLoaded()
            updateSensor()
            render.removeCallbacks(videoLoop)
            if (!visible) return
            if (clip != null) render.post(videoLoop) else drawFrame()
        }

        private fun restartLoopIfNeeded() {
            render.removeCallbacks(videoLoop)
            if (isVisible && clip != null) render.post(videoLoop)
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
                clip = VideoClip.open(Store.videoDir(ctx))
                frameIndex = 0
            } else {
                bg = Store.bgFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                fg = Store.fgFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                bgFill = Store.bgFillFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                depth = try {
                    Store.depthFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                        ?.let { DepthMesh(it, fg) }
                } catch (_: Throwable) { null }
            }
        }

        private fun drawFrame() {
            ensureLoaded()
            val holder = surfaceHolder ?: return
            val canvas = try { holder.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
            try {
                val c = clip
                DepthRenderer.draw(
                    canvas,
                    bg = if (c != null) c.currentFrame else bg,
                    fg = if (c != null) null else fg,
                    cfg = cfg,
                    showClock = locked || cfg.showOnHome,
                    now = Date(),
                    is24 = DateFormat.is24HourFormat(ctx),
                    tiltX = tiltX, tiltY = tiltY,
                    mask = c?.mask,
                    bgFill = if (c != null) null else bgFill,
                    depth = if (c != null) null else depth
                )
                drawnX = tiltX; drawnY = tiltY
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }

        // ---------- paralaxe ----------

        private fun updateSensor() {
            val want = isVisible && cfg.parallax && tiltSensor != null
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
            val targetX = ((rawX - baseX) / 0.3f).coerceIn(-1f, 1f)
            val targetY = ((rawY - baseY) / 0.3f).coerceIn(-1f, 1f)
            tiltX += (targetX - tiltX) * 0.2f
            tiltY += (targetY - tiltY) * 0.2f
            // No vídeo o loop já redesenha a cada frame
            if (clip == null && (abs(tiltX - drawnX) > 0.01f || abs(tiltY - drawnY) > 0.01f)) drawFrame()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}
