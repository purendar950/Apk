package com.autotap.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.min

object PdfGenerator {

    private const val TAG = "AutoTapPdf"

    /**
     * Create a full-resolution PDF from stitched PNG images.
     * Each page matches the original image width — no downscaling.
     * Long images are tiled across multiple pages at full quality.
     */
    fun createPdf(context: Context, imageFiles: List<File>): File? {
        if (imageFiles.isEmpty()) return null

        val outDir = File(context.filesDir, "pdf").apply { mkdirs() }
        val pdfFile = File(outDir, "AutoTap_${System.currentTimeMillis()}.pdf")
        val document = PdfDocument()

        try {
            var pageNum = 0

            for (imageFile in imageFiles) {
                // Decode at FULL resolution — no inSampleSize
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    ?: continue

                val imgWidth = bitmap.width
                val imgHeight = bitmap.height

                // Page size matches the image exactly (1 point = 1 pixel at 72 dpi)
                // For a 1080px wide screenshot, page is 1080pt wide
                val pageWidth = imgWidth
                val pageHeight = 842  // A4 height as baseline

                // If image fits on one page, use its full height
                val totalPages = if (imgHeight <= pageHeight) {
                    1
                } else {
                    ceil(imgHeight.toDouble() / pageHeight).toInt()
                }

                for (page in 0 until totalPages) {
                    pageNum++
                    val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                    val pdfPage = document.startPage(info)
                    val canvas: Canvas = pdfPage.canvas
                    canvas.drawColor(Color.WHITE)

                    // Calculate source crop region
                    val srcY = (page * imgHeight / totalPages)
                    val srcH = min(imgHeight - srcY, imgHeight / totalPages + 1)

                    if (srcY < imgHeight && srcH > 0) {
                        val slice = Bitmap.createBitmap(bitmap, 0, srcY, imgWidth, srcH)
                        // Draw at 1:1 scale — full resolution, no quality loss
                        canvas.drawBitmap(slice, 0f, 0f, null)
                        slice.recycle()
                    }

                    document.finishPage(pdfPage)
                }

                bitmap.recycle()
                Log.d(TAG, "Added ${imageFile.name}: ${imgWidth}x${imgHeight}, $totalPages page(s)")
            }

            FileOutputStream(pdfFile).use { out ->
                document.writeTo(out)
            }
            Log.d(TAG, "PDF created: ${pdfFile.length()} bytes")
            return pdfFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PDF", e)
            pdfFile.delete()
            return null
        } finally {
            document.close()
        }
    }

    fun saveToGallery(context: Context, pdfFile: File): File? {
        val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS
        )
        val destDir = File(picturesDir, "AutoTap")
        destDir.mkdirs()
        val destFile = File(destDir, pdfFile.name)
        return try {
            pdfFile.copyTo(destFile, overwrite = true)
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save PDF to gallery", e)
            null
        }
    }
}
