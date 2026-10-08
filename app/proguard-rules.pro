# Release builds are minified with R8 (smaller and faster). Most libraries ship their own rules
# (Room, WorkManager, Hilt, OkHttp, kotlinx.serialization); these cover the rest.

# SQLCipher: its native code looks up these classes and members by name.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# kotlinx.serialization: backups and prompts' JSON go through generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.umair.purpose.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.umair.purpose.**$$serializer { *; }

# Stack traces in the local crash log stay readable enough to act on.
-keepattributes SourceFile, LineNumberTable
