plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.henry.encodec.playback"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:decoder"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
