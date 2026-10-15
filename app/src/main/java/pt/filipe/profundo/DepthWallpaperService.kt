package pt.filipe.profundo

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.service.wallpaper.WallpaperService
import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import java.util.Date

class DepthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DepthEngine()

    inner class DepthEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var bg: Bitmap? = null
        private var fg: Bitmap? = null
        private var loadedVersion = -1L
        private val prefs = Store.prefs(this@DepthWallpaperService)
        private val keyguard = getSystemService(KeyguardManager::class.java)

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
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) drawFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
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
                    is24 = DateFormat.is24HourFormat(this@DepthWallpaperService)
                )
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
