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
        windows {
            iconFile.set(generatedWindowsIcon)
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
        // Nucleus' static resource scanner sees native payloads for every supported platform in
        // a few dependencies. They can never be loaded by this Windows-x64 image, but Graal would
        // otherwise embed them verbatim in the executable's image heap.
        buildArgs.add(
            """-H:ExcludeResources=^(macos-(x86_64|arm64)|x86_64|aarch64)/native/libdatachannel-java[.](dylib|so)$""",
        )
        buildArgs.add(
            """-H:ExcludeResources=^com/sun/jna/(aix|darwin|dragonflybsd|freebsd|linux|openbsd|sunos)-.*/.*$""",
        )
        buildArgs.add("""-H:ExcludeResources=^com/sun/jna/win32-(aarch64|x86)/.*$""")
        buildArgs.add("""-H:ExcludeResources=^nucleus/native/(darwin|linux)-.*/.*$""")
        // Nucleus already stages this DLL beside the executable in bin/, so keeping a second
        // byte-for-byte copy in the image heap only inflates the EXE.
        buildArgs.add("""-H:ExcludeResources=^skiko-windows-x64[.]dll$""")
    }
}

val nativeOutputDir = layout.buildDirectory.dir("compose/tmp/main/graalvm/output/ArchiveTune")
val nativeCompileDir = layout.buildDirectory.dir("compose/tmp/main/graalvm/nativeCompile")
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

val stageNativeRuntimeShims = tasks.register<Copy>("stageNativeRuntimeShims") {
    group = "distribution"
    description = "Replaces full GraalVM JDK java/jvm DLLs with the tiny shims emitted for this native image."
    dependsOn("nativeImageCompile")
    from(nativeCompileDir) {
        include("java.dll", "jvm.dll")
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
    description = "Stages the checksum-verified yt-dlp and quickjs-ng sidecars beside the native executable."
    platformId.set(resolverPlatformId ?: "unsupported")
    ytDlpVersion.set(ResolverSidecars.YT_DLP_VERSION)
    quickJsVersion.set(ResolverSidecars.QUICKJS_VERSION)
    bundledEjsVersion.set(ResolverSidecars.YT_DLP_BUNDLED_EJS_VERSION)
    thirdPartyNotices.set(resolverNoticesFile)
    licensesDirectory.set(resolverLicensesDir)
    destinationDirectory.set(nativeOutputDir.map { it.dir("resolver") })
    if (resolverPlatformId == null) {
        ytDlpUrl.set("")
        ytDlpSha256.set("")
        quickJsUrl.set("")
        quickJsSha256.set("")
        unsupportedReason.set(ResolverSidecars.unsupportedPlatformMessage())
    } else {
        val ytDlp = ResolverSidecars.assetFor(resolverPlatformId, ResolverSidecarKind.YT_DLP)
        val quickJs = ResolverSidecars.assetFor(resolverPlatformId, ResolverSidecarKind.QUICKJS)
        ytDlpUrl.set(ytDlp.url)
        ytDlpSha256.set(ytDlp.sha256)
        quickJsUrl.set(quickJs.url)
        quickJsSha256.set(quickJs.sha256)
        dependsOn(desktopAppProject.tasks.named(ResolverSidecars.downloadTaskName(ytDlp)))
        dependsOn(desktopAppProject.tasks.named(ResolverSidecars.downloadTaskName(quickJs)))
        ytDlpBinary.set(desktopAppProject.layout.buildDirectory.file(ResolverSidecars.downloadPath(ytDlp)))
        quickJsBinary.set(desktopAppProject.layout.buildDirectory.file(ResolverSidecars.downloadPath(quickJs)))
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

tasks.matching { it.name == "packageGraalvmNative" }.configureEach {
    finalizedBy(stageNativeRuntimeShims, stageNativeVlcRuntime, stageNativeStemRuntime, stageNativeAudioAnalysis, stageNativeResolverSidecars)
}

val stageNativeDistribution = tasks.register<Sync>("stageNativeDistribution") {
    group = "distribution"
    description = "Stages the production GraalVM distribution under desktopApp/build/compose/binaries/main-native."
    dependsOn("packageGraalvmNative", stageNativeRuntimeShims, stageNativeVlcRuntime, stageNativeStemRuntime, stageNativeAudioAnalysis, stageNativeResolverSidecars)
    from(nativeOutputDir) {
        exclude("native/windows-x64/vlc/**")
    }
    into(nativeReleaseDir)
}

val verifyNativeResolverSidecars = tasks.register<VerifyResolverSidecarsTask>("verifyNativeResolverSidecars") {
    group = "verification"
    description = "Verifies the yt-dlp and quickjs-ng sidecars carried into the native release directory."
    dependsOn(stageNativeDistribution)
    sidecarDirectory.set(nativeReleaseDir.map { it.dir("resolver") })
    platformId.set(resolverPlatformId ?: "unsupported")
    if (resolverPlatformId == null) {
        ytDlpSha256.set("")
        quickJsSha256.set("")
        unsupportedReason.set(ResolverSidecars.unsupportedPlatformMessage())
    } else {
        ytDlpSha256.set(ResolverSidecars.assetFor(resolverPlatformId, ResolverSidecarKind.YT_DLP).sha256)
        quickJsSha256.set(ResolverSidecars.assetFor(resolverPlatformId, ResolverSidecarKind.QUICKJS).sha256)
    }
}

val stageNativeReleaseVlcRuntime = tasks.register<StageBundledVlcRuntimeTask>("stageNativeReleaseVlcRuntime") {
    group = "distribution"
    description = "Stages the production libVLC runtime with plugin timestamps preserved for plugins.dat."
    dependsOn(stageNativeDistribution)
    sourceDirectory.set(bundledVlcSourceDir)
    destinationDirectory.set(nativeReleaseDir.map { it.dir("native/windows-x64/vlc") })
}

tasks.register<Exec>("packageNativeMsi") {
    group = "distribution"
    description = "Builds the production per-user/upgradable MSI from the size-optimized GraalVM native distribution."

    dependsOn(
        stageNativeReleaseVlcRuntime,
        verifyNativeResolverSidecars,
        desktopAppProject.tasks.named("generateWindowsIcon"),
        rootProject.tasks.named("unzipWix"),
    )

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
