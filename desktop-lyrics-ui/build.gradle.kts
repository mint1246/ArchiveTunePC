plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":playback-core"))
    implementation(project(":desktop-backend"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}

val smoke = sourceSets.create("smoke")
smoke.compileClasspath += sourceSets.main.get().output
smoke.runtimeClasspath += sourceSets.main.get().output

configurations[smoke.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[smoke.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("lyricsRenderSmokeTest") {
    group = "verification"
    description = "Searches live lyrics providers and verifies their documents render into lines without printing lyrics."
    classpath = smoke.runtimeClasspath
    mainClass.set("moe.rukamori.archivetune.desktop.lyrics.LyricsRenderSmokeKt")
    dependsOn(smoke.classesTaskName)
}
