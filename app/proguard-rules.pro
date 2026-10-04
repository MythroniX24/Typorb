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