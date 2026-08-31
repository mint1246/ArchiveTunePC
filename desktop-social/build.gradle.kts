plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":desktop-auth"))
    implementation(libs.coroutines.core)
    implementation(libs.json)
    testImplementation(kotlin("test"))
}
