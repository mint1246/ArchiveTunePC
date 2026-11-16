plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":desktop-auth"))
    api(project(":desktop-settings"))
    api(project(":playback-core"))
    api(libs.coroutines.core)
    implementation(project(":lastfm"))
    implementation(libs.json)
    testImplementation(kotlin("test"))
}
