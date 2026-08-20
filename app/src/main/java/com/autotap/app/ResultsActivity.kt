package com.autotap.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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

class ResultsActivity : AppCompatActivity() {

    private val authority by lazy { "$packageName.fileprovider" }
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var stitchedFiles: List<File>
    private lateinit var btnSavePdf: Button
    private lateinit var btnSharePdf: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val container = findViewById<LinearLayout>(R.id.container)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
        val btnOpen = findViewById<Button>(R.id.btnOpenExternal)
        val btnShare = findViewById<Button>(R.id.btnShare)
        btnSavePdf = findViewById(R.id.btnSavePdf)
        btnSharePdf = findViewById(R.id.btnSharePdf)

        val dir = File(filesDir, "stitched")
        stitchedFiles = dir.listFiles()
            ?.filter { it.name.endsWith(".png") }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (stitchedFiles.isEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            btnOpen.isEnabled = false
            btnShare.isEnabled = false
            btnSavePdf.isEnabled = false
            btnSharePdf.isEnabled = false
            return
        }

        val maxWidth = resources.displayMetrics.widthPixels
        for (file in stitchedFiles) {
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

        val latest = stitchedFiles.last()
        btnOpen.setOnClickListener { openExternal(latest) }
        btnShare.setOnClickListener { shareExternal(latest) }
        btnSavePdf.setOnClickListener { savePdf() }
        btnSharePdf.setOnClickListener { sharePdf() }
    }

    private fun savePdf() {
        if (stitchedFiles.isEmpty()) return
        btnSavePdf.isEnabled = false
        btnSavePdf.text = "Generating…"
        scope.launch {
            val pdf = withContext(Dispatchers.IO) {
                PdfGenerator.createPdf(this@ResultsActivity, stitchedFiles)
            }
            if (pdf != null) {
                val saved = withContext(Dispatchers.IO) {
                    PdfGenerator.saveToGallery(this@ResultsActivity, pdf)
                }
                val msg = if (saved != null) {
                    "PDF saved to Documents/AutoTap/${pdf.name}"
                } else {
                    "PDF saved to app storage: ${pdf.name}"
                }
                Toast.makeText(this@ResultsActivity, msg, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ResultsActivity, "Failed to create PDF", Toast.LENGTH_SHORT).show()
            }
            btnSavePdf.text = "Save PDF"
            btnSavePdf.isEnabled = true
        }
    }

    private fun sharePdf() {
        if (stitchedFiles.isEmpty()) return
        btnSharePdf.isEnabled = false
        btnSharePdf.text = "Generating…"
        scope.launch {
            val pdf = withContext(Dispatchers.IO) {
                PdfGenerator.createPdf(this@ResultsActivity, stitchedFiles)
            }
            if (pdf != null) {
                val uri = FileProvider.getUriForFile(this@ResultsActivity, authority, pdf)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share PDF"))
            } else {
                Toast.makeText(this@ResultsActivity, "Failed to create PDF", Toast.LENGTH_SHORT).show()
            }
            btnSharePdf.text = "Share PDF"
            btnSharePdf.isEnabled = true
        }
    }

    private fun decodeScaled(file: File, maxWidth: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / (sample + 1) > maxWidth) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun openExternal(file: File) {
        val uri = FileProvider.getUriForFile(this, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "No image viewer available", Toast.LENGTH_SHORT).show() }
    }

    private fun shareExternal(file: File) {
        val uri = FileProvider.getUriForFile(this, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share result"))
    }
}
