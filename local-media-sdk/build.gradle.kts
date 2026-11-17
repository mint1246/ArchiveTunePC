plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    // Retain Compose stability metadata on scan summary models without pulling Compose into
    // headless consumers (same approach as statistics-sdk).
    compileOnly(libs.compose.runtime)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("localMediaSdk") {
            artifactId = "local-media-sdk"
            from(components["java"])
        }
    }
}
