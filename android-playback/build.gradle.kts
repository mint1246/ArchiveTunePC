plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "moe.rukamori.archivetune.playback.android"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":playback-core"))
    api("androidx.media3:media3-common:${libs.versions.media3.get()}")
    api("androidx.media3:media3-datasource:${libs.versions.media3.get()}")
    api(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

publishing {
    publications {
        create<MavenPublication>("androidPlayback") {
            artifactId = "android-playback"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
