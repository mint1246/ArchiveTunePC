plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":sponsorblock-core"))
    api(project(":desktop-settings"))
    api(project(":playback-core"))
    implementation(libs.coroutines.core)
    api(libs.okhttp)
    implementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
