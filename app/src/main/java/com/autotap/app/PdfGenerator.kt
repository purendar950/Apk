package com.autotap.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Converts stitched PNG images into a multi-page PDF.
 * Uses Android's built-in PdfDocument API (no external dependencies).
 */
object PdfGenerator {

    private const val TAG = "AutoTapPdf"

    /**
     * Create a PDF from the given stitched image files.
     * Each image becomes one page in the PDF.
     * Returns the output PDF file, or null on failure.
     */
    fun createPdf(context: Context, imageFiles: List<File>): File? {
        if (imageFiles.isEmpty()) {
            Log.w(TAG, "No images to convert")
            return null
        }

        val outDir = File(context.filesDir, "pdf").apply { mkdirs() }
        val pdfFile = File(outDir, "AutoTap_${System.currentTimeMillis()}.pdf")
        val document = PdfDocument()

        try {
            for ((index, imageFile) in imageFiles.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: continue

                // A4-ish page: 595 x 842 points (72 dpi)
                val pageWidth = 595
                val pageHeight = 842

                // Scale bitmap to fit within the page width
                val scale = pageWidth.toFloat() / bitmap.width
                val scaledWidth = pageWidth
                val scaledHeight = (bitmap.height * scale).toInt()

                // If the scaled image is taller than one page, we tile it
                val totalPages = Math.ceil(scaledHeight.toDouble() / pageHeight).toInt().coerceAtLeast(1)

                for (page in 0 until totalPages) {
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index * 100 + page).create()
                    val pdfPage = document.startPage(pageInfo)
                    val canvas: Canvas = pdfPage.canvas

                    // Fill white background
                    canvas.drawColor(Color.WHITE)

                    // Crop the source bitmap for this page slice
                    val srcY = (page * pageHeight / scale).toInt()
                    val srcHeight = (pageHeight / scale).toInt().coerceAtMost(bitmap.height - srcY)

                    if (srcY < bitmap.height) {
                        val slice = Bitmap.createBitmap(
                            bitmap,
                            0,
                            srcY.coerceIn(0, bitmap.height - 1),
                            bitmap.width,
                            srcHeight.coerceAtLeast(1)
                        )
                        val sliceScaledH = (srcHeight * scale).toInt()
                        val drawBitmap = Bitmap.createScaledBitmap(slice, scaledWidth, sliceScaledH, true)

                        // Center vertically on page
                        val yOffset = ((pageHeight - sliceScaledH) / 2f).coerceAtLeast(0f)
                        canvas.drawBitmap(drawBitmap, 0f, yOffset, null)

                        if (drawBitmap !== slice) drawBitmap.recycle()
                        slice.recycle()
                    }

                    document.finishPage(pdfPage)
                }

                bitmap.recycle()
                Log.d(TAG, "Added image ${imageFile.name} ($totalPages page(s))")
            }

            FileOutputStream(pdfFile).use { out ->
                document.writeTo(out)
            }
            Log.d(TAG, "PDF created: ${pdfFile.absolutePath} (${pdfFile.length()} bytes)")
            return pdfFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PDF", e)
            pdfFile.delete()
            return null
        } finally {
            document.close()
        }
    }

    /**
     * Save the PDF to the system Pictures directory so it shows in file managers.
     */
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
