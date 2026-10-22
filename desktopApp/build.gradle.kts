import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

group = "moe.rukamori.archivetune"
version = "15.10"
val windowsPackageVersion = version.toString().let { value ->
    if (value.count { it == '.' } == 1) "$value.0" else value
}
val windowsInstallationPath = "ArchiveTune App"
val buildingForWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

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
    implementation(project(":canvas"))
    implementation(project(":spotifycore"))
    implementation(project(":desktop-auth"))
    implementation(project(":desktop-social"))
    implementation(project(":desktop-playback"))
    implementation(project(":desktop-play-together"))
    implementation(project(":desktop-library"))
    implementation(project(":desktop-settings"))
    implementation(project(":desktop-statistics"))
    implementation(project(":desktop-audio-analysis"))
    implementation(project(":desktop-lyrics-ui"))
    implementation(project(":windows-media-integration"))
    implementation(project(":linux-media-integration"))
    implementation(libs.jna.platform)
    implementation(project(":desktop-downloads"))
    implementation(project(":lastfm"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.core)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.json)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

val desktopLastFmLocalPropertiesFile = rootProject.file("local.properties")
val desktopLastFmLocalProperties = Properties().apply {
    if (desktopLastFmLocalPropertiesFile.isFile) {
        desktopLastFmLocalPropertiesFile.inputStream().use(::load)
    }
}
val desktopLastFmApiKey =
    desktopLastFmLocalProperties.getProperty("LASTFM_API_KEY")?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv("LASTFM_API_KEY")?.trim()?.takeIf { it.isNotBlank() }
        ?: ""
val desktopLastFmSecret =
    desktopLastFmLocalProperties.getProperty("LASTFM_SECRET")?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv("LASTFM_SECRET")?.trim()?.takeIf { it.isNotBlank() }
        ?: ""

fun String.asKotlinStringLiteral(): String = buildString {
    append('"')
    this@asKotlinStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> {
                append('\\')
                append('$')
            }
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\u000c")
            else -> {
                if (character.code < 0x20 || character.code == 0x7f) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

fun desktopLastFmCredentialFingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

val desktopLastFmGeneratedSourceRoot =
    layout.buildDirectory.dir("generated/sources/desktopLastFm/main/kotlin")
val desktopLastFmGeneratedSourceFile =
    layout.buildDirectory.file(
        "generated/sources/desktopLastFm/main/kotlin/moe/rukamori/archivetune/desktop/platform/DesktopLastFmBuildCredentials.kt",
    )
val generateDesktopLastFmBuildCredentials = tasks.register("generateDesktopLastFmBuildCredentials") {
    group = "build"
    description = "Generates the desktop Last.fm credential constants from local build configuration."
    inputs.files(provider {
        listOfNotNull(desktopLastFmLocalPropertiesFile.takeIf { it.isFile })
    })
    inputs.property("lastfmApiKeyFingerprint", desktopLastFmCredentialFingerprint(desktopLastFmApiKey))
    inputs.property("lastfmSecretFingerprint", desktopLastFmCredentialFingerprint(desktopLastFmSecret))
    outputs.file(desktopLastFmGeneratedSourceFile)

    doLast {
        val outputFile = desktopLastFmGeneratedSourceFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package moe.rukamori.archivetune.desktop.platform

            internal object DesktopLastFmBuildCredentials {
                const val API_KEY = ${desktopLastFmApiKey.asKotlinStringLiteral()}
                const val SECRET = ${desktopLastFmSecret.asKotlinStringLiteral()}
            }
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(desktopLastFmGeneratedSourceRoot)
}

tasks.named("compileKotlin") {
    dependsOn(generateDesktopLastFmBuildCredentials)
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
            if (buildingForWindows) {
                targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            } else {
                targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            }
            packageName = "ArchiveTune"
            packageVersion = windowsPackageVersion
            description = "A private, feature-rich YouTube Music client and music player."
            copyright = "Copyright © 2026 Rukamori and ArchiveTune contributors"
            vendor = "ArchiveTune"
            licenseFile.set(rootProject.file("LICENSE"))
            // The bundled VLC runtime contains Windows DLLs. Linux uses the system libVLC.
            if (buildingForWindows) {
                appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("packaging/vlc/common"))
            }
            modules(
                "java.desktop",
                "java.logging",
                "java.naming",
                "java.net.http",
                "java.sql",
                "jdk.crypto.ec",
                // D-Bus Unix-socket transport and EXTERNAL authentication on Linux.
                "jdk.net",
                "jdk.security.auth",
                "jdk.unsupported",
            )

            linux {
                iconFile.set(desktopIconPng)
            }

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
// Compose/jpackage places app resources in app/resources on Windows and lib/app/resources on
// Linux, and the generated launcher exposes that directory as compose.application.resources.dir.
val appImageResourcesDir =
    appImageDir.map { it.dir(if (buildingForWindows) "app/resources" else "lib/app/resources") }
val releaseAppImageResourcesDir =
    releaseAppImageDir.map { it.dir(if (buildingForWindows) "app/resources" else "lib/app/resources") }
val bundledVlcSourceDir = rootProject.layout.projectDirectory.dir("packaging/vlc/common/native/windows-x64/vlc")
val audioAnalysisProject = evaluationDependsOn(":desktop-audio-analysis")
val audioAnalysisDll = audioAnalysisProject.layout.buildDirectory.file("native/windows-x64/archivetune-audio-analysis.dll")
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
val stageBundledAudioAnalysis = tasks.register<Copy>("stageBundledAudioAnalysis") {
    group = "distribution"
    description = "Stages the Essentia audio-analysis DLL into the desktop app image."
    dependsOn(audioAnalysisProject.tasks.named("buildNativeWindows"))
    from(audioAnalysisDll)
    into(appImageDir.map { it.dir("app/resources/native/windows-x64/audio-analysis") })
    mustRunAfter("createDistributable")
}
val stageBundledAudioAnalysisRelease = tasks.register<Copy>("stageBundledAudioAnalysisRelease") {
    group = "distribution"
    description = "Stages the Essentia audio-analysis DLL into the optimized desktop app image."
    dependsOn(audioAnalysisProject.tasks.named("buildNativeWindows"))
    from(audioAnalysisDll)
    into(releaseAppImageDir.map { it.dir("app/resources/native/windows-x64/audio-analysis") })
    mustRunAfter("createReleaseDistributable")
}
/*
 * Experimental desktop resolver sidecars.
 *
 * The sidecars are downloaded from pinned upstream GitHub releases, verified
 * against committed SHA-256 values, and staged into app/resources/resolver so
 * `<compose.application.resources.dir>/resolver` resolves beside the app image.
 */
val resolverPlatformId = ResolverSidecars.resolvePlatform(
    providers.gradleProperty("archivetune.resolverPlatform").orNull,
)
val resolverNoticesFile = rootProject.layout.projectDirectory.file("packaging/resolver/THIRD_PARTY_NOTICES.md")
val resolverLicensesDir = rootProject.layout.projectDirectory.dir("packaging/resolver/LICENSES")
val resolverDownloadTasks = ResolverSidecars.assets.associate { asset ->
    val taskName = ResolverSidecars.downloadTaskName(asset)
    taskName to tasks.register<DownloadVerifiedFileTask>(taskName) {
        group = "distribution"
        description =
            "Downloads ${asset.kind.displayName} ${asset.version} for ${asset.platformId} and verifies its SHA-256."
        sourceUrl.set(asset.url)
        expectedSha256.set(asset.sha256)
        outputFile.set(layout.buildDirectory.file(ResolverSidecars.downloadPath(asset)))
    }
}

/*
 * Bun ships a ZIP, so its executable is extracted as a separate verified step.
 * Only the exact pinned member is streamed into the build directory; the ZIP
 * tree is never unpacked, which keeps the staging path free of ZIP-slip and
 * symlink surprises.
 */
val resolverExtractTasks = ResolverSidecars.assets.filter { it.isArchive }.associate { asset ->
    val taskName = ResolverSidecars.extractTaskName(asset)
    taskName to tasks.register<ExtractVerifiedArchiveEntryTask>(taskName) {
        group = "distribution"
        description =
            "Extracts the verified ${asset.kind.displayName} ${asset.version} executable for ${asset.platformId} " +
                "from ${asset.assetName}."
        sourceArchive.set(
            resolverDownloadTasks.getValue(ResolverSidecars.downloadTaskName(asset)).flatMap { it.outputFile },
        )
        archiveMember.set(asset.archiveMember!!)
        expectedSha256.set(asset.binarySha256)
        outputFile.set(layout.buildDirectory.file(ResolverSidecars.extractedBinaryPath(asset)))
    }
}

/** The verified executable to stage: a raw download, or the extracted ZIP member. */
fun resolverBinaryFile(platformId: String, kind: ResolverSidecarKind) =
    ResolverSidecars.assetFor(platformId, kind).let { asset ->
        if (asset.isArchive) {
            resolverExtractTasks.getValue(ResolverSidecars.extractTaskName(asset)).flatMap { it.outputFile }
        } else {
            resolverDownloadTasks.getValue(ResolverSidecars.downloadTaskName(asset)).flatMap { it.outputFile }
        }
    }

fun registerResolverStageTask(
    name: String,
    destination: Provider<Directory>,
    runAfterTask: String,
) = tasks.register<StageResolverSidecarsTask>(name) {
    group = "distribution"
    description = "Stages the checksum-verified yt-dlp and Bun sidecars into the desktop app image."
    platformId.set(resolverPlatformId ?: "unsupported")
    ytDlpVersion.set(ResolverSidecars.YT_DLP_VERSION)
    bunVersion.set(ResolverSidecars.BUN_VERSION)
    bundledEjsVersion.set(ResolverSidecars.YT_DLP_BUNDLED_EJS_VERSION)
    thirdPartyNotices.set(resolverNoticesFile)
    licensesDirectory.set(resolverLicensesDir)
    destinationDirectory.set(destination)
    if (resolverPlatformId == null) {
        ytDlpUrl.set("")
        ytDlpSha256.set("")
        bunUrl.set("")
        bunSha256.set("")
        bunBinarySha256.set("")
        unsupportedReason.set(ResolverSidecars.unsupportedPlatformMessage())
    } else {
        val ytDlp = ResolverSidecars.assetFor(resolverPlatformId, ResolverSidecarKind.YT_DLP)
        val bun = ResolverSidecars.assetFor(resolverPlatformId, ResolverSidecarKind.BUN)
        ytDlpUrl.set(ytDlp.url)
        ytDlpSha256.set(ytDlp.sha256)
        bunUrl.set(bun.url)
        bunSha256.set(bun.sha256)
        bunBinarySha256.set(bun.binarySha256)
        ytDlpBinary.set(resolverBinaryFile(resolverPlatformId, ResolverSidecarKind.YT_DLP))
        bunBinary.set(resolverBinaryFile(resolverPlatformId, ResolverSidecarKind.BUN))
    }
    mustRunAfter(runAfterTask)
}

fun registerResolverVerifyTask(
    name: String,
    directory: Provider<Directory>,
    stageTask: TaskProvider<out Task>,
) = tasks.register<VerifyResolverSidecarsTask>(name) {
    group = "verification"
    description = "Verifies the bundled yt-dlp and Bun sidecars, checksums, and notices."
    dependsOn(stageTask)
    sidecarDirectory.set(directory)
    platformId.set(resolverPlatformId ?: "unsupported")
    if (resolverPlatformId == null) {
        ytDlpSha256.set("")
        bunSha256.set("")
        bunBinarySha256.set("")
        unsupportedReason.set(ResolverSidecars.unsupportedPlatformMessage())
    } else {
        val ytDlp = ResolverSidecars.assetFor(resolverPlatformId, ResolverSidecarKind.YT_DLP)
        val bun = ResolverSidecars.assetFor(resolverPlatformId, ResolverSidecarKind.BUN)
        ytDlpSha256.set(ytDlp.sha256)
        bunSha256.set(bun.sha256)
        bunBinarySha256.set(bun.binarySha256)
    }
}

val stageBundledResolverSidecars = registerResolverStageTask(
    name = "stageBundledResolverSidecars",
    destination = appImageResourcesDir.map { it.dir("resolver") },
    runAfterTask = "createDistributable",
)
val stageBundledResolverSidecarsRelease = registerResolverStageTask(
    name = "stageBundledResolverSidecarsRelease",
    destination = releaseAppImageResourcesDir.map { it.dir("resolver") },
    runAfterTask = "createReleaseDistributable",
)
val verifyBundledResolverSidecars = registerResolverVerifyTask(
    name = "verifyBundledResolverSidecars",
    directory = appImageResourcesDir.map { it.dir("resolver") },
    stageTask = stageBundledResolverSidecars,
)
val verifyBundledResolverSidecarsRelease = registerResolverVerifyTask(
    name = "verifyBundledResolverSidecarsRelease",
    directory = releaseAppImageResourcesDir.map { it.dir("resolver") },
    stageTask = stageBundledResolverSidecarsRelease,
)

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
    "--app-version", windowsPackageVersion,
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

if (buildingForWindows) {
    val packageMsiUpgradable = tasks.register<Exec>("packageMsiUpgradable") {
        group = "package"
        description = "Builds the MSI with install-over-existing upgrade behaviour (replaces any previously installed ArchiveTune instead of failing)."
        dependsOn("createDistributable", stageBundledVlcRuntime, stageBundledAudioAnalysis, verifyBundledVlcRuntime, stageBundledResolverSidecars, verifyBundledResolverSidecars, generateWindowsIcon, rootProject.tasks.named("unzipWix"))
        environment("PATH", wixToolsetDir.get().asFile.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""))
        commandLine(
            listOf(jpackageExecutable) +
                installerJpackageArgs("msi", appImageDir, layout.buildDirectory.dir("compose/binaries/main/msi")),
        )
    }

    val packageExeUpgradable = tasks.register<Exec>("packageExeUpgradable") {
        group = "package"
        description = "Builds the EXE with install-over-existing upgrade behaviour (replaces any previously installed ArchiveTune instead of failing)."
        dependsOn("createDistributable", stageBundledVlcRuntime, stageBundledAudioAnalysis, verifyBundledVlcRuntime, stageBundledResolverSidecars, verifyBundledResolverSidecars, generateWindowsIcon, rootProject.tasks.named("unzipWix"))
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

}

val verifyBundledVlcRuntime = tasks.register<VerifyBundledVlcRuntimeTask>("verifyBundledVlcRuntime") {
    group = "verification"
    description = "Verifies that the Windows distribution contains the libVLC runtime and plugin tree."
    dependsOn(stageBundledVlcRuntime)
    appDirectory.set(layout.buildDirectory.dir("compose/binaries/main/app"))
}

// Windows-only packagers execute .exe and PowerShell tools when staging VLC/Essentia.
// Leave Compose's Linux distributable and package tasks independent of those tools.
if (buildingForWindows) {
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
        finalizedBy(stageBundledVlcRuntime, stageBundledAudioAnalysis, stageBundledResolverSidecars)
    }

    tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
        finalizedBy(stageBundledVlcRuntimeRelease, stageBundledAudioAnalysisRelease, stageBundledResolverSidecarsRelease)
    }
} else {
    // Linux app images cannot use the Windows VLC/Essentia staging tasks. The resolver sidecars
    // are cross-platform and must still land under lib/app/resources/resolver.
    tasks.matching { it.name == "createDistributable" }.configureEach {
        finalizedBy(stageBundledResolverSidecars, verifyBundledResolverSidecars)
    }

    tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
        finalizedBy(stageBundledResolverSidecarsRelease, verifyBundledResolverSidecarsRelease)
    }
}
