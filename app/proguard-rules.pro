# Keep Xposed module entry classes so libxposed can reflect-load them
-keep class com.verifyhub.xposed.ModuleEntry { *; }
-keep class com.verifyhub.xposed.hooks.** { *; }

# Keep Room entities and DAO interfaces
-keep class com.verifyhub.data.** { *; }

# Standard Compose / kotlinx
-keepclassmembers,allowobfuscation class * {
    @androidx.compose.runtime.Composable <methods>;
}
