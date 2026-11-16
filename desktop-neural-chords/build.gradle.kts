plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":chords-sdk"))
    api(project(":playback-core"))
    implementation(project(":desktop-model-runtime"))
    implementation(libs.coroutines.core)
    implementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("desktopNeuralChords") {
            artifactId = "desktop-neural-chords"
            from(components["java"])
        }
    }
}
