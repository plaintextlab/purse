# JGit loads translation bundles and some services reflectively.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.jsch.**
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.invoke.**

# kotlinx.serialization (sync format lives in :core)
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.purse.core.**$$serializer { *; }
-keepclassmembers class app.purse.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
