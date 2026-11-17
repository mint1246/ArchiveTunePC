plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("moe.rukamori.archivetune:social-session:0.1.0-SNAPSHOT")
    implementation("moe.rukamori.archivetune:sdk-lifecycle:0.1.0-SNAPSHOT")
}

kotlin { jvmToolchain(21) }
application { mainClass.set("example.HeadlessSocialSessionKt") }
