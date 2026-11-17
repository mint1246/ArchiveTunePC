plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.coroutines.core)
    api(libs.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("social") {
            artifactId = "social-sdk"
            from(components["java"])
        }
    }
}
