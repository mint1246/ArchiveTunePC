plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":playback-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(libs.json)
    implementation("uk.co.caprica:vlcj:4.11.0")
    implementation("uk.co.caprica:vlcj-natives:4.8.3")
    implementation(libs.okhttp)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}
