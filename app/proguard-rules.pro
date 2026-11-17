# Traccar 原生客户端 R8 保留规则（release 已开启 minify + shrinkResources）
#
# 说明：
#  - MapLibre / OkHttp 的 AAR 自带 consumer rules，这里只做「兜底 + 反射/JNI 保护」；
#  - 数据模型由 org.json 手工解析，不依赖反射，但保留字段名与模型类便于排查，
#    同时避免后续引入序列化库时被裁掉；
#  - 不保留整个 app 包，否则 R8 实际上不起作用。

# 保留泛型签名、注解与异常声明（JSON/反射/协程恢复需要）
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions

# 应用数据模型与 JSON 字段
-keep class com.traccar.nativeapp.data.** { *; }

# JNI 方法名不能被混淆
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# MapLibre（含注解、回调接口与 native 绑定）
-keep class org.maplibre.** { *; }
-keep interface org.maplibre.** { *; }
-keepclassmembers class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# OkHttp / Okio（含可选 TLS 提供方）
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# Kotlin 协程内部状态机与调试元数据
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**