# Keep serializable models across all app packages (data, pin, playlist,
# history) — kotlinx.serialization resolves some serializers reflectively.
-keepclassmembers class app.shelfie.** {
    *** Companion;
}
-keepclasseswithmembers class app.shelfie.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.shelfie.**$$serializer { *; }

# Retrofit reflects on interface methods and generic signatures.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep interface app.shelfie.data.AbsApi { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
