-dontwarn **
-keepattributes *Annotation*
# 清单里声明的组件:不能被删除或改名
-keep class com.spark.keeper.MainActivity { *; }
-keep class com.spark.keeper.OnboardingActivity { *; }
-keep class com.spark.keeper.AccessibilityConfigActivity { *; }
-keep class com.spark.keeper.ProtocolConfigActivity { *; }
-keep class com.spark.keeper.ProtocolLoginActivity { *; }
-keep class com.spark.keeper.SparkService { *; }
-keep class com.spark.keeper.RunnerService { *; }
-keep class com.spark.keeper.KeepAliveService { *; }
-keep class com.spark.keeper.AlarmReceiver { *; }
-keep class com.spark.keeper.BootReceiver { *; }
# WebView 通过 JS 调用这些方法
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# 资源 R 类字段
-keep class **.R$* { *; }

# Shizuku:provider 由系统实例化,api 与内部 AIDL 之间靠 binder/反射,必须整体保留
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
