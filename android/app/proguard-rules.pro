# SQLCipher native bindings
-keep class net.zetetic.database.** { *; }
# Ktor/OkHttp
-dontwarn org.slf4j.**

# The published Fold build (build type `fold`, build plan: smaller phone updates): shrink, don't rename, so the
# stack traces in Activity, Health and the server's update/crash reports read as the source does.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
# Ktor/OkHttp optional platforms
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
# WorkManager opens its Room database and makes workers by reflection; the fold-smoke run (2026-10-12) crashed at
# start with WorkDatabase_Impl.<init> stripped. Keep Room databases' and workers' constructors.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class * extends androidx.work.InputMerger { <init>(); }
