import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    }
}

val nativeOutputDir = layout.buildDirectory.dir("compose/tmp/main/graalvm/output/${project.name}")
val bundledVlcSourceDir = rootProject.layout.projectDirectory.dir("packaging/vlc/common/native/windows-x64/vlc")

val stageNativeVlcRuntime = tasks.register<Sync>("stageNativeVlcRuntime") {
    group = "distribution"
    description = "Stages ArchiveTune's bundled libVLC runtime beside the GraalVM native executable."
    from(bundledVlcSourceDir)
    into(nativeOutputDir.map { it.dir("native/windows-x64/vlc") })
}

tasks.matching { it.name == "packageGraalvmNative" }.configureEach {
    finalizedBy(stageNativeVlcRuntime)
}
