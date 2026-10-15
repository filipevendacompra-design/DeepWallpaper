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
import android.service.wallpaper.WallpaperService
import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import java.util.Date
import kotlin.math.abs

class DepthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DepthEngine()

    inner class DepthEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener,
        SensorEventListener {

        private var bg: Bitmap? = null
        private var fg: Bitmap? = null
        private var loadedVersion = -1L
        private val prefs = Store.prefs(this@DepthWallpaperService)
        private val keyguard = getSystemService(KeyguardManager::class.java)

        // ---- Paralaxe ----
        private val sensors = getSystemService(SensorManager::class.java)
        private val tiltSensor: Sensor? =
            sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private var listening = false
        private var hasBase = false
        private var baseX = 0f; private var baseY = 0f   // posição "neutra", segue devagar a forma como seguras o telemóvel
        private var tiltX = 0f; private var tiltY = 0f   // inclinação suavizada, -1..1
        private var drawnX = 0f; private var drawnY = 0f

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (isVisible) drawFrame()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)       // cada minuto
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_USER_PRESENT)    // desbloqueou → tirar o relógio
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(
                this@DepthWallpaperService, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            stopSensor()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            updateSensor()
            if (visible) drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopSensor()
            super.onSurfaceDestroyed(holder)
        }

        /** O sensor só corre com o fundo visível e o paralaxe ligado — poupa bateria. */
        private fun updateSensor() {
            val want = isVisible && ClockConfig.load(prefs).parallax && tiltSensor != null
            if (want && !listening) {
                hasBase = false
                listening = sensors!!.registerListener(this, tiltSensor, SensorManager.SENSOR_DELAY_GAME)
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
            if (abs(tiltX - drawnX) > 0.01f || abs(tiltY - drawnY) > 0.01f) drawFrame()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            updateSensor()
            if (isVisible) drawFrame()
        }

        private fun ensureLoaded() {
            val version = prefs.getLong("version", 0L)
            if (version == loadedVersion) return
            val ctx = this@DepthWallpaperService
            bg = Store.bgFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            fg = Store.fgFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            loadedVersion = version
        }

        private fun drawFrame() {
            ensureLoaded()
            val holder = surfaceHolder
            val canvas = try { holder.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
            try {
                val cfg = ClockConfig.load(prefs)
                val locked = keyguard?.isKeyguardLocked ?: true
                DepthRenderer.draw(
                    canvas, bg, fg, cfg,
                    showClock = locked || cfg.showOnHome,
                    now = Date(),
                    is24 = DateFormat.is24HourFormat(this@DepthWallpaperService),
                    tiltX = tiltX, tiltY = tiltY
                )
                drawnX = tiltX; drawnY = tiltY
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
