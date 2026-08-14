plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(project(":morideobfuscator"))
    implementation(libs.okhttp)
    api(project(":lyrics:betterlyrics"))
    api(project(":lyrics:kugou"))
    api(project(":lyrics:lrclib"))
    api(project(":lyrics:paxsenix"))
    api(project(":lyrics:simpmusic"))
    api(project(":lyrics:unison"))
    api(project(":lyrics:youlyplus"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}

val smoke = sourceSets.create("smoke")
smoke.compileClasspath += sourceSets.main.get().output
smoke.runtimeClasspath += sourceSets.main.get().output

configurations[smoke.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[smoke.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Runs an opt-in real YouTube Music search and stream-resolution smoke test."
    classpath = smoke.runtimeClasspath
    mainClass.set("moe.rukamori.archivetune.backend.BackendSmokeKt")
    dependsOn(smoke.classesTaskName)
}

tasks.register<JavaExec>("lyricsSmokeTest") {
    group = "verification"
    description = "Runs an opt-in live search against every built-in lyrics provider."
    classpath = smoke.runtimeClasspath
    mainClass.set("moe.rukamori.archivetune.backend.LyricsSmokeKt")
    dependsOn(smoke.classesTaskName)
}
