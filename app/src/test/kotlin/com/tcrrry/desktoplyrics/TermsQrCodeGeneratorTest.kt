package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsQrCodeGeneratorTest {
    @Test
    fun `injected agreement environment and URL stay consistent and decode locally`() {
        val expectedUrl = when (BuildConfig.USER_AGREEMENT_ENVIRONMENT) {
            "staging" -> "https://staging.9studio.fun/icar03/terms"
            "production" -> "https://9.9studio.fun/icar03/terms"
            else -> error("Unexpected agreement environment")
        }
        assertEquals(expectedUrl, BuildConfig.USER_AGREEMENT_URL)

        val matrix = TermsQrCodeGenerator.encode(BuildConfig.USER_AGREEMENT_URL, 177)
        assertEquals(177, matrix.width)
        assertEquals(177, matrix.height)
        assertEquals(
            BuildConfig.USER_AGREEMENT_URL,
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(matrixLuminance(matrix)))).text
        )
    }

    @Test
    fun `production agreement address produces a different local QR`() {
        val staging = TermsQrCodeGenerator.encode(
            "https://staging.9studio.fun/icar03/terms",
            177
        )
        val production = TermsQrCodeGenerator.encode(
            "https://9.9studio.fun/icar03/terms",
            177
        )

        var differs = false
        for (row in 0 until staging.height) {
            for (column in 0 until staging.width) {
                if (staging[column, row] != production[column, row]) {
                    differs = true
                    break
                }
            }
            if (differs) break
        }
        assertTrue(differs)
    }

    private fun matrixLuminance(matrix: com.google.zxing.common.BitMatrix): LuminanceSource {
        return object : LuminanceSource(matrix.width, matrix.height) {
            private val values = ByteArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) 0 else 0xFF.toByte()
            }

            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                val target = row?.takeIf { it.size >= width } ?: ByteArray(width)
                values.copyInto(target, 0, y * width, (y + 1) * width)
                return target
            }

            override fun getMatrix(): ByteArray = values.copyOf()
        }
    }
}
