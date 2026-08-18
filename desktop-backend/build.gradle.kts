plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(project(":spotifycore"))
    implementation(project(":stream-resolver"))
    implementation(project(":morideobfuscator"))
    implementation(libs.okhttp)
    api(project(":lyrics:betterlyrics"))
    api(project(":lyrics:kugou"))
    api(project(":lyrics:lrclib"))
    api(project(":lyrics:paxsenix"))
    api(project(":lyrics:simpmusic"))
    api(project(":lyrics:unison"))
    api(project(":lyrics:youlyplus"))
    api(project(":lyrics:spicylyrics"))
    implementation(libs.coroutines.core)
    implementation(libs.json)
    testImplementation(libs.junit)
}
