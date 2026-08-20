package com.autotap.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DashboardActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: SessionAdapter
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        recycler = findViewById(R.id.recyclerSessions)
        tvEmpty = findViewById(R.id.tvEmpty)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SessionAdapter()
        recycler.adapter = adapter

        loadSessions()
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    private fun loadSessions() {
        val sessions = SessionManager.loadAllSessions(this)
        adapter.submitList(sessions)
        tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun deleteSession(session: CaptureSession) {
        AlertDialog.Builder(this)
            .setTitle("Delete session")
            .setMessage("Delete capture from ${session.dateFormatted()}?\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                SessionManager.deleteSession(this@DashboardActivity, session)
                loadSessions()
                Toast.makeText(this, "Session deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.VH>() {
        private var items = emptyList<CaptureSession>()

        fun submitList(list: List<CaptureSession>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_session, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvDate: TextView = view.findViewById(R.id.tvDate)
            private val tvFrames: TextView = view.findViewById(R.id.tvFrames)
            private val tvInfo: TextView = view.findViewById(R.id.tvInfo)
            private val ivThumb: ImageView = view.findViewById(R.id.ivThumbnail)
            private val btnShareImage: Button = view.findViewById(R.id.btnShareImage)
            private val btnSharePdf: Button = view.findViewById(R.id.btnSharePdf)
            private val btnDelete: Button = view.findViewById(R.id.btnDelete)

            fun bind(session: CaptureSession) {
                tvDate.text = session.dateFormatted()
                tvFrames.text = "${session.frameCount} frames"
                tvInfo.text = buildString {
                    append("Captured: ${session.frameCount} screens")
                    append("\nStitched: ${session.stitchedFiles.size} image(s)")
                    if (session.pdfPath != null) append("\nPDF: ready")
                }

                // Load thumbnail
                val stitched = session.stitchedFileObjects(this@DashboardActivity)
                if (stitched.isNotEmpty()) {
                    ivThumb.post {
                        val bmp = decodeSampled(stitched.first(), 128, 192)
                        ivThumb.setImageBitmap(bmp)
                    }
                } else {
                    ivThumb.setImageBitmap(null)
                }

                btnShareImage.setOnClickListener { shareImages(session) }
                btnSharePdf.setOnClickListener { sharePdf(session) }
                btnDelete.setOnClickListener { deleteSession(session) }

                itemView.setOnClickListener { openDetail(session) }
            }

            private fun shareImages(session: CaptureSession) {
                val files = session.stitchedFileObjects(this@DashboardActivity)
                if (files.isEmpty()) {
                    Toast.makeText(this@DashboardActivity, "No images to share", Toast.LENGTH_SHORT).show()
                    return
                }
                if (files.size == 1) {
                    shareSingleImage(files.first())
                } else {
                    // Share multiple images
                    val uris = ArrayList<Uri>()
                    for (f in files) {
                        uris.add(FileProvider.getUriForFile(
                            this@DashboardActivity, "$packageName.fileprovider", f
                        ))
                    }
                    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/png"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share ${files.size} images"))
                }
            }

            private fun shareSingleImage(file: File) {
                val uri = FileProvider.getUriForFile(
                    this@DashboardActivity, "$packageName.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share image"))
            }

            private fun sharePdf(session: CaptureSession) {
                val pdfFile = session.pdfFile()
                if (pdfFile != null) {
                    val uri = FileProvider.getUriForFile(
                        this@DashboardActivity, "$packageName.fileprovider", pdfFile
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share PDF"))
                } else {
                    // Generate PDF on the fly
                    btnSharePdf.isEnabled = false
                    btnSharePdf.text = "Generating…"
                    scope.launch {
                        val files = session.stitchedFileObjects(this@DashboardActivity)
                        val pdf = withContext(Dispatchers.IO) {
                            PdfGenerator.createPdf(this@DashboardActivity, files)
                        }
                        if (pdf != null) {
                            val uri = FileProvider.getUriForFile(
                                this@DashboardActivity, "$packageName.fileprovider", pdf
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "Share PDF"))
                        } else {
                            Toast.makeText(this@DashboardActivity, "Failed to create PDF", Toast.LENGTH_SHORT).show()
                        }
                        btnSharePdf.text = "Share PDF"
                        btnSharePdf.isEnabled = true
                    }
                }
            }

            private fun openDetail(session: CaptureSession) {
                val intent = Intent(this@DashboardActivity, SessionDetailActivity::class.java).apply {
                    putExtra("SESSION_ID", session.id)
                }
                startActivity(intent)
            }

            private fun decodeSampled(file: File, reqW: Int, reqH: Int): android.graphics.Bitmap? {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) > reqW || opts.outHeight / (sample * 2) > reqH) {
                    sample *= 2
                }
                return BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        }
    }
}
