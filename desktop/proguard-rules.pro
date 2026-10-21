# Rules on top of Compose Desktop's defaults for the release (MSI) build.
# JNA binds Windows APIs by reflection over these interfaces.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn kotlinx.serialization.**
-dontwarn kotlinx.coroutines.**
