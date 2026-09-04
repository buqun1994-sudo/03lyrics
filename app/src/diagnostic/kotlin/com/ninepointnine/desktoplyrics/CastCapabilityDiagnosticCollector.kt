package com.ninepointnine.desktoplyrics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.resume

/**
 * Read-only capability evidence for the independent 03投屏 application.
 * This class never loads its native libraries and never starts the receiver.
 */
internal class CastCapabilityDiagnosticCollector(
    private val context: Context,
) {
    private val packageManager = context.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun collect(): JSONObject {
        val result = JSONObject()
            .put("observedAtEpochMs", System.currentTimeMillis())
            .put("collector", "03lyrics-debug-cast-capability")
        result.put("device", collectDevice())
        result.put("targetPackages", collectTargetPackages())
        result.put("network", collectNetwork())
        result.put("mediaCodec", collectCodecs())
        result.put("automotive", collectAutomotive())
        result.put("window", collectWindow())
        return result
    }

    fun installedTargetPackages(): List<String> = TARGET_PACKAGES.filter(::isPackageInstalled)

    private fun collectDevice(): JSONObject {
        val metrics = context.resources.displayMetrics
        val display: Display? = runCatching {
            context.getSystemService(WindowManager::class.java).defaultDisplay
        }.getOrNull()
        var width = 0
        var height = 0
        display?.let {
            runCatching {
                val size = android.graphics.Point()
                it.getRealSize(size)
                width = size.x
                height = size.y
            }
        }
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("board", Build.BOARD)
            .put("hardware", Build.HARDWARE)
            .put("fingerprint", Build.FINGERPRINT)
            .put("release", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("displayWidthPx", width)
            .put("displayHeightPx", height)
            .put("displayDensity", metrics.density)
            .put("displayDensityDpi", metrics.densityDpi)
            .put("displayRefreshHz", display?.refreshRate ?: 0f)
    }

    private fun collectTargetPackages(): JSONArray = JSONArray().also { packages ->
        TARGET_PACKAGES.forEach { packageName -> packages.put(collectPackage(packageName)) }
    }

    private fun collectPackage(packageName: String): JSONObject {
        val result = JSONObject().put("packageName", packageName)
        val info = getPackageInfo(packageName)
            ?: return result.put("installed", false)
        val applicationInfo = info.applicationInfo
        result
            .put("installed", true)
            .put("versionName", info.versionName ?: "")
            .put("versionCode", versionCode(info))
            .put("firstInstallTime", info.firstInstallTime)
            .put("lastUpdateTime", info.lastUpdateTime)
            .put("sourceDir", applicationInfo?.sourceDir ?: "")
            .put("nativeLibraryDir", applicationInfo?.nativeLibraryDir ?: "")
            .put("targetSdk", applicationInfo?.targetSdkVersion ?: 0)
            .put("minSdk", applicationInfo?.minSdkVersion ?: 0)
            .put("enabled", applicationInfo?.enabled == true)
        result.put("signing", signingSummary(info))
        result.put("nativeLibraries", nativeLibrarySummary(applicationInfo?.sourceDir))
        result.put("activities", activitiesSummary(info))
        result.put("services", servicesSummary(info))
        result.put(
            "declaredCapabilities",
            JSONObject()
                .put("hasMainActivity", hasActivity(info, "MainActivity"))
                .put("hasFullscreenActivity", hasActivity(info, "FullscreenActivity"))
                .put("hasCastService", hasService(info, "CastService"))
                .put("androidCarOptionalLibraryStaticCheck", "adb_required"),
        )
        return result
    }

    private fun getPackageInfo(packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_ACTIVITIES or
                        PackageManager.GET_SERVICES or
                        PackageManager.GET_SIGNING_CERTIFICATES).toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }
    }.getOrNull()

    private fun signingSummary(info: PackageInfo): JSONObject {
        val signers: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return JSONObject()
            .put("signerCount", signers.size)
            .put("sha256", JSONArray(signers.map { sha256(it.toByteArray()) }))
            .put("multipleSigners", signers.size != 1)
    }

    private fun nativeLibrarySummary(sourceDir: String?): JSONObject {
        val result = JSONObject()
        if (sourceDir.isNullOrBlank()) return result.put("error", "source_dir_missing")
        val entries = runCatching {
            ZipFile(File(sourceDir)).use { zip ->
                val names = mutableListOf<String>()
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val name = iterator.nextElement().name
                    if (name.startsWith("lib/")) names += name
                }
                names.sorted()
            }
        }.getOrElse { return result.put("error", errorSummary(it)).put("adbRequired", true) }
        val byAbi = JSONObject()
        entries.groupBy { it.substringBeforeLast('/').substringAfter("lib/") }
            .forEach { (abi, names) -> byAbi.put(abi, JSONArray(names)) }
        val expected = listOf("libairplay_native.so", "libc++_shared.so", "libcrypto.so", "liboboe.so")
        val arm64 = entries.filter { it.startsWith("lib/arm64-v8a/") }
            .map { it.substringAfterLast('/') }
            .toSet()
        return result
            .put("entries", byAbi)
            .put("expectedArm64Libraries", JSONArray(expected))
            .put("missingExpectedArm64Libraries", JSONArray(expected.filterNot(arm64::contains)))
            .put("arm64Complete", expected.all(arm64::contains))
            .put("deviceSupportsArm64", Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
    }

    private fun activitiesSummary(info: PackageInfo): JSONArray = JSONArray().also { array ->
        info.activities.orEmpty().forEach { activity ->
            array.put(
                JSONObject()
                    .put("name", activity.name)
                    .put("enabled", activity.enabled)
                    .put("exported", activity.exported)
                    .put("launchMode", activity.launchMode)
                    .put("hasStandardWindowMetadata", activity.metaData?.getBoolean(
                        "com.tcrrry.icar.window.STANDARD_FLOATING_WINDOW",
                        false,
                    ) == true),
            )
        }
    }

    private fun servicesSummary(info: PackageInfo): JSONArray = JSONArray().also { array ->
        info.services.orEmpty().forEach { service ->
            array.put(
                JSONObject()
                    .put("name", service.name)
                    .put("enabled", service.enabled)
                    .put("exported", service.exported)
                    .put("permission", service.permission ?: ""),
            )
        }
    }

    private fun hasActivity(info: PackageInfo, simpleName: String): Boolean =
        info.activities.orEmpty().any { it.name.substringAfterLast('.') == simpleName }

    private fun hasService(info: PackageInfo, simpleName: String): Boolean =
        info.services.orEmpty().any { it.name.substringAfterLast('.') == simpleName }

    private suspend fun collectNetwork(): JSONObject {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = runCatching { connectivity.activeNetwork }.getOrNull()
        val capabilities = activeNetwork?.let {
            runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull()
        }
        return JSONObject()
            .put("activeNetwork", activeNetwork != null)
            .put("capabilities", networkCapabilities(capabilities))
            .put(
                "linkProperties",
                activeNetwork?.let { networkLinkProperties(connectivity, it) }
                    ?: JSONObject().put("available", false),
            )
            .put("interfaces", networkInterfaces())
            .put("wifi", wifiSummary())
            .put("multicastLock", multicastLockProbe())
            .put("localServicePorts", localServicePorts())
            .put("ssdpProbe", withContext(Dispatchers.IO) { ssdpProbe() })
            .put(
                "mdnsDiscovery",
                JSONObject()
                    .put("airplay", discoverNsd("_airplay._tcp."))
                    .put("raop", discoverNsd("_raop._tcp.")),
            )
    }

    private fun networkCapabilities(capabilities: NetworkCapabilities?): JSONObject {
        if (capabilities == null) return JSONObject().put("available", false)
        return JSONObject()
            .put("available", true)
            .put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .put("notMetered", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            .put("wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            .put("ethernet", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            .put("vpn", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            .put("internet", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
    }

    private fun networkLinkProperties(
        connectivity: ConnectivityManager,
        network: android.net.Network,
    ): JSONObject = runCatching {
        val properties = connectivity.getLinkProperties(network)
        JSONObject()
            .put("interfaceName", properties?.interfaceName ?: "")
            .put("addresses", JSONArray(properties?.linkAddresses.orEmpty().map { it.address.hostAddress }))
            .put("dnsServers", JSONArray(properties?.dnsServers.orEmpty().map { it.hostAddress }))
            .put("routes", JSONArray(properties?.routes.orEmpty().map { it.toString() }))
    }.getOrElse { JSONObject().put("error", errorSummary(it)) }

    private fun networkInterfaces(): JSONArray = JSONArray().also { array ->
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
                .filter { it.isUp }
                .forEach { networkInterface ->
                    array.put(
                        JSONObject()
                            .put("name", networkInterface.name)
                            .put("displayName", networkInterface.displayName)
                            .put("up", networkInterface.isUp)
                            .put("loopback", networkInterface.isLoopback)
                            .put("addresses", JSONArray(
                                networkInterface.inetAddresses.asSequence()
                                    .mapNotNull { it.hostAddress }
                                    .toList(),
                            )),
                    )
                }
        }.onFailure { array.put(JSONObject().put("error", errorSummary(it))) }
    }

    private fun wifiSummary(): JSONObject = runCatching {
        val wifi = context.getSystemService(WifiManager::class.java)
        val connection = wifi.connectionInfo
        JSONObject()
            .put("servicePresent", true)
            .put("enabled", wifi.isWifiEnabled)
            .put("linkSpeedMbps", connection.linkSpeed)
            .put("frequencyMHz", connection.frequency)
            .put("networkId", connection.networkId)
            .put("ssidPresent", !connection.ssid.isNullOrBlank() && connection.ssid != "<unknown ssid>")
    }.getOrElse { JSONObject().put("servicePresent", false).put("error", errorSummary(it)) }

    private fun multicastLockProbe(): JSONObject = runCatching {
        val wifi = context.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("03lyrics-diagnostic").apply { setReferenceCounted(false) }
        lock.acquire()
        val held = lock.isHeld
        lock.release()
        JSONObject().put("acquireSucceeded", held).put("released", !lock.isHeld)
    }.getOrElse { JSONObject().put("acquireSucceeded", false).put("error", errorSummary(it)) }

    private fun localServicePorts(): JSONArray = JSONArray().also { array ->
        val address = localIpv4Address()
        EXPECTED_PORTS.forEach { (port, protocol, role) ->
            val item = JSONObject()
                .put("port", port)
                .put("protocol", protocol)
                .put("role", role)
            if (protocol == "tcp") {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), 350)
                    }
                    item.put("localListener", "open")
                }.onFailure { error ->
                    val message = error.message.orEmpty().lowercase(Locale.ROOT)
                    item.put(
                        "localListener",
                        if (message.contains("refused")) "closed" else "unreachable_or_timeout",
                    )
                    item.put("error", errorSummary(error))
                }
            } else {
                item.put("localListener", "adb_required")
            }
            array.put(item)
        }
    }

    private fun localIpv4Address(): InetAddress = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
    }.getOrNull() ?: InetAddress.getByName("127.0.0.1")

    private suspend fun ssdpProbe(): JSONObject {
        val result = JSONObject().put("destination", "239.255.255.250:1900")
        val responses = JSONArray()
        var lock: WifiManager.MulticastLock? = null
        runCatching {
            val wifi = context.getSystemService(WifiManager::class.java)
            lock = wifi.createMulticastLock("03lyrics-diagnostic-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
            DatagramSocket().use { socket ->
                socket.soTimeout = 250
                val request = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: ssdp:all\r\n\r\n"
                val bytes = request.toByteArray(StandardCharsets.US_ASCII)
                socket.send(DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName("239.255.255.250"),
                    1900,
                ))
                val deadline = System.currentTimeMillis() + 1_200L
                while (System.currentTimeMillis() < deadline && responses.length() < 24) {
                    val buffer = ByteArray(2_048)
                    val response = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(response) }.onSuccess {
                        val firstLine = String(
                            response.data,
                            0,
                            response.length,
                            StandardCharsets.US_ASCII,
                        ).lineSequence().firstOrNull().orEmpty()
                        responses.put(JSONObject()
                            .put("address", response.address.hostAddress)
                            .put("port", response.port)
                            .put("firstLine", firstLine.take(160)))
                    }
                }
            }
            result.put("sendSucceeded", true)
        }.onFailure { result.put("sendSucceeded", false).put("error", errorSummary(it)) }
        lock?.let { runCatching { if (it.isHeld) it.release() } }
        return result.put("responseCount", responses.length()).put("responses", responses)
    }

    private suspend fun discoverNsd(serviceType: String): JSONObject =
        withTimeoutOrNull(NSD_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val names = JSONArray()
                val nsd = context.getSystemService(NsdManager::class.java)
                var stopped = false
                lateinit var listener: NsdManager.DiscoveryListener
                fun stop() {
                    if (stopped) return
                    stopped = true
                    runCatching { nsd.stopServiceDiscovery(listener) }
                }
                fun finish(error: Throwable? = null) {
                    if (!continuation.isActive) return
                    stop()
                    val value = JSONObject()
                        .put("serviceType", serviceType)
                        .put("serviceCount", names.length())
                        .put("services", names)
                    error?.let { value.put("error", errorSummary(it)) }
                    continuation.resume(value)
                }
                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) = Unit
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        names.put(JSONObject()
                            .put("serviceName", serviceInfo.serviceName ?: "")
                            .put("serviceType", serviceInfo.serviceType ?: ""))
                    }
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onDiscoveryStopped(regType: String) = Unit
                    override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                        finish(IllegalStateException("nsd_start_$errorCode"))
                    }
                    override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                        finish(IllegalStateException("nsd_stop_$errorCode"))
                    }
                }
                continuation.invokeOnCancellation { stop() }
                mainHandler.post {
                    if (!continuation.isActive) return@post
                    runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                        .onFailure(::finish)
                }
                mainHandler.postDelayed({ finish() }, NSD_TIMEOUT_MS - 50L)
            }
        } ?: JSONObject()
            .put("serviceType", serviceType)
            .put("timedOut", true)

    private fun collectCodecs(): JSONObject = runCatching {
        val candidates = JSONArray()
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        codecInfos.forEach { info ->
            if (info.isEncoder) return@forEach
            info.supportedTypes.map(String::lowercase).filter { it == "video/avc" || it == "video/hevc" }
                .forEach { mime ->
                    val item = JSONObject().put("name", info.name).put("mimeType", mime)
                    runCatching {
                        val capabilities = info.getCapabilitiesForType(mime)
                        val video = capabilities.videoCapabilities
                        val colors = capabilities.colorFormats?.toList().orEmpty()
                        val format = MediaFormat.createVideoFormat(mime, 1920, 1080)
                        item
                            .put("hardware", isHardwareCodec(info))
                            .put("softwareOnly", if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else !isHardwareCodec(info))
                            .put("hardwareAccelerated", if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else isHardwareCodec(info))
                            .put("reportedMaxWidth", video?.supportedWidths?.upper ?: 0)
                            .put("reportedMaxHeight", video?.supportedHeights?.upper ?: 0)
                            .put("reportedMaxFrameRate", video?.supportedFrameRates?.upper ?: 0.0)
                            .put("surfaceFormatAdvertised", colors.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
                            .put("supports1080pFormat", runCatching { capabilities.isFormatSupported(format) }.getOrDefault(false))
                            .put("colorFormats", JSONArray(colors))
                    }.onFailure { item.put("capabilityError", errorSummary(it)) }
                    candidates.put(item)
                }
        }
        JSONObject()
            .put("enumerationSucceeded", true)
            .put("codecCount", codecInfos.size)
            .put("videoCandidates", candidates)
            .put("configurationStartProbe", "not_run_by_diagnostic_app")
    }.getOrElse { JSONObject().put("enumerationSucceeded", false).put("error", errorSummary(it)) }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        val name = info.name.lowercase(Locale.ROOT)
        return !name.startsWith("omx.google.") &&
            !name.startsWith("c2.android.") &&
            !name.startsWith("avcodec.") &&
            !name.contains("software")
    }

    private suspend fun collectAutomotive(): JSONObject {
        val result = JSONObject()
            .put("featureAutomotive", packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            .put("carClassPresent", classPresent("android.car.Car"))
            .put("vehiclePropertyIdsClassPresent", classPresent("android.car.VehiclePropertyIds"))
            .put("carPowertrainPermissionDeclared", ownPermissionDeclared("android.car.permission.CAR_POWERTRAIN"))
            .put("carPowertrainPermissionGranted", ownPermissionGranted("android.car.permission.CAR_POWERTRAIN"))
        if (result.optBoolean("featureAutomotive") && result.optBoolean("carClassPresent")) {
            result.put("connection", connectAndReadGear())
        } else {
            result.put("connection", JSONObject().put("attempted", false))
        }
        return result
    }

    private suspend fun connectAndReadGear(): JSONObject = withTimeoutOrNull(CAR_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            var car: Any? = null
            lateinit var connection: android.content.ServiceConnection
            fun finish(value: JSONObject) {
                if (!continuation.isActive) return
                runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
                continuation.resume(value)
            }
            connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                    mainHandler.post {
                        val value = runCatching {
                            val connectedCar = car ?: error("car_null")
                            val manager = connectedCar.javaClass
                                .getMethod("getCarManager", String::class.java)
                                .invoke(connectedCar, "property")
                            val ids = Class.forName("android.car.VehiclePropertyIds")
                            val gearClass = Class.forName("android.car.VehicleGear")
                            val currentId = staticInt(ids, "CURRENT_GEAR", 289408001)
                            val selectionId = staticInt(ids, "GEAR_SELECTION", 289408000)
                            val park = staticInt(gearClass, "GEAR_PARK", 4)
                            val current = readCarInt(manager, currentId)
                            val selection = readCarInt(manager, selectionId)
                            JSONObject()
                                .put("attempted", true)
                                .put("propertyManager", manager != null)
                                .put("currentGearPropertyId", currentId)
                                .put("gearSelectionPropertyId", selectionId)
                                .put("parkValue", park)
                                .put("currentGear", current ?: JSONObject.NULL)
                                .put("gearSelection", selection ?: JSONObject.NULL)
                        }.getOrElse { JSONObject().put("attempted", true).put("error", errorSummary(it)) }
                        finish(value)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finish(JSONObject().put("attempted", true).put("error", "car_service_disconnected"))
                }
            }
            continuation.invokeOnCancellation {
                runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
            }
            runCatching {
                val carClass = Class.forName("android.car.Car")
                val create = carClass.getMethod(
                    "createCar",
                    Context::class.java,
                    android.content.ServiceConnection::class.java,
                )
                car = create.invoke(null, context.applicationContext, connection)
                    ?: error("createCar_returned_null")
                car?.javaClass?.getMethod("connect")?.invoke(car)
            }.onFailure { finish(JSONObject().put("attempted", true).put("error", errorSummary(it))) }
        }
    } ?: JSONObject().put("attempted", true).put("timedOut", true)

    private fun readCarInt(manager: Any?, propertyId: Int): Int? = runCatching {
        manager?.javaClass?.getMethod(
            "getIntProperty",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )?.invoke(manager, propertyId, 0) as? Int
    }.getOrNull()

    private fun staticInt(clazz: Class<*>, field: String, fallback: Int): Int = runCatching {
        clazz.getField(field).getInt(null)
    }.getOrDefault(fallback)

    private fun classPresent(name: String): Boolean = runCatching {
        Class.forName(name)
        true
    }.getOrDefault(false)

    private fun ownPermissionDeclared(permission: String): Boolean =
        getPackageInfo(context.packageName)?.requestedPermissions?.contains(permission) == true

    private fun ownPermissionGranted(permission: String): Boolean =
        packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED

    private fun collectWindow(): JSONObject {
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        val size = android.graphics.Point()
        display.getRealSize(size)
        val providers = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(
                Intent("com.tcrrry.icar.surface.action.ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE"),
                0,
            ).map { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" }
        }.getOrElse { emptyList() }
        return JSONObject()
            .put("realWidthPx", size.x)
            .put("realHeightPx", size.y)
            .put("density", context.resources.displayMetrics.density)
            .put("rotation", display.rotation)
            .put("ownOverlayPermission", runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false))
            .put("fullDisplayLeaseProviders", JSONArray(providers))
            .put("expectedTargetLeaseProviders", JSONArray(
                providers.filter { it.startsWith("com.ninepointnine.desktopcast") },
            ))
    }

    private fun isPackageInstalled(packageName: String): Boolean = getPackageInfo(packageName) != null

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    private fun errorSummary(error: Throwable): String =
        "${error.javaClass.name}:${error.message.orEmpty()}".take(400)

    private companion object {
        val TARGET_PACKAGES = listOf(
            "com.ninepointnine.desktopcast",
            "com.ninepointnine.desktopcast.test",
        )
        val EXPECTED_PORTS = listOf(
            Triple(7000, "tcp", "airplay_http"),
            Triple(8200, "tcp", "dlna_http"),
            Triple(1900, "udp", "dlna_ssdp"),
        )
        const val NSD_TIMEOUT_MS = 2_500L
        const val CAR_TIMEOUT_MS = 2_500L
    }
}
