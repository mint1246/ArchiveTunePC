plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    compileOnly(libs.compose.runtime)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("together") {
            artifactId = "together-sdk"
            from(components["java"])
        }
    }
}
