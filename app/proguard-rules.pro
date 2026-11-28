# SŌRA Release ProGuard / R8 Rules
# Preserve debugging line numbers while stripping source filenames for privacy
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve annotations and Kotlin metadata required by reflection/Firestore/Compose
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

# Keep Firestore @Keep model classes and all nested fields/getters/setters
-keep @androidx.annotation.Keep class * { *; }
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** { *; }

# Keep custom JSON AST classes and Security helpers
-keep class com.example.data.ai.JsonNode { *; }
-keep class com.example.data.ai.JsonObj { *; }
-keep class com.example.data.ai.JsonArr { *; }
-keep class com.example.data.ai.JsonStr { *; }
-keep class com.example.data.ai.JsonNum { *; }
-keep class com.example.data.ai.JsonBool { *; }
-keep class com.example.data.ai.JsonNull { *; }
-keep class com.example.core.EncryptedSecretBox { *; }

# Firebase & Google Play Services / Credential Manager
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
}
-keepclassmembers class * {
    @com.google.firebase.firestore.DocumentId <fields>;
}
-keep class com.google.firebase.ai.** { *; }
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.firebase.appcheck.** { *; }
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
