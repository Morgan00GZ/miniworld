# Shizuku API 使用反射调用 newProcess，保留类与方法名
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# 通过反射访问的 ActivityOptions 隐藏方法
-keepclassmembers class android.app.ActivityOptions {
    *** setLaunchWindowingMode(int);
}
