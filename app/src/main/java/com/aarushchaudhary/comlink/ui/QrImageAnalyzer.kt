package com.aarushchaudhary.comlink.ui

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class QrImageAnalyzer(
    private val onQrDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        val yuvImage = image.image
        if (yuvImage != null) {
            val yPlane = yuvImage.planes[0]
            // Rows can be padded past the image width; ZXing must step by the real row length
            val rowStride = yPlane.rowStride
            val yBuffer = yPlane.buffer
            val raw = ByteArray(yBuffer.remaining())
            yBuffer.get(raw)
            // The last row is often not padded, so the buffer can be a little short of stride x height
            val needed = rowStride * image.height
            val yuvData = if (raw.size >= needed) raw else raw.copyOf(needed)

            val decoded = QrUtils.decodeQr(yuvData, rowStride, image.width, image.height)
            if (decoded != null) {
                onQrDecoded(decoded)
            }
        }
        image.close()
    }
}
