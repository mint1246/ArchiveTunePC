plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("audioAnalysis") {
            groupId = "moe.rukamori.archivetune"
            artifactId = "audio-analysis-sdk"
            version = "0.1.0-SNAPSHOT"
            from(components["java"])
        }
    }
}
