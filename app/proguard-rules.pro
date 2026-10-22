# R8/ProGuard rules for the release build.

# AboutLibraries loads its bundled license metadata and model classes at runtime.
-keep class com.mikepenz.aboutlibraries.** { *; }

# Gson model classes
-keep class com.nutomic.syncthingandroid.model.** { *; }

# Generic type signatures of the TypeToken subclasses Gson reads, e.g. in RestApi.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
