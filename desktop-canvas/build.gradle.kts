plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":canvas"))
    api(project(":desktop-settings"))
    api(libs.coroutines.core)
    implementation(project(":spotifycore"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
