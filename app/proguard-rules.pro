# ---- OkHttp / Okio ----
# OkHttp references optional JVM-only TLS providers that aren't on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- ML Kit (bundled Chinese text recognition) ----
# The recognizer loads its pipeline/model classes reflectively; keep the ML Kit
# API surface and silence warnings for its optional transitive deps.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ---- Room ----
# room-runtime ships its own consumer rules; keep the generated DB impl's
# no-arg constructor defensively (accessed via Room.databaseBuilder reflection).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---- Kotlin ----
# Keep coroutine internals that are only referenced reflectively.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
