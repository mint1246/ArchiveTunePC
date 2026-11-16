plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":desktop-backend"))
    api(project(":desktop-auth"))
    api(project(":desktop-settings"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
