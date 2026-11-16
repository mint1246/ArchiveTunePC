plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(project(":sponsorblock-core"))
    implementation(project(":spotifycore"))
    implementation(project(":stream-resolver"))
    implementation(project(":morideobfuscator:jvm"))
    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    api(project(":lyrics:betterlyrics"))
    api(project(":lyrics:kugou"))
    api(project(":lyrics:lrclib"))
    api(project(":lyrics:paxsenix"))
    api(project(":lyrics:simpmusic"))
    api(project(":lyrics:unison"))
    api(project(":lyrics:youlyplus"))
    api(project(":lyrics:spicylyrics"))
    implementation(libs.coroutines.core)
    implementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

// Developer-only classpath export for measuring resolver JVMs without Gradle startup/daemon RSS.
tasks.register("writeResolverBenchmarkClasspath") {
    dependsOn(tasks.testClasses)
    val destination = layout.buildDirectory.file("resolver-benchmark-classpath.txt")
    outputs.file(destination)
    outputs.upToDateWhen { false }
    doLast { destination.get().asFile.writeText(sourceSets.test.get().runtimeClasspath.asPath) }
}
