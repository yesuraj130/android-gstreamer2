# GStreamer native calls
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class org.freedesktop.gstreamer.** { *; }
-keep class com.gstlauncher.app.** { *; }
