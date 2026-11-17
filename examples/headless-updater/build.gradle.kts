plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("moe.rukamori.archivetune:update-sdk:0.1.0-SNAPSHOT")
}

application {
    mainClass.set("example.HeadlessUpdaterKt")
}
