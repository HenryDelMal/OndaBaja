import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localAppConfig = Properties().apply {
    rootProject.file("config.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun configValue(key: String, defaultValue: String): String =
    localAppConfig.getProperty(key)?.trim()?.takeIf(String::isNotEmpty) ?: defaultValue

fun configPositiveInt(key: String, defaultValue: Int): Int {
    val value = configValue(key, defaultValue.toString()).toIntOrNull()
        ?: error("Invalid integer for '$key' in config.properties")
    require(value > 0) { "'$key' in config.properties must be greater than zero" }
    return value
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val configuredCatalogUrl = configValue(
    "catalog.url",
    "https://radios.example.com/radio_list.json",
)
val configuredModelAsset = configValue("decoder.modelAsset", "vocos-encodec-24khz-f32.bin")
val configuredModelSha256 = configValue(
    "decoder.modelSha256",
    "58eb4c8daacf3b21667a40935ef6028d6ac253978778d505e24b0f539faf647e",
)
require(configuredModelSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
    "decoder.modelSha256 in config.properties must be a 64-character SHA-256 hex digest"
}

android {
    namespace = "cl.cuy.emergencyradio"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.cuy.emergencyradio"
        minSdk = 26
        targetSdk = 35
        versionCode = 68
        versionName = "1.0.0"
        buildConfigField("String", "STATION_CATALOG_URL", buildConfigString(configuredCatalogUrl))
        buildConfigField("String", "DECODER_MODEL_ASSET", buildConfigString(configuredModelAsset))
        buildConfigField("String", "DECODER_MODEL_SHA256", buildConfigString(configuredModelSha256))
        buildConfigField("int", "CATALOG_CONNECT_TIMEOUT_MS", configPositiveInt("network.catalogConnectTimeoutMs", 12_000).toString())
        buildConfigField("int", "CATALOG_READ_TIMEOUT_MS", configPositiveInt("network.catalogReadTimeoutMs", 12_000).toString())
        buildConfigField("int", "FILE_CONNECT_TIMEOUT_MS", configPositiveInt("network.fileConnectTimeoutMs", 15_000).toString())
        buildConfigField("int", "FILE_READ_TIMEOUT_MS", configPositiveInt("network.fileReadTimeoutMs", 30_000).toString())
        buildConfigField("int", "LIVE_CONNECT_TIMEOUT_MS", configPositiveInt("network.liveConnectTimeoutMs", 4_000).toString())
        buildConfigField("int", "LIVE_READ_TIMEOUT_MS", configPositiveInt("network.liveReadTimeoutMs", 5_000).toString())
        buildConfigField("int", "LIVE_REBUFFER_TARGET_SEGMENTS", configPositiveInt("live.rebufferTargetSegments", 3).toString())
        buildConfigField("int", "LIVE_MAX_BUFFER_SEGMENTS", configPositiveInt("live.maxBufferSegments", 6).toString())
        buildConfigField("int", "LIVE_STARTUP_BUFFER_SEGMENTS", configPositiveInt("live.startupBufferSegments", 6).toString())
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { jniLibs.useLegacyPackaging = true; resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core:ecdc"))
    implementation(project(":core:decoder"))
    implementation(project(":core:playback"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
