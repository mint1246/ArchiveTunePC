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
    api(project(":together-sdk"))
    api(libs.coroutines.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    compileOnly(libs.compose.runtime)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("togetherTransport") {
            artifactId = "together-transport"
            from(components["java"])
        }
    }
}
