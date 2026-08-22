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
version = desktopAppProject.version
val generatedWindowsIcon = desktopAppProject.layout.buildDirectory.file("generated/desktop/icon/ArchiveTune.ico")

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    implementation(project(":desktopApp"))
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
        packageVersion = version.toString()
        windows {
            iconFile.set(generatedWindowsIcon)
        }
    }

    graalvm {
        isEnabled.set(true)
        imageName.set("ArchiveTune")
        maxHeapSize.set("128m")
        optimization.set(NativeImageOptimization.SIZE)
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

val nativeOutputDir = layout.buildDirectory.dir("compose/tmp/main/graalvm/output/${project.name}")
val nativeCompileDir = layout.buildDirectory.dir("compose/tmp/main/graalvm/nativeCompile")
val bundledVlcSourceDir = rootProject.layout.projectDirectory.dir("packaging/vlc/common/native/windows-x64/vlc")
val nativeMsiScript = rootProject.layout.projectDirectory.file("packaging/windows/build-native-msi.ps1")
val nativeReleaseDir = desktopAppProject.layout.buildDirectory.dir("compose/binaries/main-native/app/ArchiveTune")
val nativeMsiOutputDir = desktopAppProject.layout.buildDirectory.dir("compose/binaries/main-native/msi")
val wixToolsetDir = rootProject.layout.buildDirectory.dir("wix311")
val desktopVersion = version.toString()

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

tasks.matching { it.name == "packageGraalvmNative" }.configureEach {
    finalizedBy(stageNativeRuntimeShims, stageNativeVlcRuntime)
}

val stageNativeDistribution = tasks.register<Sync>("stageNativeDistribution") {
    group = "distribution"
    description = "Stages the production GraalVM distribution under desktopApp/build/compose/binaries/main-native."
    dependsOn("packageGraalvmNative", stageNativeRuntimeShims, stageNativeVlcRuntime)
    from(nativeOutputDir) {
        exclude("native/windows-x64/vlc/**")
        // The clean CI build does not reliably leave the generated executable in Nucleus' output
        // directory even though nativeImageCompile produced it successfully. Stage the executable
        // from nativeCompile below, which is the authoritative GraalVM build output.
        exclude("ArchiveTune.exe")
    }
    from(nativeCompileDir) {
        include("ArchiveTune.exe")
    }
    into(nativeReleaseDir)
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
        desktopAppProject.tasks.named("generateWindowsIcon"),
        rootProject.tasks.named("unzipWix"),
    )

    inputs.dir(nativeReleaseDir)
    inputs.file(nativeMsiScript)
    inputs.file(rootProject.layout.projectDirectory.file("LICENSE"))
    inputs.file(generatedWindowsIcon)
    outputs.file(nativeMsiOutputDir.map { it.file("ArchiveTune-$desktopVersion.msi") })

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
        desktopVersion,
    )
}
