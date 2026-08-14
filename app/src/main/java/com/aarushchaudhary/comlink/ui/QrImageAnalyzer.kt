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
            val yBuffer = yuvImage.planes[0].buffer
            val ySize = yBuffer.remaining()
            val yuvData = ByteArray(ySize)
            yBuffer.get(yuvData)
            
            val decoded = QrUtils.decodeQr(yuvData, image.width, image.height)
            if (decoded != null) {
                onQrDecoded(decoded)
            }
        }
        image.close()
    }
}
