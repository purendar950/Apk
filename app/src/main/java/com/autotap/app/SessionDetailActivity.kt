package com.autotap.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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

    private val authority by lazy { "$packageName.fileprovider" }
    private val scope = CoroutineScope(Dispatchers.Main)
    private var session: CaptureSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_detail)

        val sessionId = intent.getStringExtra("SESSION_ID")
        if (sessionId == null) { finish(); return }

        session = SessionManager.loadAllSessions(this).find { it.id == sessionId }
        if (session == null) { Toast.makeText(this, "Session not found", Toast.LENGTH_SHORT).show(); finish(); return }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = session!!.dateFormatted()

        val container = findViewById<LinearLayout>(R.id.container)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)

        val stitched = session!!.stitchedFileObjects(this)
        val frames = session!!.frameFiles(this)

        if (stitched.isEmpty() && frames.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }

        val maxWidth = resources.displayMetrics.widthPixels

        // Show stitched images
        if (stitched.isNotEmpty()) {
            val header = TextView(this).apply {
                text = "Stitched Images (${stitched.size})"
                textSize = 14f
                setPadding(0, 8, 0, 4)
                setTextColor(0xFFAAAAAA.toInt())
            }
            container.addView(header)

            for (file in stitched) {
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.gravity = Gravity.CENTER_HORIZONTAL }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setImageBitmap(decodeScaled(file, maxWidth))
                }
                container.addView(iv)
            }
        }

        // Show individual frames
        if (frames.isNotEmpty()) {
            val header = TextView(this).apply {
                text = "Individual Frames (${frames.size})"
                textSize = 14f
                setPadding(0, 16, 0, 4)
                setTextColor(0xFFAAAAAA.toInt())
            }
            container.addView(header)

            for (file in frames) {
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.gravity = Gravity.CENTER_HORIZONTAL }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setImageBitmap(decodeScaled(file, maxWidth))
                }
                container.addView(iv)
            }
        }

        // Action buttons
        val btnShareImages = findViewById<Button>(R.id.btnShareAllImages)
        val btnSavePdf = findViewById<Button>(R.id.btnSavePdf)
        val btnSharePdf = findViewById<Button>(R.id.btnSharePdf)
        val btnOpenAll = findViewById<Button>(R.id.btnOpenAll)

        btnShareImages.setOnClickListener { shareAllImages(stitched) }
        btnSavePdf.setOnClickListener { savePdf(stitched) }
        btnSharePdf.setOnClickListener { sharePdf(stitched) }
        btnOpenAll.setOnClickListener { openAllImages(stitched) }
    }

    private fun shareAllImages(files: List<File>) {
        if (files.isEmpty()) {
            Toast.makeText(this, "No images to share", Toast.LENGTH_SHORT).show()
            return
        }
        if (files.size == 1) {
            val uri = FileProvider.getUriForFile(this, authority, files.first())
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } else {
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(this, authority, it)
            })
            startActivity(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private fun savePdf(files: List<File>) {
        if (files.isEmpty()) return
        val btn = findViewById<Button>(R.id.btnSavePdf)
        btn.isEnabled = false; btn.text = "Generating…"
        scope.launch {
            val pdf = withContext(Dispatchers.IO) { PdfGenerator.createPdf(this@SessionDetailActivity, files) }
            if (pdf != null) {
                withContext(Dispatchers.IO) { PdfGenerator.saveToGallery(this@SessionDetailActivity, pdf) }
                Toast.makeText(this@SessionDetailActivity, "PDF saved to Documents/AutoTap/", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@SessionDetailActivity, "Failed to create PDF", Toast.LENGTH_SHORT).show()
            }
            btn.isEnabled = true; btn.text = "Save PDF"
        }
    }

    private fun sharePdf(files: List<File>) {
        if (files.isEmpty()) return
        val btn = findViewById<Button>(R.id.btnSharePdf)
        btn.isEnabled = false; btn.text = "Generating…"
        scope.launch {
            val pdf = withContext(Dispatchers.IO) { PdfGenerator.createPdf(this@SessionDetailActivity, files) }
            if (pdf != null) {
                val uri = FileProvider.getUriForFile(this@SessionDetailActivity, authority, pdf)
                startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } else {
                Toast.makeText(this@SessionDetailActivity, "Failed to create PDF", Toast.LENGTH_SHORT).show()
            }
            btn.isEnabled = true; btn.text = "Share PDF"
        }
    }

    private fun openAllImages(files: List<File>) {
        if (files.isEmpty()) return
        for (file in files) {
            val uri = FileProvider.getUriForFile(this, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(intent); return }
        }
        Toast.makeText(this, "No image viewer available", Toast.LENGTH_SHORT).show()
    }

    private fun decodeScaled(file: File, maxWidth: Int): android.graphics.Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / (sample + 1) > maxWidth) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}
