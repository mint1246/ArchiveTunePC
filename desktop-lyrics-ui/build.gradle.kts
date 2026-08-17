plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":playback-core"))
    implementation(project(":desktop-backend"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}
