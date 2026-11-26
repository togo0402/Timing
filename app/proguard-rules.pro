# ---- Compose / AndroidX ----
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---- kotlinx.serialization ----
-keepattributes RuntimeVisibleAnnotations
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.togotiming.app.domain.**$$serializer { *; }
-keepclassmembers class com.togotiming.app.domain.** {
    *** Companion;
}
-keepclasseswithmembers class com.togotiming.app.domain.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 小部件 / RemoteViews（反射调用 setChronometer 等） ----
-keep class com.togotiming.app.widget.** { *; }
-keep class com.togotiming.app.timer.** { *; }

# ---- 防止 R8 删除 Room 生成的实现 ----
-keep class com.togotiming.app.data.**_Impl { *; }

-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
