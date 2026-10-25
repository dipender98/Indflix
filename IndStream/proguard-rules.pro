# CloudStream loads the plugin by instantiating the Plugin subclass, and users
# reach everything else through statically-referenced code paths, so only the
# reflective entry points must survive shrinking. Everything reachable from
# IndStreamPlugin is kept automatically by R8's reachability analysis.
-keep class com.indstream.IndStream { *; }

# R8 size rules: access relaxation and package flattening.
-allowaccessmodification
-repackageclasses ''

# Generated R class is unused (requiresResources = false); let it shrink.
-keep,allowshrinking class **.R
-keep,allowshrinking class **.R$*

# Annotations + generics info needed by CloudStream's dex inspection and the
# gradle plugin's repo.json generation.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Dependencies below are provided by the CloudStream app at runtime and are not
# packaged into the .cs3; just silence missing-reference warnings.
-dontwarn okhttp3.**
-dontwarn com.lagradost.nicehttp.**
-dontwarn io.ktor.**
-dontwarn org.jsoup.**
-dontwarn org.json.**
-dontwarn javax.crypto.**
-dontwarn java.security.**
-dontwarn com.fasterxml.jackson.**
-dontwarn kotlinx.datetime.**
-dontwarn kotlinx.serialization.**
-dontwarn java.time.**
