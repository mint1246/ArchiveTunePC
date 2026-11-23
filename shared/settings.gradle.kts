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

listOf(
    "sdk-lifecycle",
    "playback-core",
    "ios-playback",
    "discovery-sdk",
    "lyrics-sdk",
    "playlist-sdk",
    "social-sdk",
    "social-session",
).forEach { moduleName ->
    include(":$moduleName")
    project(":$moduleName").projectDir = file("../$moduleName")
}
