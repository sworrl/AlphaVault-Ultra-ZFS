# ProGuard / R8 Obfuscation and Minification Rules for AlphaSteg Pro & AlphaVault

# Material Components & Layout Views Preservation
-keep class com.google.android.material.** { *; }
-keep class androidx.appcompat.** { *; }
-dontwarn com.google.android.material.**

# Custom UI Views (StegoVisualizerView) & Activities
-keep class com.alphasteg.pro.ui.** { *; }
-keep class com.alphasteg.pro.engine.** { *; }
-keep class com.alphasteg.pro.security.** { *; }
-keep class com.alphasteg.pro.VaultService { *; }
-keep class com.alphasteg.pro.MainActivity { *; }
-keep class com.alphasteg.pro.LockScreenActivity { *; }

# Preserve View Constructors used by Layout Inflation
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Android Biometrics
-keep class androidx.biometric.** { *; }

# Cryptography & KeyStore
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Native Library Loading
-keepclasseswithmembernames class * {
    native <methods>;
}

# Obfuscation Optimizations
-allowaccessmodification
-dontusemixedcaseclassnames
-verbose
