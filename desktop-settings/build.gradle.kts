plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("desktopSettings") {
            from(components["java"])
        }
    }
}
