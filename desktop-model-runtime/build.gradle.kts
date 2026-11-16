plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.json)
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("desktopModelRuntime") {
            artifactId = "desktop-model-runtime"
            from(components["java"])
        }
    }
}
