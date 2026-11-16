plugins {
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("moe.rukamori.archivetune:playback-core:0.1.0-SNAPSHOT")
    implementation("moe.rukamori.archivetune:audio-analysis-sdk:0.1.0-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

application {
    mainClass.set("example.HeadlessQueue")
}
