# R8/ProGuard rules for the release build.

# AboutLibraries loads its bundled license metadata and model classes at runtime.
-keep class com.mikepenz.aboutlibraries.** { *; }

# Model classes read and written as JSON, with the serializers kotlinx.serialization generates.
-keep class com.nutomic.syncthingandroid.model.** { *; }
