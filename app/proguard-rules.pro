# Keep Retrofit/Gson DTOs and OkHttp interfaces used reflectively.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retrofit service interfaces + Gson models.
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation interface <1>
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-keep class com.typorb.cloud.dto.** { *; }
-keep class com.typorb.**$Companion { *; }

# OkHttp / Okio platform references.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }

# Tink resolves its key managers and parameter serialisation reflectively, and throws
# NoClassDefFoundError / ExceptionInInitializerError rather than an IOException when R8 has
# removed what it needed. Keep its protobuf runtime and its shaded classes intact so a Minified
# build can still construct an EncryptedSharedPreferences at all.
-keep class com.google.crypto.tink.** { *; }
-keep interface com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
# Tink logs via java.util.logging, which is absent on Android.
-dontwarn java.util.logging.**

# The launcher activity and accessibility service are named in the manifest and resolved by the
# framework; R8 keeps them, but their entry points are worth stating explicitly.
-keep class com.typorb.ui.dashboard.MainActivity { *; }
-keep class com.typorb.service.TyporbAccessibilityService { *; }
-keep class com.typorb.TyporbApp { *; }

# Crash reporting. The handler must survive stripping, and the stack trace it renders is the whole
# reason the screen exists, so keep exception names intact for readable reports.
-keep class com.typorb.diagnostics.** { *; }
-keep class com.typorb.ui.diagnostics.** { *; }
-keepattributes SourceFile,LineNumberTable,StackTrace,Signature,Exceptions

# Enum valueOf/values are used for settings and context modes persisted by name.
-keepclassmembers enum com.typorb.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Compose
-dontwarn androidx.compose.**

# Fix R8 missing-errorprone-annotations from tink/crypto (blocks release minification).
# tink references errorprone CheckReturnValue/CanIgnoreReturnValue/RestrictedApi/Immutable,
# but the errorprone annotations jar isn't shipped on the Android classpath.
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.errorprone.annotations.** { *; }

# Keep reflection-safe helpers used by tink/EncryptedSharedPreferences.
-keepclassmembers class com.google.crypto.tink.** {
    <fields>;
    <methods>;
}
-keep class com.google.crypto.tink.** {
    *;
}

# Keep retrolambda / Kotlin JVM stubs if present.
-dontwarn sun.reflect.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.signatures.**