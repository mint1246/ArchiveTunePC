plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":playback-core"))
    implementation(libs.coroutines.core)
    implementation("net.java.dev.jna:jna-jpms:5.17.0")
    implementation("net.java.dev.jna:jna-platform-jpms:5.17.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test { useJUnitPlatform() }
