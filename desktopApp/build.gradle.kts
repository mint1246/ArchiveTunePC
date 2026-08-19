import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

group = "moe.rukamori.archivetune"
version = "14.1.0"
val windowsInstallationPath = "ArchiveTune App"

val buildingWithOpenJ9 =
    System.getProperty("java.vm.name").contains("OpenJ9", ignoreCase = true) ||
        System.getProperty("java.vm.info").contains("OpenJ9", ignoreCase = true)

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
    implementation(project(":desktop-play-together"))
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
        )
        if (buildingWithOpenJ9) {
            // OpenJ9 enables JIT by default; keep it explicit in packaged builds so the runtime
            // choice is visible in ArchiveTune.cfg and cannot accidentally regress to -Xnojit.
            jvmArgs += "-Xjit"
        } else {
            // These are HotSpot/G1 knobs and should not be carried into the OpenJ9 launcher.
            jvmArgs += listOf(
                "-XX:ReservedCodeCacheSize=128m",
                "-XX:ParallelGCThreads=4",
                "-XX:ConcGCThreads=2",
                "-XX:G1ConcRefinementThreads=4",
            )
        }

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
                // Keep installed binaries away from the stable user-data roots. jpackage otherwise
                // defaults a per-user install to %LOCALAPPDATA%/ArchiveTune, which used to collide
                // with ArchiveTune's mutable data directory during MSI upgrades.
                installationPath = windowsInstallationPath
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

/**
 * OpenJ9 Windows builds intentionally use a nested Gradle invocation because the JVM running
 * Gradle is also the JDK Compose uses for jlink/jpackage.
 *
 * The normal OpenJ9 path uses Compose's non-ProGuard distributable: runtime behaviour is the same
 * JVM bytecode, but iterative builds avoid the several-minute shrink/obfuscation pass. A separate
 * shrunk release task is retained for occasions where distribution size matters.
 *
 * Override JDK discovery with either:
 *   -Parchivetune.openj9Home=C:\path\to\jdk
 *   ARCHIVETUNE_OPENJ9_HOME=C:\path\to\jdk
 */
val configuredOpenJ9Home =
    providers.gradleProperty("archivetune.openj9Home").orNull
        ?: System.getenv("ARCHIVETUNE_OPENJ9_HOME")
val programFilesDir = System.getenv("ProgramFiles")?.let(::File) ?: File("C:/Program Files")
val semeruRoot = File(programFilesDir, "Semeru")
val discoveredOpenJ9Homes =
    semeruRoot.listFiles()
        ?.filter { it.isDirectory && it.name.contains("openj9", ignoreCase = true) }
        ?.sortedByDescending(File::lastModified)
        .orEmpty()
val openJ9HomeForRelease =
    buildList {
        configuredOpenJ9Home?.let { add(File(it)) }
        addAll(discoveredOpenJ9Homes)
    }.firstOrNull { candidate ->
        File(candidate, "bin/java.exe").isFile && File(candidate, "bin/jpackage.exe").isFile
    }
val gradlewBatPath = rootProject.file("gradlew.bat").absolutePath

val createOpenJ9Distributable = tasks.register<Exec>("createOpenJ9Distributable") {
    group = "distribution"
    description = "Builds the fast OpenJ9 app image with JIT enabled and without ProGuard."
    workingDir(rootProject.projectDir)

    if (openJ9HomeForRelease != null) {
        environment("JAVA_HOME", openJ9HomeForRelease.absolutePath)
        environment(
            "PATH",
            File(openJ9HomeForRelease, "bin").absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""),
        )
        commandLine(
            "cmd.exe",
            "/d",
            "/c",
            gradlewBatPath,
            ":desktopApp:createDistributable",
        )
    } else {
        commandLine(
            "cmd.exe",
            "/d",
            "/c",
            "echo OpenJ9 JDK not found. Install IBM Semeru OpenJ9 or set ARCHIVETUNE_OPENJ9_HOME / -Parchivetune.openj9Home=^<jdk^>. 1>&2 & exit /b 1",
        )
    }
}

val createOpenJ9ReleaseDistributable = tasks.register<Exec>("createOpenJ9ReleaseDistributable") {
    group = "distribution"
    description = "Builds the shrunk OpenJ9 main-release app image with JIT enabled and ProGuard."
    workingDir(rootProject.projectDir)

    if (openJ9HomeForRelease != null) {
        environment("JAVA_HOME", openJ9HomeForRelease.absolutePath)
        environment(
            "PATH",
            File(openJ9HomeForRelease, "bin").absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""),
        )
        commandLine(
            "cmd.exe",
            "/d",
            "/c",
            gradlewBatPath,
            ":desktopApp:createReleaseDistributable",
        )
    } else {
        commandLine(
            "cmd.exe",
            "/d",
            "/c",
            "echo OpenJ9 JDK not found. Install IBM Semeru OpenJ9 or set ARCHIVETUNE_OPENJ9_HOME / -Parchivetune.openj9Home=^<jdk^>. 1>&2 & exit /b 1",
        )
    }
}

fun installerJpackageArgs(
    type: String,
    appImage: Provider<Directory>,
    dest: Provider<Directory>,
): List<String> = listOf(
    "--type", type,
    "--app-image", appImage.get().asFile.absolutePath,
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
    "--install-dir", windowsInstallationPath,
    "--win-upgrade-uuid", "1bfa7db8-e785-4fd2-8fb1-5debbd28ac03",
    "--win-menu", "--win-menu-group", "ArchiveTune",
    "--win-dir-chooser",
)

val packageMsiUpgradable = tasks.register<Exec>("packageMsiUpgradable") {
    group = "package"
    description = "Builds the MSI with install-over-existing upgrade behaviour (replaces any previously installed ArchiveTune instead of failing)."
    dependsOn("createDistributable", stageBundledVlcRuntime, verifyBundledVlcRuntime, generateWindowsIcon, rootProject.tasks.named("unzipWix"))
    environment("PATH", wixToolsetDir.get().asFile.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""))
    commandLine(
        listOf(jpackageExecutable) +
            installerJpackageArgs("msi", appImageDir, layout.buildDirectory.dir("compose/binaries/main/msi")),
    )
}

val packageExeUpgradable = tasks.register<Exec>("packageExeUpgradable") {
    group = "package"
    description = "Builds the EXE with install-over-existing upgrade behaviour (replaces any previously installed ArchiveTune instead of failing)."
    dependsOn("createDistributable", stageBundledVlcRuntime, verifyBundledVlcRuntime, generateWindowsIcon, rootProject.tasks.named("unzipWix"))
    environment("PATH", wixToolsetDir.get().asFile.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""))
    commandLine(
        listOf(jpackageExecutable) +
            installerJpackageArgs("exe", appImageDir, layout.buildDirectory.dir("compose/binaries/main/exe")),
    )
}

val openJ9JpackageExecutable =
    openJ9HomeForRelease?.let { File(it, "bin/jpackage.exe").absolutePath } ?: jpackageExecutable

val packageOpenJ9MsiUpgradable = tasks.register<Exec>("packageOpenJ9MsiUpgradable") {
    group = "package"
    description = "Builds an upgradable MSI from the fast OpenJ9 JIT app image without ProGuard."
    dependsOn(createOpenJ9Distributable, generateWindowsIcon, rootProject.tasks.named("unzipWix"))
    environment(
        "PATH",
        buildString {
            openJ9HomeForRelease?.let {
                append(File(it, "bin").absolutePath)
                append(File.pathSeparator)
            }
            append(wixToolsetDir.get().asFile.absolutePath)
            append(File.pathSeparator)
            append(System.getenv("PATH") ?: "")
        },
    )
    commandLine(
        listOf(openJ9JpackageExecutable) +
            installerJpackageArgs(
                "msi",
                appImageDir,
                layout.buildDirectory.dir("compose/binaries/main/msi"),
            ),
    )
}

val packageOpenJ9ReleaseMsiUpgradable = tasks.register<Exec>("packageOpenJ9ReleaseMsiUpgradable") {
    group = "package"
    description = "Builds an upgradable MSI from the shrunk OpenJ9 JIT/ProGuard app image."
    dependsOn(createOpenJ9ReleaseDistributable, generateWindowsIcon, rootProject.tasks.named("unzipWix"))
    environment(
        "PATH",
        buildString {
            openJ9HomeForRelease?.let {
                append(File(it, "bin").absolutePath)
                append(File.pathSeparator)
            }
            append(wixToolsetDir.get().asFile.absolutePath)
            append(File.pathSeparator)
            append(System.getenv("PATH") ?: "")
        },
    )
    commandLine(
        listOf(openJ9JpackageExecutable) +
            installerJpackageArgs(
                "msi",
                releaseAppImageDir,
                layout.buildDirectory.dir("compose/binaries/main-release/msi"),
            ),
    )
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
