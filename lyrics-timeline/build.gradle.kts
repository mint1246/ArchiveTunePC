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
    api(libs.coroutines.core)
    api(libs.guava)
    compileOnly(libs.compose.runtime)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("lyricsTimeline") {
            artifactId = "lyrics-timeline"
            from(components["java"])
        }
    }
}
