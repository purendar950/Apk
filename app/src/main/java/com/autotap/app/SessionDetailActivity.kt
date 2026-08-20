package com.autotap.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SessionDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AutoTapDetail"
    }

    private val authority by lazy { "$packageName.fileprovider" }
    private val scope = CoroutineScope(Dispatchers.Main)
    private var session: CaptureSession? = null

    // Track selected items for multi-share
    private val allFiles = mutableListOf<File>()
    private val selectedIndices = mutableSetOf<Int>()
    private lateinit var container: LinearLayout
    private lateinit var tvSelectInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_detail)

        val sessionId = intent.getStringExtra("SESSION_ID")
        if (sessionId == null) { finish(); return }

        session = SessionManager.loadAllSessions(this).find { it.id == sessionId }
        if (session == null) {
            Toast.makeText(this, "Session not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        container = findViewById(R.id.container)
        tvSelectInfo = findViewById(R.id.tvSelectInfo)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = session!!.dateFormatted()

        try {
            loadFiles()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading session", e)
            findViewById<TextView>(R.id.tvEmpty).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvEmpty).text = "Error: ${e.message}"
        }

        // Action buttons
        findViewById<Button>(R.id.btnShareAllImages).setOnClickListener { shareAllImages() }
        findViewById<Button>(R.id.btnSavePdf).setOnClickListener { savePdf() }
        findViewById<Button>(R.id.btnSharePdf).setOnClickListener { sharePdf() }
        findViewById<Button>(R.id.btnShareSelected).setOnClickListener { shareSelectedFiles() }
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener { toggleSelectAll() }
    }

    private fun loadFiles() {
        container.removeAllViews()
        allFiles.clear()
        selectedIndices.clear()

        val stitched = session!!.stitchedFileObjects(this)
        val frames = session!!.frameFiles(this)

        if (stitched.isEmpty() && frames.isEmpty()) {
            findViewById<TextView>(R.id.tvEmpty).visibility = View.VISIBLE
            return
        }

        val maxWidth = resources.displayMetrics.widthPixels

        // Stitched images
        if (stitched.isNotEmpty()) {
            container.addView(makeHeader("Stitched Images (${stitched.size}) — tap to select"))
            for ((i, file) in stitched.withIndex()) {
                val idx = allFiles.size
                allFiles.add(file)
                container.addView(makeSelectableImage(file, maxWidth, idx))
            }
        }

        // Individual frames
        if (frames.isNotEmpty()) {
            container.addView(makeHeader("Frames (${frames.size}) — tap to select"))
            for ((i, file) in frames.withIndex()) {
                val idx = allFiles.size
                allFiles.add(file)
                container.addView(makeSelectableImage(file, maxWidth, idx))
            }
        }
    }

    private fun makeHeader(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
            setTextColor(Color.parseColor("#AAAAAA"))
        }
    }

    private fun makeSelectableImage(file: File, maxWidth: Int, index: Int): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.parseColor("#111111"))
            post {
                try {
                    setImageBitmap(decodeScaled(file, maxWidth))
                } catch (_: Exception) {}
            }
        }

        // Selection indicator
        val tvSel = TextView(this).apply {
            text = "○ ${file.name}"
            textSize = 11f
            setTextColor(Color.parseColor("#777777"))
            setPadding(0, dp(2), 0, 0)
        }

        wrapper.addView(iv)
        wrapper.addView(tvSel)

        // Toggle selection on tap
        wrapper.setOnClickListener {
            if (selectedIndices.contains(index)) {
                selectedIndices.remove(index)
                tvSel.text = "○ ${file.name}"
                tvSel.setTextColor(Color.parseColor("#777777"))
                wrapper.alpha = 1.0f
            } else {
                selectedIndices.add(index)
                tvSel.text = "● ${file.name}"
                tvSel.setTextColor(Color.parseColor("#4CAF50"))
                wrapper.alpha = 0.85f
            }
            updateSelectInfo()
        }

        return wrapper
    }

    private fun updateSelectInfo() {
        tvSelectInfo.text = "${selectedIndices.size} of ${allFiles.size} selected"
        tvSelectInfo.visibility = if (allFiles.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleSelectAll() {
        if (selectedIndices.size == allFiles.size) {
            selectedIndices.clear()
        } else {
            for (i in allFiles.indices) selectedIndices.add(i)
        }
        // Rebuild to update visuals
        loadFiles()
        // Re-select
        for (i in selectedIndices) {
            val child = container.getChildAt(if (session!!.stitchedFileObjects(this).isNotEmpty()) 1 else 0)
            // Just update the info text
        }
        updateSelectInfo()
    }

    private fun shareSelectedFiles() {
        if (selectedIndices.isEmpty()) {
            Toast.makeText(this, "Tap images to select, then share", Toast.LENGTH_SHORT).show()
            return
        }
        val files = selectedIndices.map { allFiles[it] }
        shareFiles(files)
    }

    private fun shareAllImages() {
        val files = session!!.stitchedFileObjects(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "No images", Toast.LENGTH_SHORT).show()
            return
        }
        shareFiles(files)
    }

    private fun shareFiles(files: List<File>) {
        if (files.isEmpty()) return
        if (files.size == 1) {
            val uri = FileProvider.getUriForFile(this, authority, files.first())
            val type = when {
                files.first().name.endsWith(".pdf") -> "application/pdf"
                files.first().name.endsWith(".png") -> "image/png"
                else -> "*/*"
            }
            startActivity(Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } else {
            val uris = ArrayList(files.map { FileProvider.getUriForFile(this, authority, it) })
            val allPdf = files.all { it.name.endsWith(".pdf") }
            val allPng = files.all { it.name.endsWith(".png") }
            val mime = when {
                allPdf -> "application/pdf"
                allPng -> "image/png"
                else -> "*/*"
            }
            startActivity(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private fun savePdf() {
        val files = session!!.stitchedFileObjects(this)
        if (files.isEmpty()) return
        val btn = findViewById<Button>(R.id.btnSavePdf)
        btn.isEnabled = false; btn.text = "Generating…"
        scope.launch {
            val pdf = withContext(Dispatchers.IO) { PdfGenerator.createPdf(this@SessionDetailActivity, files) }
            if (pdf != null) {
                withContext(Dispatchers.IO) { PdfGenerator.saveToGallery(this@SessionDetailActivity, pdf) }
                Toast.makeText(this@SessionDetailActivity, "PDF saved to Documents/AutoTap/", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@SessionDetailActivity, "Failed", Toast.LENGTH_SHORT).show()
            }
            btn.isEnabled = true; btn.text = "Save PDF"
        }
    }

    private fun sharePdf() {
        val files = session!!.stitchedFileObjects(this)
        if (files.isEmpty()) return
        val btn = findViewById<Button>(R.id.btnSharePdf)
        btn.isEnabled = false; btn.text = "Generating…"
        scope.launch {
            val pdf = withContext(Dispatchers.IO) { PdfGenerator.createPdf(this@SessionDetailActivity, files) }
            if (pdf != null) {
                shareFiles(listOf(pdf))
            } else {
                Toast.makeText(this@SessionDetailActivity, "Failed", Toast.LENGTH_SHORT).show()
            }
            btn.isEnabled = true; btn.text = "Share PDF"
        }
    }

    private fun decodeScaled(file: File, maxWidth: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / (sample + 1) > maxWidth) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) { null }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
