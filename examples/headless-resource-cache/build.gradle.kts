plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("moe.rukamori.archivetune:desktop-resource-core:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin { jvmToolchain(21) }
application { mainClass.set("example.HeadlessResourceCacheKt") }
