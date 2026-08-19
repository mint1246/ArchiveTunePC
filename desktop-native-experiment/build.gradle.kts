import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    id("dev.nucleusframework") version "2.1.10"
}

group = "moe.rukamori.archivetune"
version = "14.1.0-native-experiment"

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
    mainClass = "moe.rukamori.archivetune.nativeexperiment.NativeMainKt"

    graalvm {
        isEnabled.set(true)
        imageName.set("ArchiveTuneNative")
        maxHeapSize.set("768m")
        optimization.set(NativeImageOptimization.SIZE)
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

val stageNativeRuntimeShims = tasks.register<Copy>("stageNativeRuntimeShims") {
    group = "distribution"
    description = "Replaces full GraalVM JDK java/jvm DLLs with the tiny shims emitted for this native image."
    dependsOn("nativeImageCompile")
    from(nativeCompileDir) {
        include("java.dll", "jvm.dll")
    }
    into(nativeOutputDir)
}

val stageNativeVlcRuntime = tasks.register<StageBundledVlcRuntimeTask>("stageNativeVlcRuntime") {
    group = "distribution"
    description = "Stages ArchiveTune's bundled libVLC runtime beside the GraalVM native executable."
    sourceDirectory.set(bundledVlcSourceDir)
    destinationDirectory.set(nativeOutputDir.map { it.dir("native/windows-x64/vlc") })
}

tasks.matching { it.name == "packageGraalvmNative" }.configureEach {
    finalizedBy(stageNativeRuntimeShims, stageNativeVlcRuntime)
}
