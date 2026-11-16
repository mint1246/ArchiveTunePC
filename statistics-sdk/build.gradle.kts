plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Retain Compose stability metadata without pulling Compose into headless consumers.
    compileOnly(libs.compose.runtime)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("statistics") {
            groupId = "moe.rukamori.archivetune"
            artifactId = "statistics-sdk"
            version = "0.1.0-SNAPSHOT"
            from(components["java"])
        }
    }
}
