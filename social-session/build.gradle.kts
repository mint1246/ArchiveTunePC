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
            api(project(":social-sdk"))
            api(libs.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
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
