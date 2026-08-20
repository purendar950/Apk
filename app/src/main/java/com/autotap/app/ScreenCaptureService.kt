package com.autotap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : android.app.Service() {

    companion object {
        private const val TAG = "AutoTapCapture"
        private const val CHANNEL_ID = "autotap_capture"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.autotap.app.action.START"
        const val ACTION_STOP = "com.autotap.app.action.STOP"
        const val ACTION_PROGRESS = "com.autotap.app.PROGRESS"
        const val ACTION_DONE = "com.autotap.app.DONE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_TEXT = "target_text"
        const val EXTRA_COUNT = "count"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_USE_RAW = "use_raw"
        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"
        const val EXTRA_DONE = "done"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_PAGES = "pages"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_GALLERY_URIS = "gallery_uris"

        fun buildStartIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            config: AutoTapConfig
        ): Intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, data)
            putExtra(EXTRA_TARGET_TEXT, config.targetText)
            putExtra(EXTRA_COUNT, config.questionCount)
            putExtra(EXTRA_DELAY, config.delayMs)
            putExtra(EXTRA_USE_RAW, config.useRawTap)
            putExtra(EXTRA_TAP_X, config.tapX)
            putExtra(EXTRA_TAP_Y, config.tapY)
        }

        fun parseConfig(intent: Intent): AutoTapConfig = AutoTapConfig(
            targetText = intent.getStringExtra(EXTRA_TARGET_TEXT) ?: "Next",
            questionCount = intent.getIntExtra(EXTRA_COUNT, 10),
            delayMs = intent.getLongExtra(EXTRA_DELAY, 1500L),
            useRawTap = intent.getBooleanExtra(EXTRA_USE_RAW, false),
            tapX = intent.getIntExtra(EXTRA_TAP_X, 0),
            tapY = intent.getIntExtra(EXTRA_TAP_Y, 0)
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var density = 0

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Preparing capture…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopCapture()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun startCapture(intent: Intent) {
        if (running) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        if (data == null) { stopSelf(); return }
        val config = parseConfig(intent)
        if (!setupProjection(resultCode, data)) { stopSelf(); return }
        running = true
        runLoop(config)
    }

    private fun setupProjection(resultCode: Int, data: Intent): Boolean {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() = stopCapture()
            },
            handler
        )

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoTapCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        )
        return virtualDisplay != null
    }

    private fun runLoop(config: AutoTapConfig) {
        scope.launch {
            val dir = File(filesDir, "frames").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            var captured = 0
            broadcastProgress(captured, config.questionCount)

            // Give user time to switch to target app after pressing Start
            Log.d(TAG, "Starting in 2 seconds — switch to target app now!")
            delay(2000)

            repeat(config.questionCount) {
                if (!running) return@launch

                // Tap FIRST, then capture (so we capture the result of the tap)
                tap(config)
                // Small delay for the tap to register in the UI
                delay(300)
                if (captureFrame(dir, captured)) captured++
                broadcastProgress(captured, config.questionCount)
                // Wait for the next question to render
                delay(config.delayMs)
            }

            // Extra final capture after last tap
            if (running && captureFrame(dir, captured)) captured++

            val frames = dir.listFiles()
                ?.filter { it.name.endsWith(".png") }
                ?.sortedBy { it.name }
                ?: emptyList()
            val outDir = File(filesDir, "stitched").apply { mkdirs() }
            val pages = FrameStitcher.stitch(frames, outDir, maxWidth = 1080)
            val galleryUris = pages.mapNotNull { GallerySaver.save(this@ScreenCaptureService, it, it.name) }
            running = false
            broadcastDone(pages, galleryUris)
            stopCapture()
        }
    }

    private suspend fun captureFrame(dir: File, index: Int): Boolean = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext false
        try {
            var image = reader.acquireLatestImage()
            for (i in 0 until 40) {
                if (image != null) break
                Thread.sleep(50)
                image = reader.acquireLatestImage()
            }
            if (image == null) return@withContext false
            val bitmap: Bitmap = imageToBitmap(image)
            val file = File(dir, "frame_%05d.png".format(index))
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "captureFrame failed", e)
            return@withContext false
        }
    }

    private suspend fun tap(config: AutoTapConfig) {
        val svc = AutoTapAccessibilityService.instance
        Log.d(TAG, "tap() svc=$svc useRaw=${config.useRawTap} x=${config.tapX} y=${config.tapY}")
        if (svc == null) {
            Log.w(TAG, "Accessibility service not running! Tap will fail.")
            return
        }

        // Hide the overlay marker so it doesn't intercept the gesture
        sendBroadcast(Intent(OverlayService.ACTION_HIDE_MARKER).apply { setPackage(packageName) })
        // Small delay for the overlay to hide
        delay(100)

        withContext(Dispatchers.Main) {
            if (config.useRawTap) {
                svc.tapAt(config.tapX, config.tapY)
            } else {
                svc.tapByText(config.targetText)
            }
        }

        // Wait for gesture to complete, then restore the marker
        delay(200)
        sendBroadcast(Intent(OverlayService.ACTION_SHOW_MARKER).apply { setPackage(packageName) })
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopCapture() {
        if (!running && mediaProjection == null) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { mediaProjection?.stop() } catch (_: Exception) { }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AutoTap Capture", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoTap")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        startForeground(NOTIF_ID, buildNotification(text))
    }

    private fun broadcastProgress(done: Int, total: Int) {
        updateNotification("Captured $done / $total")
        sendBroadcast(
            Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_DONE, done)
                putExtra(EXTRA_TOTAL, total)
            }
        )
    }

    private fun broadcastDone(pages: List<File>, galleryUris: List<Uri>) {
        sendBroadcast(
            Intent(ACTION_DONE).apply {
                setPackage(packageName)
                putExtra(EXTRA_PAGES, pages.size)
                putStringArrayListExtra(EXTRA_PATHS, ArrayList(pages.map { it.absolutePath }))
                putParcelableArrayListExtra(EXTRA_GALLERY_URIS, ArrayList(galleryUris))
            }
        )
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        // Crop to actual screen size if there's padding
        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }
}
