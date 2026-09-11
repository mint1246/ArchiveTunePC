plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
}
