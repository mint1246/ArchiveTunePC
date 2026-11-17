plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    // Retain Compose stability metadata without pulling Compose into headless consumers.
    compileOnly(libs.compose.runtime)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("playlistSdk") {
            artifactId = "playlist-sdk"
            from(components["java"])
        }
    }
}
