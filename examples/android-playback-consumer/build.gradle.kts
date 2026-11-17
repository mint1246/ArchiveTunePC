plugins {
    id("com.android.library") version "9.4.0"
    kotlin("android") version "2.4.10" apply false
}

android {
    namespace = "example.archivetune.playback"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("moe.rukamori.archivetune:android-playback:0.1.0-SNAPSHOT")
}
