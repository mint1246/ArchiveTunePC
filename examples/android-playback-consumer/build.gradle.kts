plugins {
    id("com.android.library") version "9.4.0"
    kotlin("android") version "2.4.10" apply false
}

android {
    namespace = "example.archivetune.playback"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("moe.rukamori.archivetune:android-playback:0.1.0-SNAPSHOT")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
