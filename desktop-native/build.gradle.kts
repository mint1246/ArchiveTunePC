import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    id("dev.nucleusframework") version "2.1.10"
}

group = "moe.rukamori.archivetune"
val desktopAppProject = evaluationDependsOn(":desktopApp")
val desktopAudioAnalysisProject = evaluationDependsOn(":desktop-audio-analysis")
version = desktopAppProject.version
val nativeQuickBuild = providers.gradleProperty("nativeQuickBuild").map(String::toBoolean).getOrElse(false)
val windowsPackageVersion = version.toString().let { value ->
    if (value.count { it == '.' } == 1) "$value.0" else value
}
val generatedWindowsIcon = desktopAppProject.layout.buildDirectory.file("generated/desktop/icon/ArchiveTune.ico")

/*
 * Host platform detection.
 *
 * Compose Desktop and GraalVM Native Image cannot cross-compile a complete distribution, so the
 * native build always targets the machine that runs Gradle. Everything that is specific to one
 * operating system (resource excludes, staged sidecars, installers) is therefore selected from
 * the host instead of being hardcoded for Windows.
 */
val hostOsName = System.getProperty("os.name").orEmpty()
val buildingForWindows = hostOsName.startsWith("Windows", ignoreCase = true)
val buildingForLinux = hostOsName.startsWith("Linux", ignoreCase = true)

/** Canonical GraalVM/Skiko architecture name for the build host. */
val hostArch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> System.getProperty("os.arch").orEmpty().lowercase()
}

/**
 * Architecture name used by the Skiko and Nucleus native payloads.
 *
 * They spell the x86-64 architecture `x64` (`libskiko-linux-x64.so`, `nucleus/native/linux-x64/`),
 * while the libdatachannel bindings spell it `x86_64`.
 */
val payloadArch = if (hostArch == "x86_64") "x64" else hostArch

/** The payload of the other architecture, which can never be loaded by this image. */
val foreignPayloadArch = if (hostArch == "x86_64") "aarch64" else "x64"

/** The libdatachannel bindings spell the x86-64 architecture `x86_64`. */
val foreignArch = if (hostArch == "x86_64") "aarch64" else "x86_64"

/*
 * Native-image compiler budget.
 *
 * These properties only tune the *builder* JVM that runs the native-image compiler. They are
 * unrelated to `graalvm.maxHeapSize`, which is the heap of the produced application.
 *
 *   ./gradlew :desktop-native:nativeImageCompile -PnativeImageBuildHeap=6g -PnativeImageBuildParallelism=4
 */
val nativeImageBuildHeap = providers.gradleProperty("nativeImageBuildHeap")
    .map(String::trim)
    .filter { it.isNotEmpty() }
    .orNull
val nativeImageBuildParallelism = providers.gradleProperty("nativeImageBuildParallelism")
    .map(String::trim)
    .filter { it.isNotEmpty() }
    .orNull

if (nativeImageBuildHeap != null && !nativeImageBuildHeap.matches(Regex("^[0-9]+[kKmMgGtT]?$"))) {
    throw GradleException(
        "-PnativeImageBuildHeap must be a JVM memory size such as 6g or 4096m, but was '$nativeImageBuildHeap'",
    )
}
if (nativeImageBuildParallelism != null &&
    (!nativeImageBuildParallelism.matches(Regex("^[0-9]+$")) || (nativeImageBuildParallelism.toIntOrNull() ?: 0) < 1)
) {
    throw GradleException(
        "-PnativeImageBuildParallelism must be a positive integer, but was '$nativeImageBuildParallelism'",
    )
}

/*
 * Native payload excludes for the host platform.
 *
 * Nucleus' static resource scanner sees the native payloads of every supported platform in a
 * few dependencies. Only the payloads that this image can actually load are kept; the rest
 * would otherwise be embedded verbatim in the image heap.
 *
 * The Linux payloads (JNA, the Nucleus native helpers and the libdatachannel Java bindings)
 * must survive for a Linux image, so the excludes are the mirror image of the Windows ones.
 */
val nativeImageResourceExcludes: List<String> = buildList {
    if (buildingForWindows) {
        // This image can only load the Windows payloads.
        add("""-H:ExcludeResources=^(macos-(x86_64|arm64)|x86_64|aarch64)/native/libdatachannel-java[.](dylib|so)$""")
        add("""-H:ExcludeResources=^com/sun/jna/(aix|darwin|dragonflybsd|freebsd|linux|openbsd|sunos)-.*/.*$""")
        add("""-H:ExcludeResources=^com/sun/jna/win32-(aarch64|x86)/.*$""")
        add("""-H:ExcludeResources=^nucleus/native/(darwin|linux)-.*/.*$""")
        // Nucleus already stages this DLL beside the executable in bin/, so keeping a second
        // byte-for-byte copy in the image heap only inflates the EXE.
        add("""-H:ExcludeResources=^skiko-windows-x64[.]dll$""")
    } else if (buildingForLinux) {
        // This image can only load the Linux payloads; the JNA dispatcher, the Nucleus native
        // helpers and the libdatachannel Java bindings have to stay in the image heap.
        // The Nucleus Windows payload lives in `nucleus/native/win32-*`, not `windows-*`.
        add("""-H:ExcludeResources=^com/sun/jna/(aix|darwin|dragonflybsd|freebsd|openbsd|sunos|win32)-.*/.*$""")
        add("""-H:ExcludeResources=^nucleus/native/(darwin|win32)-.*/.*$""")
        add("""-H:ExcludeResources=^macos-(x86_64|arm64)/native/libdatachannel-java[.]dylib$""")
        add("""-H:ExcludeResources=^windows-x86_64/native/libdatachannel-java[.]dll$""")
        // The payload of the other architecture can never be loaded by this image.
        add("""-H:ExcludeResources=^$foreignArch/native/libdatachannel-java[.]so$""")
        add("""-H:ExcludeResources=^nucleus/native/linux-$foreignPayloadArch/.*$""")
        // Nucleus already stages these libraries beside the executable in lib/, so keeping a
        // second byte-for-byte copy in the image heap only inflates the image.
        add("""-H:ExcludeResources=^libskiko-linux-$payloadArch[.]so$""")
        add("""-H:ExcludeResources=^libskiko-linux-$foreignPayloadArch[.]so$""")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    implementation(project(":desktopApp"))
    implementation(project(":desktop-playback"))
    implementation(libs.coroutines.core)
    implementation("dev.nucleusframework:nucleus.nucleus-application:2.1.10")
    implementation("dev.nucleusframework:nucleus.graalvm-runtime:2.1.10")
}

// Eclipse JDT's annotation JAR is signed. Nucleus flattens runtime dependencies into one uber JAR,
// which necessarily invalidates that original JAR signature. These annotations are compile-time
// metadata for desktop-play-together and are not needed by the native runtime.
configurations.runtimeClasspath {
    exclude(group = "org.eclipse.jdt", module = "org.eclipse.jdt.annotation")
}

nucleus.application {
    mainClass = "moe.rukamori.archivetune.native.NativeMainKt"

    nativeDistributions {
        appName = "ArchiveTune"
        packageName = "ArchiveTune"
        packageVersion = windowsPackageVersion
        if (buildingForWindows) {
            windows {
                iconFile.set(generatedWindowsIcon)
            }
        }
    }

    graalvm {
        isEnabled.set(true)
        imageName.set("ArchiveTune")
        maxHeapSize.set("128m")
        optimization.set(
            if (nativeQuickBuild) NativeImageOptimization.QUICK_BUILD else NativeImageOptimization.SIZE,
        )
        // The native-image compiler spends a meaningful share of CI time in GC with its
        // default collector (85s across 3564 collections in the 14.12 release build).
        // Parallel GC trades a little peak RSS for substantially less builder overhead.
        buildArgs.add("-J-XX:+UseParallelGC")
        // Keep the native image's Serial GC from holding on to a large reserve of completely
        // free heap after a collection. This biases the app toward the compact steady-state
        // working set we see after it has been running for a while, without forcing System.gc()
        // or using Windows working-set trimming (both of which just trade RAM for stalls/faults).
        buildArgs.add("-H:MaxHeapFree=16m")
        // Optional compiler budget for the native-image builder JVM. This is not the heap of the
        // produced application, which stays at `maxHeapSize` above.
        nativeImageBuildHeap?.let { buildArgs.add("-J-Xmx$it") }
        nativeImageBuildParallelism?.let { buildArgs.add("--parallelism=$it") }
        nativeImageResourceExcludes.forEach { buildArgs.add(it) }
    }
}

val nativeOutputDir = layout.buildDirectory.dir("compose/tmp/main/graalvm/output/ArchiveTune")
val nativeCompileDir = layout.buildDirectory.dir("compose/tmp/main/graalvm/nativeCompile")
val nativeRuntimeShimNames = when {
    buildingForWindows -> listOf("java.dll", "jvm.dll")
    buildingForLinux -> listOf("libjava.so", "libjvm.so")
    else -> emptyList()
}
val bundledVlcSourceDir = rootProject.layout.projectDirectory.dir("packaging/vlc/common/native/windows-x64/vlc")
val nativeMsiScript = rootProject.layout.projectDirectory.file("packaging/windows/build-native-msi.ps1")
val nativeReleaseDir = desktopAppProject.layout.buildDirectory.dir("compose/binaries/main-native/app/ArchiveTune")
val nativeMsiOutputDir = desktopAppProject.layout.buildDirectory.dir("compose/binaries/main-native/msi")
val wixToolsetDir = rootProject.layout.buildDirectory.dir("wix311")
val stemWorkerProjectDir = rootProject.layout.projectDirectory.dir("stem-worker")
val stemWorkerExe = stemWorkerProjectDir.file("target/release/archivetune-model-runner.exe")
val stemRuntimeBuildDir = rootProject.layout.buildDirectory.dir("stem-runtime/windows-x64")
val stemOrtPackage = rootProject.layout.buildDirectory.file("stem-runtime/packages/onnxruntime-directml-1.24.4.nupkg")
val stemDirectMlPackage = rootProject.layout.buildDirectory.file("stem-runtime/packages/directml-1.15.4.nupkg")
val audioAnalysisDll = desktopAudioAnalysisProject.layout.buildDirectory.file("native/windows-x64/archivetune-audio-analysis.dll")

tasks.matching { it.name == "generateGraalvmWindowsResources" }.configureEach {
    dependsOn(desktopAppProject.tasks.named("generateWindowsIcon"))
}

/*
 * Replaces the full GraalVM JDK runtime libraries with the tiny shims that native-image emitted
 * for this build.
 *
 * Nucleus stages the complete `libjava`/`libjvm` (or `java`/`jvm` on Windows) libraries next to
 * the executable, but the native image does not need the full JVM: it only needs the shims that
 * the image was linked against. Shipping the full libraries instead adds tens of megabytes and
 * crashes the process during shutdown, because the full JVM tears down state the native image
 * never initialised.
 */
val stageNativeRuntimeShims = tasks.register<Copy>("stageNativeRuntimeShims") {
    group = "distribution"
    description = "Replaces the full GraalVM JDK runtime libraries with the shims emitted for this native image."
    dependsOn("nativeImageCompile")
    onlyIf { nativeRuntimeShimNames.isNotEmpty() }
    from(nativeCompileDir) {
        include(nativeRuntimeShimNames)
    }
    into(nativeOutputDir)
    mustRunAfter("packageGraalvmNative")
}

val stageNativeVlcRuntime = tasks.register<StageBundledVlcRuntimeTask>("stageNativeVlcRuntime") {
    group = "distribution"
    description = "Stages ArchiveTune's bundled libVLC runtime beside the GraalVM native executable."
    sourceDirectory.set(bundledVlcSourceDir)
    destinationDirectory.set(nativeOutputDir.map { it.dir("native/windows-x64/vlc") })
    mustRunAfter("packageGraalvmNative")
}

val stageNativeAudioAnalysis = tasks.register<Copy>("stageNativeAudioAnalysis") {
    group = "distribution"
    description = "Stages the Essentia beat-analysis DLL beside the GraalVM native executable."
    dependsOn(desktopAudioAnalysisProject.tasks.named("buildNativeWindows"))
    from(audioAnalysisDll)
    into(nativeOutputDir.map { it.dir("native/windows-x64/audio-analysis") })
    mustRunAfter("packageGraalvmNative")
}

/*
 * Experimental desktop resolver sidecars for the GraalVM native distribution.
 * The runtime probes `<executable parent>/resolver` (and `../resolver`), so the
 * sidecars are staged as a sibling directory of the native executable and then
 * carried into the staged release directory beside it.
 */
val resolverPlatformId = ResolverSidecars.resolvePlatform(
    providers.gradleProperty("archivetune.resolverPlatform").orNull,
)
val resolverNoticesFile = rootProject.layout.projectDirectory.file("packaging/resolver/THIRD_PARTY_NOTICES.md")
val resolverLicensesDir = rootProject.layout.projectDirectory.dir("packaging/resolver/LICENSES")

val stageNativeResolverSidecars = tasks.register<StageResolverSidecarsTask>("stageNativeResolverSidecars") {
    group = "distribution"
    description = "Stages the checksum-verified yt-dlp and Bun sidecars beside the native executable."
    platformId.set(resolverPlatformId ?: "unsupported")
    ytDlpVersion.set(ResolverSidecars.YT_DLP_VERSION)
    bunVersion.set(ResolverSidecars.BUN_VERSION)
    bundledEjsVersion.set(ResolverSidecars.YT_DLP_BUNDLED_EJS_VERSION)
    thirdPartyNotices.set(resolverNoticesFile)
    licensesDirectory.set(resolverLicensesDir)
    destinationDirectory.set(nativeOutputDir.map { it.dir("resolver") })
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
        dependsOn(desktopAppProject.tasks.named(ResolverSidecars.downloadTaskName(ytDlp)))
        dependsOn(desktopAppProject.tasks.named(ResolverSidecars.extractTaskName(bun)))
        ytDlpBinary.set(desktopAppProject.layout.buildDirectory.file(ResolverSidecars.downloadPath(ytDlp)))
        bunBinary.set(desktopAppProject.layout.buildDirectory.file(ResolverSidecars.extractedBinaryPath(bun)))
    }
    mustRunAfter("packageGraalvmNative")
}

val buildStemWorker = tasks.register<Exec>("buildStemWorker") {
    group = "distribution"
    description = "Builds ArchiveTune's native DirectML model-inference sidecar."
    workingDir(stemWorkerProjectDir)
    commandLine("cargo", "build", "--release", "--locked")
    inputs.file(stemWorkerProjectDir.file("Cargo.toml"))
    inputs.file(stemWorkerProjectDir.file("Cargo.lock"))
    inputs.dir(stemWorkerProjectDir.dir("src"))
    outputs.file(stemWorkerExe)
}

val downloadStemOrtRuntime = tasks.register<Exec>("downloadStemOrtRuntime") {
    group = "distribution"
    description = "Downloads the redistributable ONNX Runtime DirectML package for the stem worker."
    val target = stemOrtPackage.get().asFile.absolutePath
    val partial = "$target.part"
    outputs.file(target)
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        "New-Item -ItemType Directory -Force -Path (Split-Path -Parent '$target') | Out-Null; " +
            "curl.exe --fail --location --retry 6 --retry-all-errors --retry-delay 3 --continue-at - " +
            "--output '$partial' 'https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime.directml/1.24.4/microsoft.ml.onnxruntime.directml.1.24.4.nupkg'; " +
            "if (${'$'}LASTEXITCODE -ne 0) { exit ${'$'}LASTEXITCODE }; " +
            "Move-Item -LiteralPath '$partial' -Destination '$target' -Force",
    )
}

val downloadStemDirectMlRuntime = tasks.register<Exec>("downloadStemDirectMlRuntime") {
    group = "distribution"
    description = "Downloads Microsoft's redistributable DirectML package for the stem worker."
    val target = stemDirectMlPackage.get().asFile.absolutePath
    val partial = "$target.part"
    outputs.file(target)
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        "New-Item -ItemType Directory -Force -Path (Split-Path -Parent '$target') | Out-Null; " +
            "curl.exe --fail --location --retry 6 --retry-all-errors --retry-delay 3 --continue-at - " +
            "--output '$partial' 'https://api.nuget.org/v3-flatcontainer/microsoft.ai.directml/1.15.4/microsoft.ai.directml.1.15.4.nupkg'; " +
            "if (${'$'}LASTEXITCODE -ne 0) { exit ${'$'}LASTEXITCODE }; " +
            "Move-Item -LiteralPath '$partial' -Destination '$target' -Force",
    )
}

val prepareStemRuntime = tasks.register<Sync>("prepareStemRuntime") {
    group = "distribution"
    description = "Extracts only the Windows x64 DLLs required by the native stem worker."
    dependsOn(downloadStemOrtRuntime, downloadStemDirectMlRuntime)
    from(stemOrtPackage.map { zipTree(it) }) {
        include("runtimes/win-x64/native/onnxruntime.dll")
        include("runtimes/win-x64/native/onnxruntime_providers_shared.dll")
        eachFile { path = name }
    }
    from(stemDirectMlPackage.map { zipTree(it) }) {
        include("bin/x64-win/DirectML.dll")
        include("runtimes/win-x64/native/DirectML.dll")
        eachFile { path = name }
    }
    includeEmptyDirs = false
    into(stemRuntimeBuildDir)
}

val stageNativeStemRuntime = tasks.register<Copy>("stageNativeStemRuntime") {
    group = "distribution"
    description = "Stages ArchiveTune's model runner and DirectML runtime beside the native executable."
    dependsOn(buildStemWorker, prepareStemRuntime)
    from(stemWorkerExe)
    from(stemRuntimeBuildDir)
    into(nativeOutputDir.map { it.dir("stem-worker") })
    mustRunAfter("packageGraalvmNative")
}

/*
 * Staging steps that only exist for a Windows x64 distribution.
 *
 * A Linux native image links against the system libVLC, ships no DirectML stem worker and no
 * Essentia analysis DLL, so these tasks must not become part of the Linux task graph. They stay
 * registered (so their names remain stable across platforms) but are only wired into the
 * packaging chain on Windows.
 */
val windowsNativeStageTasks: List<TaskProvider<*>> = listOf(
    stageNativeVlcRuntime,
    stageNativeStemRuntime,
    stageNativeAudioAnalysis,
)

/** Every staging step that has to run for the host platform after the native image is packaged. */
val nativeStageTasks: List<TaskProvider<*>> = buildList {
    // The GraalVM runtime shims replace the full JDK libraries on every platform.
    add(stageNativeRuntimeShims)
    if (buildingForWindows) {
        addAll(windowsNativeStageTasks)
    }
    // The yt-dlp and Bun sidecars exist for Windows and Linux x86_64 alike.
    add(stageNativeResolverSidecars)
}

tasks.matching { it.name == "packageGraalvmNative" }.configureEach {
    finalizedBy(nativeStageTasks)
}

val stageNativeDistribution = tasks.register<Sync>("stageNativeDistribution") {
    group = "distribution"
    description = "Stages the production GraalVM distribution under desktopApp/build/compose/binaries/main-native."
    dependsOn(listOf<Any>("packageGraalvmNative") + nativeStageTasks)
    doFirst {
        nativeRuntimeShimNames.forEach { name ->
            val compiled = nativeCompileDir.get().file(name).asFile
            val packaged = nativeOutputDir.get().file(name).asFile
            check(compiled.isFile && packaged.isFile && compiled.readBytes().contentEquals(packaged.readBytes())) {
                "Native runtime shim $name is missing or differs from this build; refusing to stage full JVM libraries."
            }
        }
    }
    from(nativeOutputDir) {
        if (buildingForWindows) {
            // The bundled libVLC runtime is copied separately so its plugin timestamps stay
            // intact for plugins.dat.
            exclude("native/windows-x64/vlc/**")
        }
    }
    into(nativeReleaseDir)
}

val verifyNativeResolverSidecars = tasks.register<VerifyResolverSidecarsTask>("verifyNativeResolverSidecars") {
    group = "verification"
    description = "Verifies the yt-dlp and Bun sidecars carried into the native release directory."
    dependsOn(stageNativeDistribution)
    sidecarDirectory.set(nativeReleaseDir.map { it.dir("resolver") })
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

val stageNativeReleaseVlcRuntime = tasks.register<StageBundledVlcRuntimeTask>("stageNativeReleaseVlcRuntime") {
    group = "distribution"
    description = "Stages the production libVLC runtime with plugin timestamps preserved for plugins.dat."
    dependsOn(stageNativeDistribution)
    sourceDirectory.set(bundledVlcSourceDir)
    destinationDirectory.set(nativeReleaseDir.map { it.dir("native/windows-x64/vlc") })
    onlyIf { buildingForWindows }
}

/*
 * Portable release entry point.
 *
 * `stageNativeReleaseVlcRuntime` only stages the Windows libVLC runtime, so the root
 * `nativeRelease` task has to depend on this task instead to work on every host.
 */
val stageNativeRelease = tasks.register("stageNativeRelease") {
    group = "distribution"
    description = "Stages the production GraalVM native distribution for the current host platform."
    dependsOn(stageNativeDistribution, verifyNativeResolverSidecars)
    if (buildingForWindows) {
        dependsOn(stageNativeReleaseVlcRuntime)
    }
}

tasks.register<Exec>("packageNativeMsi") {
    group = "distribution"
    description = "Builds the production per-user/upgradable MSI from the size-optimized GraalVM native distribution."

    // The MSI is a Windows-only artifact; the task stays registered so its path remains stable
    // across platforms, but it never runs on another host.
    onlyIf { buildingForWindows }

    if (buildingForWindows) {
        dependsOn(
            stageNativeReleaseVlcRuntime,
            verifyNativeResolverSidecars,
            desktopAppProject.tasks.named("generateWindowsIcon"),
            // The Compose plugin only registers the WiX toolset tasks on Windows, so this task
            // does not exist on any other host. Resolving it eagerly would fail the whole
            // configuration there, so the dependency is only declared on Windows.
            rootProject.tasks.named("unzipWix"),
        )
    }

    inputs.dir(nativeReleaseDir)
    inputs.file(nativeMsiScript)
    inputs.file(rootProject.layout.projectDirectory.file("LICENSE"))
    inputs.file(generatedWindowsIcon)
    outputs.file(nativeMsiOutputDir.map { it.file("ArchiveTune-$windowsPackageVersion.msi") })

    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        nativeMsiScript.asFile.absolutePath,
        "-SourceDir",
        nativeReleaseDir.get().asFile.absolutePath,
        "-OutputDir",
        nativeMsiOutputDir.get().asFile.absolutePath,
        "-WixDir",
        wixToolsetDir.get().asFile.absolutePath,
        "-IconPath",
        generatedWindowsIcon.get().asFile.absolutePath,
        "-LicensePath",
        rootProject.layout.projectDirectory.file("LICENSE").asFile.absolutePath,
        "-Version",
        windowsPackageVersion,
    )
}
