plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":social-realtime"))
    api(libs.coroutines.core)
    implementation(project(":together-sdk"))
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("socialSession") {
            artifactId = "social-session"
            from(components["java"])
        }
    }
}
