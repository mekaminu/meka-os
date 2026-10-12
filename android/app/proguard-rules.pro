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
