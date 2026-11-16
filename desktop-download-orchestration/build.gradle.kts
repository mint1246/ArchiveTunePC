plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":desktop-backend"))
    api(project(":desktop-downloads"))
    api(project(":desktop-settings"))
    api(libs.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
