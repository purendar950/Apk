package com.autotap.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etTarget: EditText
    private lateinit var etCount: EditText
    private lateinit var etDelay: EditText
    private lateinit var cbRaw: CheckBox
    private lateinit var etX: EditText
    private lateinit var etY: EditText
    private lateinit var tvStatus: TextView

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlayPermLauncher: ActivityResultLauncher<Intent>
    private lateinit var notifPermLauncher: ActivityResultLauncher<String>

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenCaptureService.ACTION_PROGRESS -> {
                    val done = intent.getIntExtra(ScreenCaptureService.EXTRA_DONE, 0)
                    val total = intent.getIntExtra(ScreenCaptureService.EXTRA_TOTAL, 0)
                    tvStatus.text = "Captured $done / $total"
                }
                ScreenCaptureService.ACTION_DONE -> {
                    val pages = intent.getIntExtra(ScreenCaptureService.EXTRA_PAGES, 0)
                    tvStatus.text = "Done — $pages page(s) saved"
                    showToast("Saved to gallery & app")
                }
                OverlayService.ACTION_OVERLAY_START -> startCaptureFlow()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTarget = findViewById(R.id.etTarget)
        etCount = findViewById(R.id.etCount)
        etDelay = findViewById(R.id.etDelay)
        cbRaw = findViewById(R.id.cbRaw)
        etX = findViewById(R.id.etX)
        etY = findViewById(R.id.etY)
        tvStatus = findViewById(R.id.tvStatus)

        val cfg = ConfigStore.load(this)
        etTarget.setText(cfg.targetText)
        etCount.setText(cfg.questionCount.toString())
        etDelay.setText(cfg.delayMs.toString())
        cbRaw.isChecked = cfg.useRawTap
        etX.setText(cfg.tapX.toString())
        etY.setText(cfg.tapY.toString())

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = ScreenCaptureService.buildStartIntent(
                    this, result.resultCode, result.data!!, currentConfig()
                )
                ContextCompat.startForegroundService(this, intent)
                tvStatus.text = "Capturing…"
            } else {
                showToast("Screen capture permission denied")
            }
        }

        overlayPermLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { startOverlayIfAllowed() }

        notifPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* foreground service still runs without it */ }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            requestOverlayPermission()
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startOverlayIfAllowed()
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestNotificationPermission()
            startCaptureFlow()
        }
        findViewById<Button>(R.id.btnResults).setOnClickListener {
            startActivity(Intent(this, ResultsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter().apply {
                addAction(ScreenCaptureService.ACTION_PROGRESS)
                addAction(ScreenCaptureService.ACTION_DONE)
                addAction(OverlayService.ACTION_OVERLAY_START)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onDestroy()
    }

    private fun startCaptureFlow() {
        if (!isAccessibilityEnabled()) {
            showToast("Enable the AutoTap accessibility service first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        ConfigStore.save(this, currentConfig())
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermLauncher.launch(intent)
    }

    private fun startOverlayIfAllowed() {
        if (!Settings.canDrawOverlays(this)) {
            showToast("Overlay permission not granted")
            requestOverlayPermission()
            return
        }
        startService(Intent(this, OverlayService::class.java))
        tvStatus.text = "Floating controls shown"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any {
            it.equals("$packageName/$SERVICE_CLASS", ignoreCase = true)
        }
    }

    private fun currentConfig(): AutoTapConfig {
        val ui = readConfig()
        val stored = ConfigStore.load(this)
        val placed = stored.useRawTap && (stored.tapX != 0 || stored.tapY != 0)
        return if (placed) {
            ui.copy(useRawTap = true, tapX = stored.tapX, tapY = stored.tapY)
        } else {
            ui
        }
    }

    private fun readConfig(): AutoTapConfig {
        return AutoTapConfig(
            targetText = etTarget.text.toString().ifBlank { "Next" },
            questionCount = etCount.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
            delayMs = etDelay.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 1500L,
            useRawTap = cbRaw.isChecked,
            tapX = etX.text.toString().toIntOrNull() ?: 0,
            tapY = etY.text.toString().toIntOrNull() ?: 0
        )
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val SERVICE_CLASS = "com.autotap.app.AutoTapAccessibilityService"
    }
}
