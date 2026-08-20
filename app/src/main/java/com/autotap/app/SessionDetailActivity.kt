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

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = session!!.dateFormatted()

        val container = findViewById<LinearLayout>(R.id.container)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)

        try {
            val stitched = session!!.stitchedFileObjects(this)
            val frames = session!!.frameFiles(this)

            if (stitched.isEmpty() && frames.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                return
            }

            val maxWidth = resources.displayMetrics.widthPixels

            // Stitched images header
            if (stitched.isNotEmpty()) {
                container.addView(makeHeader("Stitched Images (${stitched.size})"))
                for (file in stitched) {
                    container.addView(makeImage(file, maxWidth))
                }
            }

            // Individual frames header
            if (frames.isNotEmpty()) {
                container.addView(makeHeader("Individual Frames (${frames.size})"))
                for (file in frames) {
                    container.addView(makeImage(file, maxWidth))
                }
            }

            // Wire buttons
            findViewById<Button>(R.id.btnShareAllImages).setOnClickListener { shareAllImages(stitched) }
            findViewById<Button>(R.id.btnSavePdf).setOnClickListener { savePdf(stitched) }
            findViewById<Button>(R.id.btnSharePdf).setOnClickListener { sharePdf(stitched) }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading session", e)
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "Error: ${e.message}"
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

    private fun makeImage(file: File, maxWidth: Int): ImageView {
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.parseColor("#111111"))
            post {
                try {
                    val bmp = decodeScaled(file, maxWidth)
                    setImageBitmap(bmp)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode ${file.name}", e)
                }
            }
        }
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
            val uris = ArrayList(files.map { FileProvider.getUriForFile(this, authority, it) })
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

    private fun decodeScaled(file: File, maxWidth: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / (sample + 1) > maxWidth) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
