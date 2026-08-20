package com.ninepointnine.desktoplyrics.commercial

import android.graphics.BitmapFactory
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

object RemotePaymentQrLoader {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "commercial-qr-loader").apply { isDaemon = true }
    }

    fun render(image: ImageView, value: String, onResult: (Boolean) -> Unit) {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
            onResult(false)
            return
        }
        image.tag = value
        executor.execute {
            val bitmap = runCatching {
                val connection = URL(value).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.useCaches = false
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "image/png,image/jpeg,image/webp")
                    require(connection.responseCode in 200..299)
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_IMAGE_BYTES)
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    require(bytes.isNotEmpty())
                    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            image.post {
                if (image.tag == value) {
                    image.setImageBitmap(bitmap)
                    onResult(bitmap != null)
                }
            }
        }
    }

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
}
