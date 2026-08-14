plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
