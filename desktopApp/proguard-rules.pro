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

# Rhino generates/accesses Java members dynamically for YouTube deciphering.
-keep class org.mozilla.javascript.** { *; }

# Optional OkHttp platform providers and Graal substitutions are not shipped.
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# ProGuard's JDK model does not expose this Java 21 VarHandle signature, while
# the runtime does. Rhino is kept intact above.
-dontwarn org.mozilla.javascript.SlotMapOwner$ThreadedAccess
