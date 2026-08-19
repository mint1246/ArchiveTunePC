plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":playback-core"))
    implementation(libs.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.libdatachannel.java.arch.detect)
    implementation(libs.jdt.annotations)
    implementation(libs.json)
    testImplementation(kotlin("test"))
}
