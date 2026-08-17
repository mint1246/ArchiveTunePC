# ArchiveTune Desktop release rules. Keep only runtime-reflective/native surfaces;
# optional TLS/native-image providers are intentionally absent on the JVM build.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class moe.rukamori.archivetune.desktop.MainKt {
    public static void main(java.lang.String[]);
}

# JNA constructs native mappings and structures reflectively.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * implements com.sun.jna.Library { *; }

# VLCJ's binding model is a JNA ABI surface. JNA reflectively creates pointer/native-mapped
# wrapper types (for example libvlc_instance_t) and requires their public no-arg constructors.
# ProGuard cannot see those calls and otherwise removes the constructors during optimization.
-keep class uk.co.caprica.vlcj.binding.** { *; }

# Rhino generates/accesses Java members dynamically for YouTube deciphering.
-keep class org.mozilla.javascript.** { *; }

# ServiceLoader providers are referenced only from META-INF/services resources, so ProGuard's
# static reachability analysis cannot discover them. Keep the provider implementations and their
# names in sync with those resources.
-keep class coil3.network.okhttp.internal.OkHttpNetworkFetcherServiceLoaderTarget { *; }
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }
-keep class org.slf4j.nop.NOPServiceProvider { *; }
-keep class uk.co.caprica.vlcj.factory.discovery.provider.** { *; }

# These enums are intentionally bridged by name/valueOf across desktop modules and persisted
# settings. Preserve their enum identity and constant names so optimizer enum simplification and
# obfuscation cannot change that contract.
-keep enum moe.rukamori.archivetune.downloads.PartialCleanupPolicy { *; }
-keep enum moe.rukamori.archivetune.desktop.settings.DesktopLastDestination { *; }
-keep enum moe.rukamori.archivetune.desktop.settings.DesktopThemeMode { *; }
-keep enum moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode { *; }
-keep enum moe.rukamori.archivetune.desktop.state.DesktopDestination { *; }
-keep enum moe.rukamori.archivetune.desktop.state.DesktopThemePreference { *; }
-keep enum moe.rukamori.archivetune.playback.api.RepeatMode { *; }

# ProGuard 7.9.1 mis-optimizes Okio 3.17's Kotlin buffer bridge on JVM 21 and emits bytecode
# that fails verification at runtime (BufferedSink returned from a RealBufferedSink signature).
# Keep shrinking and obfuscation enabled, but leave Okio method bodies unoptimized.
-keep,allowshrinking,allowobfuscation class okio.** { *; }

# Optional OkHttp platform providers and Graal substitutions are not shipped.
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# ProGuard's JDK model does not expose this Java 21 VarHandle signature, while
# the runtime does. Rhino is kept intact above.
-dontwarn org.mozilla.javascript.SlotMapOwner$ThreadedAccess
