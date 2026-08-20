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
    private val selectedImages = mutableListOf<File>()
    private val selectedPdfs = mutableListOf<File>()
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
        exitSelectMode()

        try {
            val sessions = SessionManager.loadAllSessions(this)
            allSessions.addAll(sessions)
            Log.d(TAG, "Loaded ${sessions.size} sessions")

            if (sessions.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                return
            }

            tvEmpty.visibility = View.GONE
            for ((index, session) in sessions.withIndex()) {
                val card = createSessionCard(session, index + 1)
                cardViews.add(card)
                scrollContainer.addView(card)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "Error: ${e.message}"
        }
    }

    private fun createSessionCard(session: CaptureSession, number: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        // Header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvNumber = TextView(this).apply {
            text = "#$number"
            textSize = 14f
            setTextColor(Color.parseColor("#4CAF50"))
            setPadding(0, 0, dp(8), 0)
        }
        header.addView(tvNumber)

        val tvDate = TextView(this).apply {
            text = session.dateFormatted()
            textSize = 14f
            setTextColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvDate)

        val tvBadge = TextView(this).apply {
            text = "${session.frameCount} frames"
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        header.addView(tvBadge)
        card.addView(header)

        // Info line
        val stitchCount = session.stitchedFiles.size
        val hasPdf = session.pdfFile() != null
        val tvInfo = TextView(this).apply {
            text = buildString {
                append("Stitched: $stitchCount image(s)")
                if (hasPdf) append(" • PDF ready")
            }
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(4), 0, dp(4))
        }
        card.addView(tvInfo)

        // Buttons row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makeBtn(text: String, color: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.parseColor(color))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { onClick() }
            }
        }

        btnRow.addView(makeBtn("View", "#4CAF50") { openDetail(session) })
        btnRow.addView(makeBtn("Select", "#2196F3") { enterSelectMode(session) })
        btnRow.addView(makeBtn("Share Img", "#FF9800") { shareImages(session) })
        btnRow.addView(makeBtn("Share PDF", "#9C27B0") { sharePdf(session) })
        btnRow.addView(makeBtn("Delete", "#FF4444") { confirmDelete(session) })

        card.addView(btnRow)

        // Thumbnail
        val stitched = session.stitchedFileObjects(this)
        if (stitched.isNotEmpty()) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(90)).apply {
                    topMargin = dp(8)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }
            card.addView(iv)
            iv.post {
                try {
                    iv.setImageBitmap(decodeSampled(stitched.first(), 120, 180))
                } catch (_: Exception) {}
            }
        }

        return card
    }

    // ── Multi-select mode ────────────────────────────────────────────────

    private fun enterSelectMode(fromSession: CaptureSession) {
        selectMode = true
        selectBar.visibility = View.VISIBLE
        selectedImages.clear()
        selectedPdfs.clear()

        // Collect all images and PDFs from all sessions
        for (s in allSessions) {
            selectedImages.addAll(s.stitchedFileObjects(this))
            val pdf = s.pdfFile()
            if (pdf != null) selectedPdfs.add(pdf)
        }

        updateSelectBar()
        // Highlight all cards
        for (card in cardViews) {
            card.setBackgroundColor(Color.parseColor("#1B3A1B"))
        }
    }

    private fun exitSelectMode() {
        selectMode = false
        selectBar.visibility = View.GONE
        selectedImages.clear()
        selectedPdfs.clear()
        for (card in cardViews) {
            card.setBackgroundColor(Color.parseColor("#2A2A2A"))
        }
    }

    private fun toggleSelectAll() {
        if (!selectMode) return
        // Already all selected → deselect; else select all
        selectedImages.clear()
        selectedPdfs.clear()
        if (btnSelectAll.text == "Select All") {
            for (s in allSessions) {
                selectedImages.addAll(s.stitchedFileObjects(this))
                val pdf = s.pdfFile()
                if (pdf != null) selectedPdfs.add(pdf)
            }
            btnSelectAll.text = "Deselect All"
        } else {
            btnSelectAll.text = "Select All"
        }
        updateSelectBar()
    }

    private fun updateSelectBar() {
        val imgCount = selectedImages.size
        val pdfCount = selectedPdfs.size
        tvSelectBar.text = "$imgCount image(s) + $pdfCount PDF(s) selected"
    }

    private fun shareSelected() {
        val allFiles = mutableListOf<File>()
        allFiles.addAll(selectedImages)
        allFiles.addAll(selectedPdfs)

        if (allFiles.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }

        if (allFiles.size == 1) {
            shareSingleFile(allFiles.first())
        } else {
            val uris = ArrayList(allFiles.map {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
            })
            val mime = if (allFiles.all { it.name.endsWith(".pdf") }) {
                "application/pdf"
            } else if (allFiles.all { it.name.endsWith(".png") }) {
                "image/png"
            } else {
                "*/*"
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${allFiles.size} files"))
        }
        exitSelectMode()
    }

    private fun shareSingleFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val type = when {
            file.name.endsWith(".pdf") -> "application/pdf"
            file.name.endsWith(".png") -> "image/png"
            else -> "*/*"
        }
        startActivity(Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    // ── Single-session share ─────────────────────────────────────────────

    private fun shareImages(session: CaptureSession) {
        val files = session.stitchedFileObjects(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "No images", Toast.LENGTH_SHORT).show()
            return
        }
        if (files.size == 1) {
            shareSingleFile(files.first())
        } else {
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
            })
            startActivity(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private fun sharePdf(session: CaptureSession) {
        val pdfFile = session.pdfFile()
        if (pdfFile != null) {
            shareSingleFile(pdfFile)
        } else {
            Toast.makeText(this, "Generating PDF…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val files = session.stitchedFileObjects(this@DashboardActivity)
                val pdf = withContext(Dispatchers.IO) {
                    PdfGenerator.createPdf(this@DashboardActivity, files)
                }
                if (pdf != null) {
                    shareSingleFile(pdf)
                } else {
                    Toast.makeText(this@DashboardActivity, "PDF failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Other actions ────────────────────────────────────────────────────

    private fun confirmDelete(session: CaptureSession) {
        AlertDialog.Builder(this)
            .setTitle("Delete session")
            .setMessage("Delete capture from ${session.dateFormatted()}?")
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
            Toast.makeText(this, "Cannot open", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) > reqW || opts.outHeight / (sample * 2) > reqH) {
                sample *= 2
            }
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) { null }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
