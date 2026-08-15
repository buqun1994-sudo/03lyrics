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
val debugCommerceApiBaseUrl = if (debugCommerceEnvironment == "staging") {
    "https://api-staging.9studio.fun"
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
    }

    buildTypes {
        getByName("debug") {
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
