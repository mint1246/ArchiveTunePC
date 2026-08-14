plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":desktop-backend"))
    implementation(libs.coroutines.core)
    implementation(libs.jna.platform)
    implementation(libs.json)
    testImplementation(libs.junit)
}
