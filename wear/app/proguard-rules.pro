# SQLCipher native bindings
-keep class net.zetetic.database.** { *; }
# Ktor/OkHttp
-dontwarn org.slf4j.**

# The shrunk `watch` build (build plan: smaller phone updates): shrink, don't rename, so traces read as the source.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
# Ktor/OkHttp optional platforms
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
# WorkManager opens its Room database and makes workers by reflection (the Fold's smoke run caught this, 2026-10-12).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class * extends androidx.work.InputMerger { <init>(); }
