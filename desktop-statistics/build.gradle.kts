plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":playback-core"))
    implementation(libs.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
