-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

-keep class com.v2ray.ang.ui.main.RuroaNative { *; }

-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.enums.** { *; }
-keepclassmembers class com.v2ray.ang.dto.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keep class libv2ray.** { *; }
-keep class go.** { *; }
-keep class * implements libv2ray.CoreCallbackHandler { *; }
-keep class * implements libv2ray.ProcessFinder { *; }
-keep class com.v2ray.ang.core.CoreServiceManager$* { *; }

-keep class com.v2ray.ang.service.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.tencent.mmkv.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
