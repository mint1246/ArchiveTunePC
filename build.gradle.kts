plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("app/src/debug/**/*.kt", "app/src/nightly/**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktfmt("0.54").googleStyle()
    }
}

tasks.named<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("releaseJ9") {
    group = "distribution"
    description = "Builds the fast OpenJ9 desktop distributable with JIT enabled (no ProGuard)."
    dependsOn(":desktopApp:createOpenJ9Distributable")
}

tasks.register("msiJ9") {
    group = "distribution"
    description = "Builds the fast upgradable OpenJ9 JIT desktop MSI (no ProGuard)."
    dependsOn(":desktopApp:packageOpenJ9MsiUpgradable")
}

tasks.register("releaseJ9Shrunk") {
    group = "distribution"
    description = "Builds the smaller OpenJ9 JIT desktop distributable through ProGuard."
    dependsOn(":desktopApp:createOpenJ9ReleaseDistributable")
}

tasks.register("msiJ9Shrunk") {
    group = "distribution"
    description = "Builds the smaller upgradable OpenJ9 JIT MSI through ProGuard."
    dependsOn(":desktopApp:packageOpenJ9ReleaseMsiUpgradable")
}

tasks.register("desktopDev") {
    group = "distribution"
    description = "Runs the regular JVM desktop app for the fastest development cycle."
    dependsOn(":desktopApp:run")
}

tasks.register("nativeCheck") {
    group = "distribution"
    description = "Builds only the size-optimized GraalVM executable, skipping distribution staging."
    dependsOn(":desktop-native:nativeImageCompile")
}

tasks.register("nativeRelease") {
    group = "distribution"
    description = "Builds the size-optimized GraalVM Native Image for release packaging."
    dependsOn(":desktop-native:stageNativeRelease")
}

tasks.register("msiNative") {
    group = "distribution"
    description = "Builds the production upgradable MSI from the size-optimized GraalVM Native Image."
    dependsOn(":desktop-native:packageNativeMsi")
}

if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel") {}
}

subprojects {
    if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
        tasks.register("prepareKotlinBuildScriptModel") {}
    }
}

subprojects {
    tasks.matching { task ->
        task.name.startsWith("assemble") &&
            (task.name.endsWith("Debug") || task.name.endsWith("Nightly"))
    }.configureEach {
        dependsOn(rootProject.tasks.named("spotlessApply"))
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.add("-P")
                    freeCompilerArgs.add(
                        "plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${project.layout.buildDirectory}/compose_metrics",
                    )
                }
            }
        }
    }
}

// Force Gradle to fetch fresh SNAPSHOTs instead of turning off the build cache
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            cacheChangingModulesFor(0, "seconds")
        }
    }
}
