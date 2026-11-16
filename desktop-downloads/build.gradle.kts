plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.coroutines.core)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

publishing {
    publications {
        create<MavenPublication>("downloads") {
            artifactId = "desktop-downloads"
            from(components["java"])
        }
    }
}
