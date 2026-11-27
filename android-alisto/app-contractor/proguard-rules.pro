# Alisto R8 Release Hardening Rules
# Keep Leaflet WebView @JavascriptInterface callbacks intact
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Repackage & flatten obfuscated classes into a single package to deter static analysis
-repackageclasses 'p'
-allowaccessmodification

# Strip verbose/debug/info logs from release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Suppress harmless optional OkHttp/Conscrypt/Coroutines warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
