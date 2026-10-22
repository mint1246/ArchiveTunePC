plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":playback-core"))
    implementation(libs.jna.platform)
    implementation("com.github.hypfvieh:dbus-java-core:5.2.2")
    runtimeOnly("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.2")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    val memorySmoke = providers.environmentVariable("ARCHIVETUNE_MEMORY_SMOKE").getOrElse("")
    environment("ARCHIVETUNE_MEMORY_SMOKE", memorySmoke)
    inputs.property("memorySmoke", memorySmoke)
}
