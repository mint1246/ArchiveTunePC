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
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("socialLogin") {
            artifactId = "social-login"
            from(components["java"])
        }
    }
}
