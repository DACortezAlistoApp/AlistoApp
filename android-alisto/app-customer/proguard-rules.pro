# Preserve Leaflet WebView JavascriptInterface bridge in release builds
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class ph.alisto.kernel.** { *; }
