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
    implementation(libs.json)
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
            "-Xms32m",
            "-Xmx768m",
            "-XX:ReservedCodeCacheSize=128m",
            "-XX:ParallelGCThreads=4",
            "-XX:ConcGCThreads=2",
            "-XX:G1ConcRefinementThreads=4",
        )

        buildTypes.release.proguard {
            version.set("7.9.1")
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
            // Copied beside the application so the in-process VLCJ backend can find libVLC and its plugins.
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("packaging/vlc/common"))
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

val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/ArchiveTune")
val releaseAppImageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/ArchiveTune")
val bundledVlcSourceDir = rootProject.layout.projectDirectory.dir("packaging/vlc/common/native/windows-x64/vlc")
val stageBundledVlcRuntime = tasks.register<StageBundledVlcRuntimeTask>("stageBundledVlcRuntime") {
    group = "distribution"
    description = "Stages the verified libVLC runtime into the desktop app image."
    sourceDirectory.set(bundledVlcSourceDir)
    destinationDirectory.set(appImageDir.map { it.dir("app/resources/native/windows-x64/vlc") })
}
val stageBundledVlcRuntimeRelease = tasks.register<StageBundledVlcRuntimeTask>("stageBundledVlcRuntimeRelease") {
    group = "distribution"
    description = "Stages the verified libVLC runtime into the optimized desktop app image."
    sourceDirectory.set(bundledVlcSourceDir)
    destinationDirectory.set(releaseAppImageDir.map { it.dir("app/resources/native/windows-x64/vlc") })
}
val wixToolsetDir = rootProject.layout.buildDirectory.dir("wix311")
val customWixResourceDir = rootProject.layout.projectDirectory.dir("packaging/windows")
val jpackageExecutable = "${System.getProperty("java.home")}/bin/jpackage.exe"

fun installerJpackageArgs(type: String, dest: Provider<Directory>): List<String> = listOf(
    "--type", type,
    "--app-image", appImageDir.get().asFile.absolutePath,
    "--resource-dir", customWixResourceDir.asFile.absolutePath,
    "--dest", dest.get().asFile.absolutePath,
    "--name", "ArchiveTune",
    "--app-version", project.version.toString(),
    "--vendor", "ArchiveTune",
    "--description", "A private, feature-rich YouTube Music client and music player.",
    "--copyright", "Copyright © 2026 Rukamori and ArchiveTune contributors",
    "--license-file", rootProject.file("LICENSE").absolutePath,
    "--icon", generatedWindowsIcon.get().asFile.absolutePath,
    "--win-per-user-install",
    "--win-upgrade-uuid", "1bfa7db8-e785-4fd2-8fb1-5debbd28ac03",
    "--win-menu", "--win-menu-group", "ArchiveTune",
    "--win-dir-chooser",
)

val packageMsiUpgradable = tasks.register<Exec>("packageMsiUpgradable") {
    group = "package"
    description = "Builds the MSI with install-over-existing upgrade behaviour (replaces any previously installed ArchiveTune instead of failing)."
    dependsOn("createDistributable", stageBundledVlcRuntime, verifyBundledVlcRuntime, generateWindowsIcon, rootProject.tasks.named("unzipWix"))
    environment("PATH", wixToolsetDir.get().asFile.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""))
    commandLine(listOf(jpackageExecutable) + installerJpackageArgs("msi", layout.buildDirectory.dir("compose/binaries/main/msi")))
}

val packageExeUpgradable = tasks.register<Exec>("packageExeUpgradable") {
    group = "package"
    description = "Builds the EXE with install-over-existing upgrade behaviour (replaces any previously installed ArchiveTune instead of failing)."
    dependsOn("createDistributable", stageBundledVlcRuntime, verifyBundledVlcRuntime, generateWindowsIcon, rootProject.tasks.named("unzipWix"))
    environment("PATH", wixToolsetDir.get().asFile.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""))
    commandLine(listOf(jpackageExecutable) + installerJpackageArgs("exe", layout.buildDirectory.dir("compose/binaries/main/exe")))
}

val verifyBundledVlcRuntime = tasks.register<VerifyBundledVlcRuntimeTask>("verifyBundledVlcRuntime") {
    group = "verification"
    description = "Verifies that the Windows distribution contains the libVLC runtime and plugin tree."
    dependsOn(stageBundledVlcRuntime)
    appDirectory.set(layout.buildDirectory.dir("compose/binaries/main/app"))
}

tasks.matching {
    it.name == "createDistributable" ||
        it.name == "createReleaseDistributable" ||
        it.name == "packageMsi" ||
        it.name == "packageExe" ||
        it.name == "packageDistributionForCurrentOS"
}.configureEach {
    dependsOn(generateWindowsIcon)
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(stageBundledVlcRuntime)
}

tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
    finalizedBy(stageBundledVlcRuntimeRelease)
}
