plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    // JVM is a host-side verification target; existing Android/desktop apps stay intact.
    jvm()
    if (System.getProperty("os.name") == "Mac OS X") {
        listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
            target.binaries.framework {
                baseName = "ArchiveTuneShared"
                isStatic = true
                binaryOption("bundleId", "moe.rukamori.archivetune.shared")
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.components.resources)
            implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
            implementation("org.jetbrains.compose.foundation:foundation:1.10.3")
            implementation("org.jetbrains.compose.material3:material3:1.10.0-alpha05")
            implementation("org.jetbrains.compose.ui:ui:1.10.3")
            // Keep Intel simulator support; Compose 1.11 / Coil 3.5 dropped iosX64.
            implementation("io.coil-kt.coil3:coil-compose:3.4.0") { exclude(group = "org.jetbrains.compose.foundation") }
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.4.0")
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        if (System.getProperty("os.name") == "Mac OS X") {
            iosMain.dependencies {
                implementation("io.ktor:ktor-client-darwin:${libs.versions.ktor.get()}")
            }
        }
    }
}

val hostJvm = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
tasks.register<JavaExec>("catalogSmoke") {
    group = "verification"
    description = "Opt-in live catalog/media transport probe; does not verify AVPlayer playback."
    dependsOn("jvmMainClasses")
    classpath = files(hostJvm.compilations.getByName("main").output.allOutputs,
        configurations.getByName("jvmRuntimeClasspath"))
    mainClass.set("moe.rukamori.archivetune.shared.CatalogSmokeKt")
    args(providers.gradleProperty("catalogSmokeQuery").getOrElse("music"),
        providers.gradleProperty("catalogSmokeVideoId").getOrElse(""))
}

// Point acceptance tests at an existing simulator instead of Xcode's default device.
if (System.getProperty("os.name") == "Mac OS X") {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        providers.gradleProperty("iosSimulatorId").orNull?.let { device.set(it) }
    }
}

compose.resources {
    packageOfResClass = "moe.rukamori.archivetune.shared.resources"
}
