# Lynx: keep JNI, annotated bridge members and reflection-instantiated UI/module classes.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * { @androidx.annotation.Keep *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keepclassmembers class * {
    @com.lynx.tasm.base.CalledByNative *;
    @com.lynx.jsbridge.LynxMethod *;
    @com.lynx.tasm.behavior.LynxProp *;
    @com.lynx.tasm.behavior.LynxPropGroup *;
    @com.lynx.tasm.behavior.LynxUIMethod *;
}
-keep class * extends com.lynx.tasm.behavior.ui.LynxBaseUI { <init>(...); }
-keep class * extends com.lynx.tasm.behavior.shadow.ShadowNode { <init>(...); }
-keep class * extends com.lynx.jsbridge.LynxModule { <init>(...); }
-keep class * extends com.lynx.jsbridge.LynxContextModule { <init>(...); }
-keep class * implements com.lynx.tasm.behavior.Settable { *; }
-keep class * implements com.lynx.tasm.behavior.utils.LynxUISetter { *; }
-keep class * implements com.lynx.tasm.behavior.utils.LynxUIMethodInvoker { *; }
-keep class com.lynx.tasm.rendernode.compat.** { *; }
# Native code looks up many Java methods by name (e.g. LynxLog.log) without annotations.
-keep class com.lynx.** { *; }
-dontwarn com.lynx.**

# Strip app logging from release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# OkHttp
-dontwarn com.facebook.**
-dontwarn okhttp3.**
-dontwarn okio.**

# pdfiumandroid is built with Kotlin 2.2; its unused coroutine API references a newer stdlib helper.
-dontwarn kotlin.coroutines.jvm.internal.SpillingKt
