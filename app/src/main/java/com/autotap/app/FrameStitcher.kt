package com.autotap.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream

/**
 * Stitches a sequence of captured screen frames into one (or more) tall PNG
 * images using a [Canvas]. Frames are stacked vertically. To keep memory
 * bounded on very long captures the result is split into "pages" whenever the
 * accumulated height would exceed [maxPageHeight].
 *
 * Each frame is downscaled so its width does not exceed [maxWidth] before it is
 * drawn, which both reduces memory use and keeps the output readable.
 */
object FrameStitcher {

    fun stitch(
        frames: List<File>,
        outDir: File,
        maxWidth: Int = 1080,
        maxPageHeight: Int = 12000
    ): List<File> {
        if (frames.isEmpty()) return emptyList()
        outDir.mkdirs()

        val pages = mutableListOf<File>()
        var pageBitmaps = mutableListOf<Bitmap>()
        var pageHeight = 0
        var pageIndex = 0

        fun flush() {
            if (pageBitmaps.isEmpty()) return
            val width = pageBitmaps.maxOf { it.width }
            val out = Bitmap.createBitmap(width, pageHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            var y = 0
            for (b in pageBitmaps) {
                canvas.drawBitmap(b, ((width - b.width) / 2).toFloat(), y.toFloat(), null)
                y += b.height
                b.recycle()
            }
            val file = File(outDir, "stitched_%02d.png".format(pageIndex))
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle()
            pages.add(file)
            pageIndex++
            pageBitmaps = mutableListOf()
            pageHeight = 0
        }

        for (frame in frames) {
            val bmp = decodeScaled(frame, maxWidth) ?: continue
            if (pageBitmaps.isNotEmpty() && pageHeight + bmp.height > maxPageHeight) {
                flush()
            }
            pageBitmaps.add(bmp)
            pageHeight += bmp.height
        }
        flush()
        return pages
    }

    private fun decodeScaled(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth.takeIf { it > 0 } ?: return null
        val h = bounds.outHeight.takeIf { it > 0 } ?: return null

        var sample = 1
        while (w / (sample + 1) > maxWidth) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        return if (bmp.width <= maxWidth) {
            bmp
        } else {
            val scale = maxWidth.toFloat() / bmp.width
            val scaled = Bitmap.createScaledBitmap(
                bmp, maxWidth, (bmp.height * scale).toInt(), true
            )
            bmp.recycle()
            scaled
        }
    }
}
