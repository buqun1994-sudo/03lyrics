package com.ninepointnine.desktoplyrics

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Builds the public agreement QR locally so the settings page works offline. */
object TermsQrCodeGenerator {
    fun encode(value: String, size: Int): BitMatrix {
        require(value.startsWith("https://")) { "Agreement URL must use HTTPS" }
        require(size > 0) { "QR size must be positive" }
        return MultiFormatWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2
            )
        )
    }

    fun createBitmap(value: String, size: Int): Bitmap {
        val matrix = encode(value, size)
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
