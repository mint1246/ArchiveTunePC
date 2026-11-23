// Standalone shared tests avoid configuring the Android SDK and Windows packaging toolchain.
pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            // iOS builds are isolated from desktop's newer Compose/Skiko ABI.
            version("composeMultiplatform", "1.10.3")
        }
    }
}
rootProject.name = "ArchiveTuneShared"
