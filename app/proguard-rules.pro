# AyudaMayor ProGuard Rules

# Mantener el NativeBridge — lo usa JavaScript por reflexión
-keep class com.ayudamayor.app.bridge.NativeBridge { *; }
-keepclassmembers class com.ayudamayor.app.bridge.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Firebase
-keep class com.google.firebase.** { *; }

# Google Play Billing
-keep class com.android.billingclient.** { *; }

# Mantener clases de la app
-keep class com.ayudamayor.app.** { *; }
