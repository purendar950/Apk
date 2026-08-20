package com.autotap.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Optional floating control shown on top of other apps (requires the
 * SYSTEM_ALERT_WINDOW permission).
 *
 * Workflow:
 *  - **Place target**: enter placement mode, then tap anywhere on the screen
 *    (e.g. the "Next" button of the app you're automating). That point is saved
 *    as the tap coordinate and shown as a draggable marker you can fine-tune.
 *  - **Start**: asks the main activity to begin a capture (it owns the
 *    MediaProjection permission prompt). The capture loop then taps the placed
 *    point repeatedly and captures each screen.
 *  - **Stop**: stops the running [ScreenCaptureService].
 *  - The label updates from the service's progress broadcasts.
 *
 * The placed coordinate is persisted via [ConfigStore] (useRawTap = true), so
 * the capture service taps there with a raw gesture.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: View
    private lateinit var statusText: TextView
    private lateinit var btnPlace: Button
    private lateinit var toggleButton: Button
    private var markerView: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var catchLayer: View? = null
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenCaptureService.ACTION_PROGRESS -> {
                    val done = intent.getIntExtra(ScreenCaptureService.EXTRA_DONE, 0)
                    val total = intent.getIntExtra(ScreenCaptureService.EXTRA_TOTAL, 0)
                    running = true
                    statusText.text = "Captured $done / $total"
                    toggleButton.text = "Stop"
                }
                ScreenCaptureService.ACTION_DONE -> {
                    val pages = intent.getIntExtra(ScreenCaptureService.EXTRA_PAGES, 0)
                    running = false
                    statusText.text = "Done ($pages pages)"
                    toggleButton.text = "Start"
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_controls, null)
        statusText = panelView.findViewById(R.id.overlayStatus)
        btnPlace = panelView.findViewById(R.id.btnPlace)
        toggleButton = panelView.findViewById(R.id.btnToggle)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 120
        }
        windowManager.addView(panelView, panelParams)

        btnPlace.setOnClickListener { enterPlacementMode(type) }
        toggleButton.setOnClickListener { if (running) stopRun() else requestStart() }

        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(ScreenCaptureService.ACTION_PROGRESS)
                addAction(ScreenCaptureService.ACTION_DONE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Full-screen transparent layer that captures the next tap as the target. */
    private fun enterPlacementMode(type: Int) {
        if (catchLayer != null) return
        val layer = View(this).apply {
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            setBackgroundColor(0x33000000.toInt())
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    placeTarget(event.rawX.toInt(), event.rawY.toInt())
                    removeCatchLayer()
                    true
                } else {
                    false
                }
            }
        }
        catchLayer = layer
        windowManager.addView(layer, layer.layoutParams)
        statusText.text = "Tap the target…"
    }

    private fun removeCatchLayer() {
        catchLayer?.let { runCatching { windowManager.removeView(it) } }
        catchLayer = null
    }

    private fun placeTarget(x: Int, y: Int) {
        val cfg = ConfigStore.load(this)
        ConfigStore.save(this, cfg.copy(useRawTap = true, tapX = x, tapY = y))
        showMarker(x, y)
        statusText.text = "Target set ($x, $y)"
    }

    /** Show / move the draggable target marker at the given screen point. */
    private fun showMarker(x: Int, y: Int) {
        val size = (resources.displayMetrics.density * 56).toInt().coerceAtLeast(48)
        if (markerView == null) {
            markerView = View(this).apply { setBackgroundResource(R.drawable.marker_target) }
            markerParams = WindowManager.LayoutParams(
                size, size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            attachMarkerDrag(size)
            windowManager.addView(markerView, markerParams)
        }
        markerParams?.apply {
            this.x = x - size / 2
            this.y = y - size / 2
            windowManager.updateViewLayout(markerView, this)
        }
    }

    private fun attachMarkerDrag(size: Int) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        markerView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = markerParams?.x ?: 0
                    startY = markerParams?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    markerParams?.apply {
                        x = startX + (event.rawX - downX).toInt()
                        y = startY + (event.rawY - downY).toInt()
                        windowManager.updateViewLayout(markerView, this)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    persistMarkerTarget(size)
                    true
                }
                else -> false
            }
        }
    }

    private fun persistMarkerTarget(size: Int) {
        val p = markerParams ?: return
        val cx = p.x + size / 2
        val cy = p.y + size / 2
        val cfg = ConfigStore.load(this)
        ConfigStore.save(this, cfg.copy(useRawTap = true, tapX = cx, tapY = cy))
    }

    private fun requestStart() {
        sendBroadcast(Intent(ACTION_OVERLAY_START).apply { setPackage(packageName) })
    }

    private fun stopRun() {
        startService(
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { windowManager.removeView(panelView) }
        runCatching { markerView?.let { windowManager.removeView(it) } }
        runCatching { removeCatchLayer() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_OVERLAY_START = "com.autotap.app.OVERLAY_START"
    }
}
