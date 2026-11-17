@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral {
            mavenContent {
                releasesOnly()
            }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPack"
                    setUrl("https://jitpack.io")
                }
            }
            filter {
                includeGroup("com.github.therealbush")
                includeGroup("com.github.TeamNewPipe")
            }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "ArchiveTuneRuntime"
                    setUrl("https://pub-90881360725b42cab24caebe4e55fa2d.r2.dev/maven")
                }
            }
            filter {
                includeGroup("moe.rukamori.archivetune.runtime")
            }
        }
    }
}

// F-Droid doesn't support foojay-resolver plugin
// plugins {
//     id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
// }

rootProject.name = "ArchiveTune"
include(":app")
include(":audio-analysis-sdk")
include(":statistics-sdk")
include(":account-sdk")
include(":sdk-lifecycle")
include(":social-sdk")
include(":social-realtime")
include(":social-session")
include(":social-login")
include(":discovery-sdk")
include(":download-sdk")
include(":equalizer-sdk")
include(":local-media-sdk")
include(":lyrics-sdk")
include(":lyrics-timeline")
include(":playlist-import-sdk")
include(":playlist-sdk")
include(":backup-sdk")
include(":chords-sdk")
include(":chord-neural-core")
include(":sponsorblock-core")
include(":stems-sdk")
include(":desktop-sponsorblock")
include(":desktop-resource-core")
include(":desktop-stems")
include(":together-sdk")
include(":together-transport")
include(":desktopApp")
include(":desktop-backend")
include(":desktop-spotify-import")
include(":desktop-model-runtime")
include(":desktop-neural-chords")
include(":desktop-canvas")
include(":desktop-lyrics-translation")
include(":desktop-auth")
include(":desktop-social")
include(":playback-core")
include(":android-playback")
include(":stream-resolver")
include(":desktop-playback")
include(":desktop-stream-playback")
include(":desktop-play-together")
include(":desktop-library")
include(":desktop-settings")
include(":desktop-statistics")
include(":desktop-audio-analysis")
include(":desktop-audio-analysis-runtime")
include(":desktop-lyrics-ui")
include(":desktop-lyrics-engine")
include(":windows-media-integration")
include(":linux-media-integration")
include(":desktop-downloads")
include(":desktop-download-orchestration")
include(":desktop-native")
include(":core")
include(":moriextractor")
include(":lyrics:http")
include(":lyrics:kugou")
include(":lyrics:lrclib")
include(":lyrics:simpmusic")
include(":lyrics:paxsenix")
include(":lyrics:betterlyrics")
include(":lyrics:unison")
include(":lyrics:youlyplus")
include(":lyrics:spicylyrics")
include(":lastfm")
include(":canvas")
include(":shazamkit")
include(":spotifycore")
include(":morideobfuscator")
include(":morideobfuscator:jvm")

// Use a local copy of NewPipe Extractor by uncommenting the lines below.
// We assume, that ArchiveTune and NewPipe Extractor have the same parent directory.
// If this is not the case, please change the path in includeBuild().
//
// For this to work you also need to change the implementation in core/build.gradle.kts
// to one which does not specify a version.
// From:
//      implementation(libs.newpipe.extractor)
// To:
//      implementation("com.github.TeamNewPipe:NewPipeExtractor")
// includeBuild("../NewPipeExtractor") {
//    dependencySubstitution {
//        substitute(module("com.github.TeamNewPipe:NewPipeExtractor")).using(project(":extractor"))
//    }
// }
