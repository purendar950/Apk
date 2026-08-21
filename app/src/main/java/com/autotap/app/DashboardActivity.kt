package com.autotap.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AutoTapDashboard"
    }

    private lateinit var scrollContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private val scope = CoroutineScope(Dispatchers.Main)

    // Multi-select state
    private val selectedFiles = mutableListOf<File>()
    private var selectMode = false
    private lateinit var tvSelectBar: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnShareSelected: Button
    private lateinit var selectBar: LinearLayout

    private val allSessions = mutableListOf<CaptureSession>()
    private val cardViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        scrollContainer = findViewById(R.id.scrollContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvSelectBar = findViewById(R.id.tvSelectBar)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnShareSelected = findViewById(R.id.btnShareSelected)
        selectBar = findViewById(R.id.selectBar)

        findViewById<View>(R.id.btnRefresh).setOnClickListener { loadSessions() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        btnShareSelected.setOnClickListener { shareSelected() }

        selectBar.visibility = View.GONE
        loadSessions()
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    private fun loadSessions() {
        scrollContainer.removeAllViews()
        cardViews.clear()
        allSessions.clear()
        selectedFiles.clear()
        selectMode = false
        selectBar.visibility = View.GONE

        try {
            // Load tracked sessions
            val sessions = SessionManager.loadAllSessions(this).toMutableList()

            // Also scan for orphaned stitched files (from older versions)
            val knownIds = sessions.map { it.id }.toSet()
            val stitchedDir = File(filesDir, "stitched")
            if (stitchedDir.exists()) {
                val orphans = stitchedDir.listFiles()?.filter { it.name.endsWith(".png") }?.sortedBy { it.name }
                if (!orphans.isNullOrEmpty()) {
                    // Group all orphans into one pseudo-session
                    val orphanSession = CaptureSession(
                        id = "legacy_stitched",
                        timestamp = orphans.firstOrNull()?.lastModified() ?: System.currentTimeMillis(),
                        questionCount = 0,
                        frameCount = orphans.size,
                        sessionDir = stitchedDir.absolutePath,
                        stitchedFiles = orphans.map { it.absolutePath },
                        pdfPath = null
                    )
                    sessions.add(orphanSession)
                }
            }

            // Sort newest first
            sessions.sortByDescending { it.timestamp }

            Log.d(TAG, "Loaded ${sessions.size} sessions (including legacy)")

            if (sessions.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                return
            }

            tvEmpty.visibility = View.GONE

            // Number sequentially #1 (newest) to #N (oldest)
            for ((index, session) in sessions.withIndex()) {
                try {
                    val card = createSessionCard(session, index + 1)
                    cardViews.add(card)
                    scrollContainer.addView(card)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create card for session ${session.id}", e)
                    // Skip this card but don't crash
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "Error loading: ${e.message}"
        }
    }

    private fun createSessionCard(session: CaptureSession, number: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // Header row: number + date + frame badge
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = "#$number"
            textSize = 15f
            setTextColor(Color.parseColor("#4CAF50"))
            setPadding(0, 0, dp(8), 0)
        })

        header.addView(TextView(this).apply {
            text = try { session.dateFormatted() } catch (e: Exception) { session.id }
            textSize = 14f
            setTextColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        header.addView(TextView(this).apply {
            text = "${session.frameCount} frames"
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        })
        card.addView(header)

        // Info line
        val stitchCount = try { session.stitchedFileObjects(this@DashboardActivity).size } catch (e: Exception) { 0 }
        val hasPdf = try { session.pdfFile() != null } catch (e: Exception) { false }
        card.addView(TextView(this).apply {
            text = buildString {
                append("Images: $stitchCount")
                if (hasPdf) append(" • PDF ✓")
            }
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(4), 0, dp(4))
        })

        // Buttons row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makeBtn(text: String, color: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.parseColor(color))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(4), 0, dp(4), 0)
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { runCatching { onClick() } }
            }
        }

        btnRow.addView(makeBtn("View", "#4CAF50") { openDetail(session) })
        btnRow.addView(makeBtn("Share Img", "#FF9800") { shareImages(session) })
        btnRow.addView(makeBtn("Share PDF", "#9C27B0") { sharePdf(session) })
        btnRow.addView(makeBtn("Delete", "#FF4444") { confirmDelete(session) })
        card.addView(btnRow)

        // Thumbnail — load lazily with small size to avoid OOM
        val stitched = try { session.stitchedFileObjects(this) } catch (e: Exception) { emptyList() }
        if (stitched.isNotEmpty()) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(72)).apply {
                    topMargin = dp(6)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#111111"))
            }
            card.addView(iv)
            iv.post {
                try {
                    iv.setImageBitmap(decodeTiny(stitched.first()))
                } catch (_: Exception) {}
            }
        }

        return card
    }

    /** Decode very small thumbnail to avoid OutOfMemoryError */
    private fun decodeTiny(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) > 96 || opts.outHeight / (sample * 2) > 144) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) { null }
    }

    // ── Multi-select ─────────────────────────────────────────────────────

    private fun toggleSelectAll() {
        Toast.makeText(this, "Use Share Img / Share PDF per session", Toast.LENGTH_SHORT).show()
    }

    private fun shareSelected() {
        if (selectedFiles.isEmpty()) return
        shareFiles(selectedFiles.toList())
        selectedFiles.clear()
        selectBar.visibility = View.GONE
    }

    // ── Sharing ──────────────────────────────────────────────────────────

    private fun shareImages(session: CaptureSession) {
        val files = try { session.stitchedFileObjects(this) } catch (e: Exception) { emptyList() }
        if (files.isEmpty()) {
            Toast.makeText(this, "No images", Toast.LENGTH_SHORT).show()
            return
        }
        shareFiles(files)
    }

    private fun sharePdf(session: CaptureSession) {
        val pdfFile = try { session.pdfFile() } catch (e: Exception) { null }
        if (pdfFile != null) {
            shareFiles(listOf(pdfFile))
        } else {
            Toast.makeText(this, "Generating PDF…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val files = try { session.stitchedFileObjects(this@DashboardActivity) } catch (e: Exception) { emptyList<File>() }
                val pdf = withContext(Dispatchers.IO) {
                    PdfGenerator.createPdf(this@DashboardActivity, files)
                }
                if (pdf != null) {
                    shareFiles(listOf(pdf))
                } else {
                    Toast.makeText(this@DashboardActivity, "PDF failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareFiles(files: List<File>) {
        try {
            if (files.size == 1) {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", files.first())
                startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = mimeFor(files.first())
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } else {
                val uris = ArrayList(files.mapNotNull {
                    runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }.getOrNull()
                })
                val allPng = files.all { it.name.endsWith(".png") }
                val allPdf = files.all { it.name.endsWith(".pdf") }
                startActivity(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = when {
                        allPdf -> "application/pdf"
                        allPng -> "image/png"
                        else -> "*/*"
                    }
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeFor(file: File) = when {
        file.name.endsWith(".pdf") -> "application/pdf"
        file.name.endsWith(".png") -> "image/png"
        else -> "*/*"
    }

    // ── Other actions ────────────────────────────────────────────────────

    private fun confirmDelete(session: CaptureSession) {
        AlertDialog.Builder(this)
            .setTitle("Delete session")
            .setMessage("Delete capture #${allSessions.indexOf(session) + 1}?\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                SessionManager.deleteSession(this@DashboardActivity, session)
                loadSessions()
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openDetail(session: CaptureSession) {
        try {
            startActivity(Intent(this, SessionDetailActivity::class.java).apply {
                putExtra("SESSION_ID", session.id)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open detail", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
