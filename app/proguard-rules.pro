# libxposed API 官方建议规则
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# 模块入口类与 hook 类保持类名
-keep class top.gtian.clearall.** { *; }
