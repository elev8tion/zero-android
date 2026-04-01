# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.zeroclaw.zero.data.** { *; }

# Keep widget provider
-keep class com.zeroclaw.zero.widget.ZeroWidgetProvider { *; }
