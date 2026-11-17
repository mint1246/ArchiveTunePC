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
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("sdkLifecycle") {
            artifactId = "sdk-lifecycle"
            from(components["java"])
        }
    }
}
