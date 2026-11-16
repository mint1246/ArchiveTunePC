plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":playback-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(libs.json)
    implementation("uk.co.caprica:vlcj:4.11.0")
    implementation("uk.co.caprica:vlcj-natives:4.8.3")
    api(libs.okhttp)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // The live yt-dlp playback integration test resolves a real stream through the desktop
    // backend and feeds it to the existing ranged callback transport.
    if (findProject(":desktop-backend") != null) testImplementation(project(":desktop-backend"))
}

tasks.test {
    useJUnitPlatform()
    // The live yt-dlp playback integration test is opt-in. Forward the resolver toolchain
    // variables so the test JVM sees the same opt-in as the Gradle client. The matching system
    // properties are accepted as an alternative for daemons that were started without the
    // environment. The values are part of the task inputs so a cached result of a non-opt-in run
    // is never reused for an opt-in run.
    listOf(
        "ARCHIVETUNE_LIVE_YTDLP" to "archivetune.liveYtdlp",
        "ARCHIVETUNE_LIVE_YTDLP_VIDEO" to "archivetune.liveYtdlpVideo",
        "ARCHIVETUNE_YTDLP" to "archivetune.ytdlp",
        "ARCHIVETUNE_BUN" to "archivetune.bun",
    ).forEach { (environmentName, propertyName) ->
        val forwarded = providers.environmentVariable(environmentName).orNull
            ?: providers.systemProperty(propertyName).orNull
        if (!forwarded.isNullOrBlank()) {
            environment(environmentName, forwarded)
            systemProperty(propertyName, forwarded)
        }
        inputs.property(environmentName, forwarded.orEmpty())
    }
}

publishing {
    publications {
        create<MavenPublication>("desktopPlayback") {
            artifactId = "desktop-playback"
            from(components["java"])
        }
    }
}

// Keep the established standalone playback-core build usable without the full backend graph.
if (findProject(":desktop-backend") == null) {
    kotlin.sourceSets.named("test") {
        kotlin.exclude("**/YtDlpPlaybackIntegrationTest.kt")
    }
}
