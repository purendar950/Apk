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
import java.io.File

/**
 * Shows the stitched capture results stored under `filesDir/stitched`.
 * Each page is decoded at screen width to keep memory reasonable, listed
 * top-to-bottom, and the newest page can be opened or shared via a
 * [FileProvider] content URI.
 */
class ResultsActivity : AppCompatActivity() {

    private val authority by lazy { "$packageName.fileprovider" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val container = findViewById<LinearLayout>(R.id.container)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
        val btnOpen = findViewById<Button>(R.id.btnOpenExternal)
        val btnShare = findViewById<Button>(R.id.btnShare)

        val dir = File(filesDir, "stitched")
        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".png") }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (files.isEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            btnOpen.isEnabled = false
            btnShare.isEnabled = false
            return
        }

        val maxWidth = resources.displayMetrics.widthPixels
        for (file in files) {
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

        val latest = files.last()
        btnOpen.setOnClickListener { openExternal(latest) }
        btnShare.setOnClickListener { shareExternal(latest) }
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

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(this, authority, file)

    private fun openExternal(file: File) {
        val uri = uriFor(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { showToast("No image viewer available") }
    }

    private fun shareExternal(file: File) {
        val uri = uriFor(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share result"))
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
