plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":sponsorblock-core"))
    implementation(project(":desktop-settings"))
    implementation(project(":playback-core"))
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
