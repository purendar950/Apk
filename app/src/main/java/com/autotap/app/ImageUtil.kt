package com.autotap.app

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image

/**
 * Convert an [Image] produced by an RGBA_8888 [android.media.ImageReader] into a
 * [Bitmap]. The image is closed by this function; do not reuse it afterwards.
 */
fun imageToBitmap(image: Image): Bitmap {
    require(image.format == 0x1) {
        "Expected RGBA_8888 image, got format ${image.format}"
    }
    val width = image.width
    val height = image.height
    val plane = image.planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    val buffer = plane.buffer
    val padded = Bitmap.createBitmap(
        width + rowPadding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888
    )
    padded.copyPixelsFromBuffer(buffer)
    image.close()

    return if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
}
