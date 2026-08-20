package com.autotap.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat

class OverlayService : Service() {

    companion object {
        const val ACTION_OVERLAY_START = "com.autotap.app.OVERLAY_START"
        private const val TAG = "AutoTapOverlay"
        const val ACTION_HIDE_MARKER = "com.autotap.app.HIDE_MARKER"
        const val ACTION_SHOW_MARKER = "com.autotap.app.SHOW_MARKER"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: View
    private lateinit var statusText: TextView
    private lateinit var btnPlace: Button
    private lateinit var toggleButton: Button
    private lateinit var panelParams: WindowManager.LayoutParams
    private var markerView: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var catchLayer: View? = null
    private var running = false
    private var markerVisible = true

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
                ACTION_HIDE_MARKER -> hideMarkerForTap()
                ACTION_SHOW_MARKER -> showMarkerAfterTap()
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
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        panelParams = WindowManager.LayoutParams(
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

        makePanelDraggable()

        btnPlace.setOnClickListener { enterPlacementMode(type) }
        toggleButton.setOnClickListener { if (running) stopRun() else requestStart() }

        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_PROGRESS)
            addAction(ScreenCaptureService.ACTION_DONE)
            addAction(ACTION_HIDE_MARKER)
            addAction(ACTION_SHOW_MARKER)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun makePanelDraggable() {
        var downX = 0f
        var downY = 0f
        var paramX = 0
        var paramY = 0
        var dragging = false

        panelView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    paramX = panelParams.x
                    paramY = panelParams.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        dragging = true
                    }
                    if (dragging) {
                        panelParams.x = paramX + dx.toInt()
                        panelParams.y = paramY + dy.toInt()
                        windowManager.updateViewLayout(panelView, panelParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun hideMarkerForTap() {
        if (markerView?.visibility == View.VISIBLE) {
            markerView?.visibility = View.INVISIBLE
            markerVisible = false
            Log.d(TAG, "Marker hidden for tap")
        }
    }

    private fun showMarkerAfterTap() {
        if (!markerVisible && markerView != null) {
            markerView?.visibility = View.VISIBLE
            markerVisible = true
            Log.d(TAG, "Marker restored after tap")
        }
    }

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
                } else false
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

    private fun showMarker(x: Int, y: Int) {
        val size = (resources.displayMetrics.density * 56).toInt().coerceAtLeast(48)
        if (markerView == null) {
            markerView = View(this).apply { setBackgroundResource(R.drawable.marker_target) }
            markerParams = WindowManager.LayoutParams(
                size, size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
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
        var isDragging = false
        markerView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = markerParams?.x ?: 0; startY = markerParams?.y ?: 0
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    markerParams?.apply {
                        x = startX + dx.toInt(); y = startY + dy.toInt()
                        windowManager.updateViewLayout(markerView, this)
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (isDragging) persistMarkerTarget(size); true }
                else -> false
            }
        }
    }

    private fun persistMarkerTarget(size: Int) {
        val p = markerParams ?: return
        val cx = p.x + size / 2; val cy = p.y + size / 2
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
}
