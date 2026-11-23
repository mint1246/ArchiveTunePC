plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "moe.rukamori.archivetune"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.ktor.client.core)
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        jvmMain.dependencies {
            api(libs.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(kotlin("test-junit"))
        }
    }
}

if (tasks.findByName("test") == null) {
    tasks.register("test") {
        group = "verification"
        description = "Runs JVM unit tests for backwards compatibility with JVM-only scripts."
        dependsOn("jvmTest")
    }
}
