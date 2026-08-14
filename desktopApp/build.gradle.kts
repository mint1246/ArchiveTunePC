import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.DataOutputStream
import java.io.FileOutputStream
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

group = "moe.rukamori.archivetune"
version = "14.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(project(":desktop-backend"))
    implementation(project(":desktop-auth"))
    implementation(project(":desktop-playback"))
    implementation(project(":desktop-library"))
    implementation(project(":desktop-settings"))
    implementation(project(":desktop-lyrics-ui"))
    implementation(project(":windows-media-integration"))
    implementation(project(":desktop-downloads"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.core)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    testImplementation(kotlin("test"))
}

val desktopIconPng = rootProject.file("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
val generatedWindowsIcon = layout.buildDirectory.file("generated/desktop/icon/ArchiveTune.ico")
val generateWindowsIcon = tasks.register("generateWindowsIcon") {
    description = "Wraps the ArchiveTune PNG launcher artwork in a Windows ICO container."
    inputs.file(desktopIconPng)
    outputs.file(generatedWindowsIcon)

    doLast {
        val image = ImageIO.read(desktopIconPng)
            ?: error("Unable to decode ArchiveTune launcher icon: $desktopIconPng")
        require(image.width in 1..256 && image.height in 1..256) {
            "Windows launcher icon must be at most 256x256, but was ${image.width}x${image.height}"
        }

        val pngBytes = desktopIconPng.readBytes()
        val output = generatedWindowsIcon.get().asFile
        output.parentFile.mkdirs()
        DataOutputStream(FileOutputStream(output)).use { stream ->
            fun writeLittleEndianShort(value: Int) {
                stream.writeByte(value and 0xFF)
                stream.writeByte((value ushr 8) and 0xFF)
            }

            fun writeLittleEndianInt(value: Int) {
                stream.writeByte(value and 0xFF)
                stream.writeByte((value ushr 8) and 0xFF)
                stream.writeByte((value ushr 16) and 0xFF)
                stream.writeByte((value ushr 24) and 0xFF)
            }

            writeLittleEndianShort(0) // reserved
            writeLittleEndianShort(1) // icon
            writeLittleEndianShort(1) // one image
            stream.writeByte(if (image.width == 256) 0 else image.width)
            stream.writeByte(if (image.height == 256) 0 else image.height)
            stream.writeByte(0) // palette
            stream.writeByte(0) // reserved
            writeLittleEndianShort(1) // color planes
            writeLittleEndianShort(32) // bits per pixel
            writeLittleEndianInt(pngBytes.size)
            writeLittleEndianInt(22) // ICONDIR (6) + ICONDIRENTRY (16)
            stream.write(pngBytes)
        }
    }
}

tasks.processResources {
    from(rootProject.file("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")) {
        into("icons")
        rename { "archivetune.png" }
    }
    from(rootProject.file("app/src/main/res/font")) {
        into("fonts")
    }
}

compose.desktop {
    application {
        mainClass = "moe.rukamori.archivetune.desktop.MainKt"
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dsun.java2d.dpiaware=true",
        )

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "ArchiveTune"
            packageVersion = project.version.toString()
            description = "A private, feature-rich YouTube Music client and music player."
            copyright = "Copyright © 2026 Rukamori and ArchiveTune contributors"
            vendor = "ArchiveTune"
            licenseFile.set(rootProject.file("LICENSE"))
            // Copied beside the application rather than into the JAR so mpv can be executed.
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("packaging/mpv"))
            modules(
                "java.desktop",
                "java.logging",
                "java.naming",
                "java.net.http",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )

            windows {
                iconFile.set(generatedWindowsIcon)
                console = false
                dirChooser = true
                perUserInstall = true
                menuGroup = "ArchiveTune"
                upgradeUuid = "1bfa7db8-e785-4fd2-8fb1-5debbd28ac03"
            }
        }
    }
}

val verifyBundledMpvRuntime = tasks.register("verifyBundledMpvRuntime") {
    group = "verification"
    description = "Verifies that the Windows distribution contains a runnable bundled mpv runtime."
    dependsOn("createDistributable")

    doLast {
        val appDirectory = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val runtime = fileTree(appDirectory) {
            include("**/resources/native/windows-x64/mpv.exe")
        }.files.singleOrNull()
            ?: error("Bundled mpv.exe was not found below $appDirectory")
        val process = ProcessBuilder(runtime.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(process.waitFor() == 0 && output.contains("mpv")) {
            "Bundled mpv runtime failed its --version smoke test: $output"
        }
    }
}

tasks.matching {
    it.name == "createDistributable" ||
        it.name == "packageMsi" ||
        it.name == "packageExe" ||
        it.name == "packageDistributionForCurrentOS"
}.configureEach {
    dependsOn(generateWindowsIcon)
}
