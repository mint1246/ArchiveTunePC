plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
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
