-dontobfuscate
-keep class kotlin.** { *; }
-keep class com.desertcomm.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
# Fix R8 IllegalAccessError on Android 17 ART
-keep class kotlin.sequences.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.sequences.**


# Sada v4.3 R8 Protection (Rust JNI + WebRTC + Android 16/17 ART)
-keep class com.desertcomm.core.NativeBridge { *; }
-keep class com.desertcomm.core.NativeBridgeV3 { *; }
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class kotlin.sequences.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.sequences.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class com.desertcomm.core.** { *; }
-keep class com.desertcomm.p2p.** { *; }
-keep class com.desertcomm.security.** { *; }

-keep class org.torproject.** { *; }
-dontwarn org.torproject.**

# Strip debug/verbose/info logs in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}