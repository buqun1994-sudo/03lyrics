package com.ninepointnine.desktoplyrics.commercial

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

enum class DeviceChallengePurpose(val protocolValue: String) {
    TRIAL("trial"),
    PURCHASE("purchase"),
    REFRESH("refresh"),
    RECOVER("recover")
}

data class DeviceChallenge(
    val challengeId: String,
    val challengeBytes: ByteArray,
    val challengeBase64: String,
    val expiresAtEpochMs: Long,
    val productTitle: String?
)

data class DeviceChallengeProof(
    val challengeId: String,
    val challengeBase64: String,
    val signatureBase64: String
)

data class DeviceCampaign(val discountCode: String?)

data class DeviceQuotePayload(
    val quoteId: String,
    val productId: String,
    val sku: String,
    val productTitle: String,
    val listAmount: Int,
    val displayListAmount: String,
    val discountCode: String?,
    val discountStatus: String,
    val discountErrorCode: String?,
    val paymentRatioBps: Int,
    val discountLabel: String?,
    val calculatedAmount: Int,
    val finalAmount: Int,
    val displayFinalAmount: String,
    val minimumChargeApplied: Boolean,
    val currency: String,
    val payWays: List<String>,
    val expiresAtEpochMs: Long
)

data class DeviceAccessPayload(
    val status: String,
    val license: SignedLicenseEnvelope?,
    val trialStartedAtEpochMs: Long?,
    val trialEndsAtEpochMs: Long?,
    val deviceToken: String?,
    val keyVersion: Int?
)

data class DevicePurchasePayload(
    val purchaseId: String,
    val pollToken: String?,
    val status: String,
    val amount: Int,
    val displayAmount: String,
    val currency: String,
    val qrCodeFormat: String?,
    val qrCodeValue: String?,
    val expiresAtEpochMs: Long?,
    val pollAfterSeconds: Int,
    val paidAtEpochMs: Long?
)

data class DevicePurchaseResponse(
    val status: String,
    val product: DeviceQuotePayload?,
    val purchase: DevicePurchasePayload?,
    val license: SignedLicenseEnvelope?,
    val deviceToken: String?
)

enum class DeviceCommerceApiFailureKind {
    NETWORK,
    PROTOCOL,
    REMOTE
}

data class DeviceCommerceApiFailure(
    val kind: DeviceCommerceApiFailureKind,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val remoteStatus: String? = null,
    val latestQuote: DeviceQuotePayload? = null,
    val keyVersion: Int? = null,
    val trialEndsAtEpochMs: Long? = null
)

sealed interface DeviceCommerceApiResult<out T> {
    data class Success<T>(val value: T) : DeviceCommerceApiResult<T>
    data class Failure(val failure: DeviceCommerceApiFailure) : DeviceCommerceApiResult<Nothing>
}

interface DeviceCommerceApi {
    fun createChallenge(
        purpose: DeviceChallengePurpose,
        identity: DeviceCommercialIdentity
    ): DeviceCommerceApiResult<DeviceChallenge>

    fun readCurrentCampaign(): DeviceCommerceApiResult<DeviceCampaign>

    fun createQuote(
        deviceFingerprint: String,
        discountCode: String
    ): DeviceCommerceApiResult<DeviceQuotePayload>

    fun startTrial(
        proof: DeviceChallengeProof,
        firstOpenedAtEpochMs: Long
    ): DeviceCommerceApiResult<DeviceAccessPayload>

    fun createPurchase(
        proof: DeviceChallengeProof,
        quoteId: String,
        paymentMethod: PaymentMethod,
        clientVersion: String
    ): DeviceCommerceApiResult<DevicePurchaseResponse>

    fun readPurchase(
        purchaseId: String,
        pollToken: String,
        timestampEpochSeconds: Long,
        nonceBase64Url: String,
        signatureBase64: String
    ): DeviceCommerceApiResult<DevicePurchaseResponse>

    fun refreshLicense(
        proof: DeviceChallengeProof
    ): DeviceCommerceApiResult<DeviceAccessPayload>

    fun recover(
        proof: DeviceChallengeProof,
        previousKeySignatureBase64: String?
    ): DeviceCommerceApiResult<DeviceAccessPayload>
}

data class DeviceCommerceHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class DeviceCommerceHttpResponse(
    val statusCode: Int,
    val body: String
)

fun interface DeviceCommerceTransport {
    @Throws(IOException::class)
    fun execute(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse
}

class UrlConnectionDeviceCommerceTransport(
    apiBaseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000
) : DeviceCommerceTransport {
    private val normalizedBaseUrl = validateBaseUrl(apiBaseUrl)

    override fun execute(request: DeviceCommerceHttpRequest): DeviceCommerceHttpResponse {
        require(request.path.startsWith('/'))
        val connection = URL(normalizedBaseUrl + request.path)
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            request.headers.forEach(connection::setRequestProperty)
            request.body?.let { body ->
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_REQUEST_BYTES)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output -> output.write(bytes) }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Device commerce response too large")
                    output.write(buffer, 0, read)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }.orEmpty()
            return DeviceCommerceHttpResponse(statusCode, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = requireNotNull(runCatching { URI(normalized) }.getOrNull())
        require(uri.scheme == "https")
        require(!uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        return normalized
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 32 * 1024
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}

class DeviceCommerceJsonApi(
    private val transport: DeviceCommerceTransport
) : DeviceCommerceApi {
    override fun createChallenge(
        purpose: DeviceChallengePurpose,
        identity: DeviceCommercialIdentity
    ): DeviceCommerceApiResult<DeviceChallenge> = execute(
        DeviceCommerceHttpRequest(
            method = "POST",
            path = path("challenges"),
            body = JSONObject()
                .put("purpose", purpose.protocolValue)
                .put("deviceFingerprint", identity.deviceFingerprintSha256)
                .put("devicePublicKeySpkiBase64", identity.publicKeySpkiBase64)
                .put("packageName", DeviceCommerceProductContract.PACKAGE_NAME)
                .put("signingCertSha256", identity.signingCertSha256)
                .toString()
        )
    ) { json ->
        val challengeBase64 = json.requiredString("challengeBase64")
        val challengeBytes = decodeBase64(challengeBase64)
        require(challengeBytes.size == 32)
        DeviceChallenge(
            challengeId = json.requiredString("challengeId"),
            challengeBytes = challengeBytes,
            challengeBase64 = challengeBase64,
            expiresAtEpochMs = json.requiredInstant("expiresAt"),
            productTitle = json.optJSONObject("product")?.nullableString("title")
        )
    }

    override fun readCurrentCampaign(): DeviceCommerceApiResult<DeviceCampaign> = execute(
        DeviceCommerceHttpRequest(method = "GET", path = path("campaigns/current"))
    ) { json ->
        val campaign = json.optJSONObject("campaign")
        DeviceCampaign(
            discountCode = campaign?.nullableString("discountCode")
        )
    }

    override fun createQuote(
        deviceFingerprint: String,
        discountCode: String
    ): DeviceCommerceApiResult<DeviceQuotePayload> = execute(
        DeviceCommerceHttpRequest(
            method = "POST",
            path = path("quotes"),
            body = JSONObject()
                .put("deviceFingerprint", deviceFingerprint)
                .put("discountCode", discountCode)
                .toString()
        )
    ) { json -> parseQuote(json.requiredObject("quote")) }

    override fun startTrial(
        proof: DeviceChallengeProof,
        firstOpenedAtEpochMs: Long
    ): DeviceCommerceApiResult<DeviceAccessPayload> = execute(
        DeviceCommerceHttpRequest(
            method = "POST",
            path = path("trial/start"),
            body = proof.toJson()
                .put("firstOpenedAt", Instant.ofEpochMilli(firstOpenedAtEpochMs).toString())
                .toString()
        ),
        ::parseAccess
    )

    override fun createPurchase(
        proof: DeviceChallengeProof,
        quoteId: String,
        paymentMethod: PaymentMethod,
        clientVersion: String
    ): DeviceCommerceApiResult<DevicePurchaseResponse> = execute(
        DeviceCommerceHttpRequest(
            method = "POST",
            path = path("purchases"),
            body = proof.toJson()
                .put("quoteId", quoteId)
                .put("payWay", paymentMethod.protocolValue)
                .put("clientVersion", clientVersion)
                .put("runtimeIdentifier", DeviceCommerceProductContract.RUNTIME_IDENTIFIER)
                .put("locale", DeviceCommerceProductContract.LOCALE)
                .toString()
        ),
        ::parsePurchaseResponse
    )

    override fun readPurchase(
        purchaseId: String,
        pollToken: String,
        timestampEpochSeconds: Long,
        nonceBase64Url: String,
        signatureBase64: String
    ): DeviceCommerceApiResult<DevicePurchaseResponse> = execute(
        DeviceCommerceHttpRequest(
            method = "GET",
            path = path("purchases/$purchaseId"),
            headers = mapOf(
                "Authorization" to "Bearer $pollToken",
                "X-Device-Timestamp" to timestampEpochSeconds.toString(),
                "X-Device-Nonce" to nonceBase64Url,
                "X-Device-Signature" to signatureBase64
            )
        ),
        ::parsePurchaseResponse
    )

    override fun refreshLicense(
        proof: DeviceChallengeProof
    ): DeviceCommerceApiResult<DeviceAccessPayload> = execute(
        DeviceCommerceHttpRequest(
            method = "POST",
            path = path("license/refresh"),
            body = proof.toJson().toString()
        ),
        ::parseAccess
    )

    override fun recover(
        proof: DeviceChallengeProof,
        previousKeySignatureBase64: String?
    ): DeviceCommerceApiResult<DeviceAccessPayload> = execute(
        DeviceCommerceHttpRequest(
            method = "POST",
            path = path("recover"),
            body = proof.toJson().apply {
                previousKeySignatureBase64?.let {
                    put("previousKeySignatureBase64", it)
                }
            }.toString()
        ),
        ::parseAccess
    )

    private fun path(action: String): String =
        "/v1/products/${DeviceCommerceProductContract.PRODUCT_ID}/device-access/$action"

    private fun DeviceChallengeProof.toJson(): JSONObject = JSONObject()
        .put("challengeId", challengeId)
        .put("challengeBase64", challengeBase64)
        .put("signatureBase64", signatureBase64)

    private fun parseAccess(json: JSONObject): DeviceAccessPayload = DeviceAccessPayload(
        status = json.requiredString("status"),
        license = json.optJSONObject("license")?.let(::parseLicense),
        trialStartedAtEpochMs = json.optionalInstant("trialStartedAt"),
        trialEndsAtEpochMs = json.optionalInstant("trialEndsAt"),
        deviceToken = json.nullableString("deviceToken"),
        keyVersion = json.optionalPositiveInt("keyVersion")
    )

    private fun parsePurchaseResponse(json: JSONObject): DevicePurchaseResponse =
        DevicePurchaseResponse(
            status = json.requiredString("status"),
            product = json.optJSONObject("product")?.let(::parseProductSnapshot),
            purchase = json.optJSONObject("purchase")?.let(::parsePurchase),
            license = json.optJSONObject("license")?.let(::parseLicense),
            deviceToken = json.nullableString("deviceToken")
        )

    private fun parsePurchase(json: JSONObject): DevicePurchasePayload = DevicePurchasePayload(
        purchaseId = json.requiredString("purchaseId"),
        pollToken = json.nullableString("pollToken"),
        status = json.requiredString("status"),
        amount = json.requiredNonNegativeInt("amount"),
        displayAmount = json.requiredString("displayAmount"),
        currency = json.requiredString("currency"),
        qrCodeFormat = json.nullableString("qrCodeFormat"),
        qrCodeValue = json.nullableString("qrCodeValue"),
        expiresAtEpochMs = json.optionalInstant("expiresAt"),
        pollAfterSeconds = json.optionalPositiveInt("pollAfterSeconds") ?: 2,
        paidAtEpochMs = json.optionalInstant("paidAt")
    )

    private fun parseProductSnapshot(json: JSONObject): DeviceQuotePayload {
        val payWays = json.optJSONArray("payWays")?.let { array ->
            buildList {
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }.orEmpty()
        return DeviceQuotePayload(
            quoteId = json.nullableString("quoteId").orEmpty(),
            productId = json.requiredString("productId"),
            sku = json.requiredString("sku"),
            productTitle = json.requiredString("title"),
            listAmount = json.requiredNonNegativeInt("listAmount"),
            displayListAmount = json.requiredString("displayListAmount"),
            discountCode = json.nullableString("discountCode"),
            discountStatus = json.requiredString("discountStatus"),
            discountErrorCode = json.optJSONObject("discountError")?.nullableString("code"),
            paymentRatioBps = json.requiredRatio("paymentRatioBps"),
            discountLabel = json.nullableString("discountLabel"),
            calculatedAmount = json.requiredNonNegativeInt("calculatedAmount"),
            finalAmount = json.requiredNonNegativeInt("finalAmount"),
            displayFinalAmount = json.nullableString("displayFinalAmount")
                ?: json.requiredString("displayAmount"),
            minimumChargeApplied = json.requiredBoolean("minimumChargeApplied"),
            currency = json.requiredString("currency"),
            payWays = payWays,
            expiresAtEpochMs = json.optionalInstant("expiresAt") ?: Long.MAX_VALUE
        )
    }

    private fun parseQuote(json: JSONObject): DeviceQuotePayload {
        val payWaysArray = json.getJSONArray("payWays")
        val payWays = buildList {
            for (index in 0 until payWaysArray.length()) add(payWaysArray.getString(index))
        }
        return DeviceQuotePayload(
            quoteId = json.requiredString("quoteId"),
            productId = json.requiredString("productId"),
            sku = json.requiredString("sku"),
            productTitle = json.nullableString("title").orEmpty(),
            listAmount = json.requiredNonNegativeInt("listAmount"),
            displayListAmount = json.requiredString("displayListAmount"),
            discountCode = json.nullableString("discountCode"),
            discountStatus = json.requiredString("discountStatus"),
            discountErrorCode = json.optJSONObject("discountError")?.nullableString("code"),
            paymentRatioBps = json.requiredRatio("paymentRatioBps"),
            discountLabel = json.nullableString("discountLabel"),
            calculatedAmount = json.requiredNonNegativeInt("calculatedAmount"),
            finalAmount = json.requiredNonNegativeInt("finalAmount"),
            displayFinalAmount = json.requiredString("displayFinalAmount"),
            minimumChargeApplied = json.requiredBoolean("minimumChargeApplied"),
            currency = json.requiredString("currency"),
            payWays = payWays,
            expiresAtEpochMs = json.requiredInstant("expiresAt")
        )
    }

    private fun parseLicense(json: JSONObject): SignedLicenseEnvelope {
        val payload = decodeBase64(json.requiredString("payloadBase64"))
        val signature = decodeBase64(json.requiredString("signatureBase64"))
        require(payload.isNotEmpty() && signature.isNotEmpty())
        return SignedLicenseEnvelope(
            rawPayload = payload,
            signature = signature,
            keyId = json.requiredString("keyId")
        )
    }

    private fun <T> execute(
        request: DeviceCommerceHttpRequest,
        parse: (JSONObject) -> T
    ): DeviceCommerceApiResult<T> {
        val response = try {
            transport.execute(request)
        } catch (_: IOException) {
            return DeviceCommerceApiResult.Failure(
                DeviceCommerceApiFailure(DeviceCommerceApiFailureKind.NETWORK)
            )
        } catch (_: Exception) {
            return DeviceCommerceApiResult.Failure(
                DeviceCommerceApiFailure(DeviceCommerceApiFailureKind.PROTOCOL)
            )
        }

        val json = runCatching { JSONObject(response.body) }.getOrElse {
            return DeviceCommerceApiResult.Failure(
                DeviceCommerceApiFailure(
                    kind = if (response.statusCode in 500..599) {
                        DeviceCommerceApiFailureKind.NETWORK
                    } else {
                        DeviceCommerceApiFailureKind.PROTOCOL
                    },
                    httpStatus = response.statusCode
                )
            )
        }
        if (response.statusCode !in 200..299 || !json.optBoolean("ok", false)) {
            val latestQuote = runCatching {
                json.optJSONObject("latestQuote")?.let(::parseQuote)
            }.getOrNull()
            return DeviceCommerceApiResult.Failure(
                DeviceCommerceApiFailure(
                    kind = DeviceCommerceApiFailureKind.REMOTE,
                    httpStatus = response.statusCode,
                    errorCode = json.nullableString("error"),
                    remoteStatus = json.nullableString("status"),
                    latestQuote = latestQuote,
                    keyVersion = json.optionalPositiveInt("keyVersion"),
                    trialEndsAtEpochMs = json.optionalInstant("trialEndsAt")
                )
            )
        }
        return runCatching { DeviceCommerceApiResult.Success(parse(json)) }
            .getOrElse {
                DeviceCommerceApiResult.Failure(
                    DeviceCommerceApiFailure(
                        kind = DeviceCommerceApiFailureKind.PROTOCOL,
                        httpStatus = response.statusCode
                    )
                )
            }
    }

    private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun JSONObject.requiredObject(name: String): JSONObject = getJSONObject(name)

    private fun JSONObject.requiredString(name: String): String = getString(name).also {
        require(it.isNotBlank())
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)

    private fun JSONObject.requiredNonNegativeInt(name: String): Int = getInt(name).also {
        require(it >= 0)
    }

    private fun JSONObject.requiredRatio(name: String): Int = getInt(name).also {
        require(it in 0..10_000)
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean {
        require(has(name) && !isNull(name))
        return getBoolean(name)
    }

    private fun JSONObject.optionalPositiveInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name).also { require(it > 0) }

    private fun JSONObject.requiredInstant(name: String): Long =
        Instant.parse(requiredString(name)).toEpochMilli()

    private fun JSONObject.optionalInstant(name: String): Long? =
        nullableString(name)?.let { Instant.parse(it).toEpochMilli() }
}
