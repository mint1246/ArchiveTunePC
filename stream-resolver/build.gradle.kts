plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
}
