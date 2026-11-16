package com.aarushchaudhary.comlink.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class QrUtilsTest {

    /** A camera-style Y plane: each row is [stride] bytes, of which only [size] are the image. */
    private fun paddedPlane(text: String, size: Int, stride: Int): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val random = Random(42)
        val plane = ByteArray(stride * size) { random.nextInt(256).toByte() } // garbage in the padding
        for (y in 0 until size) for (x in 0 until size) {
            plane[y * stride + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
        }
        return plane
    }

    @Test
    fun decodesWithPaddedRows() {
        val plane = paddedPlane("comlink-contact", size = 240, stride = 256)
        assertEquals("comlink-contact", QrUtils.decodeQr(plane, rowStride = 256, width = 240, height = 240))
    }

    @Test
    fun decodesWithoutPadding() {
        val plane = paddedPlane("no padding", size = 240, stride = 240)
        assertEquals("no padding", QrUtils.decodeQr(plane, rowStride = 240, width = 240, height = 240))
    }

    @Test
    fun ignoringTheStrideFails() {
        // What the old code did: treat the padded plane as if rows were exactly `width` long
        val plane = paddedPlane("comlink-contact", size = 240, stride = 256)
        assertNotEquals("comlink-contact", QrUtils.decodeQr(plane, rowStride = 240, width = 240, height = 240))
    }
}
