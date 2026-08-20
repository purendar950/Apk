package com.autotap.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        scrollContainer = findViewById(R.id.scrollContainer)
        tvEmpty = findViewById(R.id.tvEmpty)

        loadSessions()
        findViewById<View>(R.id.btnRefresh).setOnClickListener { loadSessions() }
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    private fun loadSessions() {
        scrollContainer.removeAllViews()
        try {
            val sessions = SessionManager.loadAllSessions(this)
            Log.d(TAG, "Loaded ${sessions.size} sessions")

            if (sessions.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                return
            }

            tvEmpty.visibility = View.GONE
            for ((index, session) in sessions.withIndex()) {
                val card = createSessionCard(session, index + 1)
                scrollContainer.addView(card)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sessions", e)
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "Error loading sessions: ${e.message}"
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

        // Header row: number + date + frame badge
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
        val tvInfo = TextView(this).apply {
            text = "Stitched: ${session.stitchedFiles.size} image(s)" +
                    if (session.pdfPath != null) " • PDF ready" else ""
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

        val btnView = Button(this).apply {
            text = "View"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#4CAF50"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { openDetail(session) }
        }
        btnRow.addView(btnView)

        val btnShareImg = Button(this).apply {
            text = "Share Image"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#2196F3"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { shareImages(session) }
        }
        btnRow.addView(btnShareImg)

        val btnSharePdf = Button(this).apply {
            text = "Share PDF"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#FF9800"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { sharePdf(session) }
        }
        btnRow.addView(btnSharePdf)

        val btnDelete = Button(this).apply {
            text = "Delete"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#FF4444"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { confirmDelete(session) }
        }
        btnRow.addView(btnDelete)

        card.addView(btnRow)

        // Thumbnail
        val stitched = session.stitchedFileObjects(this)
        if (stitched.isNotEmpty()) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(80), dp(120)).apply {
                    topMargin = dp(8)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }
            card.addView(iv)
            iv.post {
                try {
                    val bmp = decodeSampled(stitched.first(), 160, 240)
                    iv.setImageBitmap(bmp)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load thumbnail", e)
                }
            }
        }

        return card
    }

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

    private fun shareImages(session: CaptureSession) {
        val files = session.stitchedFileObjects(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "No images to share", Toast.LENGTH_SHORT).show()
            return
        }
        if (files.size == 1) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", files.first())
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
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
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdfFile)
            startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } else {
            Toast.makeText(this, "Generating PDF…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val files = session.stitchedFileObjects(this@DashboardActivity)
                val pdf = withContext(Dispatchers.IO) {
                    PdfGenerator.createPdf(this@DashboardActivity, files)
                }
                if (pdf != null) {
                    val uri = FileProvider.getUriForFile(
                        this@DashboardActivity, "$packageName.fileprovider", pdf
                    )
                    startActivity(Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } else {
                    Toast.makeText(this@DashboardActivity, "Failed to create PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openDetail(session: CaptureSession) {
        try {
            val intent = Intent(this, SessionDetailActivity::class.java).apply {
                putExtra("SESSION_ID", session.id)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open session", Toast.LENGTH_SHORT).show()
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
