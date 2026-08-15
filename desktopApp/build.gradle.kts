import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
val generateWindowsIcon = tasks.register<GenerateWindowsIcoTask>("generateWindowsIcon") {
    description = "Wraps the ArchiveTune PNG launcher artwork in a Windows ICO container."
    sourcePng.set(desktopIconPng)
    outputIco.set(generatedWindowsIcon)
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

val jpackageResourceDir = layout.buildDirectory.dir("compose/tmp/resources")
val customMsiTemplate = rootProject.layout.projectDirectory.file("packaging/windows/main.wxs")

val prepareMsiWixOverride = tasks.register<Copy>("prepareMsiWixOverride") {
    description = "Copies the custom WiX main.wxs (install-over-existing upgrade behaviour) into jpackage's resource dir."
    from(customMsiTemplate)
    into(jpackageResourceDir)
}

tasks.matching {
    it.name == "packageMsi" || it.name == "packageExe" || it.name == "packageDistributionForCurrentOS"
}.configureEach {
    dependsOn(prepareMsiWixOverride)
}

val verifyBundledMpvRuntime = tasks.register<VerifyBundledExecutableTask>("verifyBundledMpvRuntime") {
    group = "verification"
    description = "Verifies that the Windows distribution contains a runnable bundled mpv runtime."
    dependsOn("createDistributable")
    appDirectory.set(layout.buildDirectory.dir("compose/binaries/main/app"))
    executablePathSuffix.set("/resources/native/windows-x64/mpv.exe")
    arguments.set(listOf("--version"))
    expectedOutput.set("mpv")
}

tasks.matching {
    it.name == "createDistributable" ||
        it.name == "packageMsi" ||
        it.name == "packageExe" ||
        it.name == "packageDistributionForCurrentOS"
}.configureEach {
    dependsOn(generateWindowsIcon)
}

tasks.register<JavaExec>("authenticatedStreamDiagnostic") {
    group = "verification"
    description = "Uses the current user's DPAPI session through the normal session manager and prints redacted stream diagnostics."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("moe.rukamori.archivetune.desktop.platform.AuthenticatedStreamDiagnosticKt")
    dependsOn("classes")
}
