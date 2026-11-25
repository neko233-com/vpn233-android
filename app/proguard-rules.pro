-keep class com.neko233.vpn233.core.MihomoBridge { *; }
-keepclassmembers class com.neko233.vpn233.core.MihomoBridge {
    public static *;
    private static native *;
}
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
