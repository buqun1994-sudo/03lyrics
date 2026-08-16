import java.io.File
import java.util.Properties

fun String.asBuildConfigString(): String = buildString {
    append('"')
    this@asBuildConfigString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

fun Properties.requiredValue(name: String): String =
    getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Staging APK signing property '$name' is required")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingPropertiesFile = rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

val debugCommerceEnvironment = providers.gradleProperty("deviceCommerceEnvironment")
    .orElse("fixture")
    .get()
    .trim()
    .lowercase()
val stagingCommerceApiBaseUrl = providers.gradleProperty("deviceCommerceStagingApiBaseUrl")
    .orElse("")
    .get()
    .trim()
val debugCommerceApiBaseUrl = if (debugCommerceEnvironment == "staging") {
    stagingCommerceApiBaseUrl
} else {
    ""
}
val stagingLicenseKeyId = providers.gradleProperty("deviceCommerceStagingLicenseKeyId")
    .orElse("")
    .get()
val stagingLicensePublicKeyBase64 = providers
    .gradleProperty("deviceCommerceStagingLicensePublicKeyBase64")
    .orElse("")
    .get()
val productionApiBaseUrl = providers.gradleProperty("deviceCommerceProductionApiBaseUrl")
    .orElse("")
    .get()
val productionLicenseKeyId = providers.gradleProperty("deviceCommerceProductionLicenseKeyId")
    .orElse("")
    .get()
val productionLicensePublicKeyBase64 = providers
    .gradleProperty("deviceCommerceProductionLicensePublicKeyBase64")
    .orElse("")
    .get()

val stagingSigningPropertiesFile = providers
    .gradleProperty("deviceCommerceStagingSigningPropertiesFile")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(rootProject::file)
val stagingSigningProperties = Properties()
val stagingSigningStoreFile = if (debugCommerceEnvironment == "staging") {
    require(debugCommerceApiBaseUrl.startsWith("https://")) {
        "Staging Device Commerce API must use HTTPS"
    }
    require(stagingLicenseKeyId.isNotBlank()) {
        "Staging Device Commerce license keyId is required"
    }
    require(stagingLicensePublicKeyBase64.isNotBlank()) {
        "Staging Device Commerce license public key is required"
    }
    val propertiesFile = requireNotNull(stagingSigningPropertiesFile) {
        "Staging APK signing properties file is required"
    }
    require(propertiesFile.isFile) {
        "Staging APK signing properties file does not exist"
    }
    propertiesFile.inputStream().use(stagingSigningProperties::load)
    val configuredStoreFile = stagingSigningProperties.requiredValue("storeFile")
    val candidate = File(configuredStoreFile)
    val resolved = if (candidate.isAbsolute) {
        candidate
    } else {
        propertiesFile.parentFile.resolve(configuredStoreFile)
    }
    require(resolved.isFile) { "Staging APK keystore does not exist" }
    resolved
} else {
    null
}

android {
    namespace = "com.tcrrry.desktoplyrics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tcrrry.desktoplyrics"
        minSdk = 26
        targetSdk = 34
        versionCode = 114
        versionName = "1.14-icar03"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
        if (stagingSigningStoreFile != null) {
            create("staging") {
                storeFile = stagingSigningStoreFile
                storePassword = stagingSigningProperties.requiredValue("storePassword")
                keyAlias = stagingSigningProperties.requiredValue("keyAlias")
                keyPassword = stagingSigningProperties.requiredValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("staging")?.let { signingConfig = it }
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_ENVIRONMENT",
                debugCommerceEnvironment.asBuildConfigString()
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_API_BASE_URL",
                debugCommerceApiBaseUrl.asBuildConfigString()
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_LICENSE_KEY_ID",
                stagingLicenseKeyId.asBuildConfigString()
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_LICENSE_PUBLIC_KEY_BASE64",
                stagingLicensePublicKeyBase64.asBuildConfigString()
            )
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            buildConfigField("String", "DEVICE_COMMERCE_ENVIRONMENT", "production".asBuildConfigString())
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_API_BASE_URL",
                productionApiBaseUrl.asBuildConfigString()
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_LICENSE_KEY_ID",
                productionLicenseKeyId.asBuildConfigString()
            )
            buildConfigField(
                "String",
                "DEVICE_COMMERCE_LICENSE_PUBLIC_KEY_BASE64",
                productionLicensePublicKeyBase64.asBuildConfigString()
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
