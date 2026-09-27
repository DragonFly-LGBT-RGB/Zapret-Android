-keep class ru.dragonfly.zapret.core.ByeDpiNative { *; }
-keep class ru.dragonfly.zapret.core.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
