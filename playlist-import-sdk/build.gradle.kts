plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // Retain Compose stability metadata without pulling Compose into headless consumers.
    compileOnly(libs.compose.runtime)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("playlistImport") {
            artifactId = "playlist-import-sdk"
            from(components["java"])
        }
    }
}
