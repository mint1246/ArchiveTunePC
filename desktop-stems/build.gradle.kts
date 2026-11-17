plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":playback-core"))
    api(project(":stems-sdk"))
    implementation(project(":desktop-model-runtime"))
    implementation(libs.json)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

publishing {
    publications {
        create<MavenPublication>("desktopStems") {
            artifactId = "desktop-stems"
            from(components["java"])
        }
    }
}
