# Local AI ProGuard rules
-keep class id.localai.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn com.tom_roush.**
