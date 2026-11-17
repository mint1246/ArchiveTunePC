plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(libs.compose.runtime)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(libs.json)
    testImplementation(kotlin("test-junit5"))
    testImplementation(project(":core"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("account") {
            artifactId = "account-sdk"
            from(components["java"])
        }
    }
}
