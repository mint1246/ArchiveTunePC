plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

val nativeOutputDir = layout.buildDirectory.dir("native/windows-x64")
val nativeDll = nativeOutputDir.map { it.file("archivetune-audio-analysis.dll") }

val buildNativeWindows = tasks.register<Exec>("buildNativeWindows") {
    group = "build"
    description = "Builds the minimal in-process Essentia audio-analysis DLL for Windows x64."

    val script = layout.projectDirectory.file("native/build-windows.ps1")
    val bridge = layout.projectDirectory.file("native/archivetune_audio_analysis.cpp")
    inputs.files(script, bridge)
    outputs.file(nativeDll)

    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", script.asFile.absolutePath,
        "-OutputDir", nativeOutputDir.get().asFile.absolutePath,
    )
}

dependencies {
    implementation(libs.jna.platform)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(buildNativeWindows)
    inputs.file(nativeDll)
    systemProperty("archivetune.audioAnalysis.library", nativeDll.get().asFile.absolutePath)
}

tasks.register("nativeCheck") {
    group = "verification"
    description = "Builds the native audio-analysis DLL and runs the binding tests."
    dependsOn(buildNativeWindows, tasks.test)
}
