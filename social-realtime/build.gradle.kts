plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":social-sdk"))
    implementation(libs.coroutines.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
}

publishing {
    publications {
        create<MavenPublication>("socialRealtime") {
            artifactId = "social-realtime"
            from(components["java"])
        }
    }
}
