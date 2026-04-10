# AyudaMayor Android — Instrucciones de compilación

## Requisitos
- Android Studio Hedgehog (2023.1.1) o superior
- JDK 17 (incluido en Android Studio)
- Conexión a internet (para descargar dependencias Gradle)

---

## Pasos para compilar y obtener el APK

### 1. Abrir el proyecto
- Abre Android Studio
- File → Open → selecciona la carpeta `AyudaMayorAndroid`
- Espera a que Gradle sincronice (puede tardar 2-3 min la primera vez)

### 2. (Opcional pero recomendado) Configurar Firebase
Sin este paso la app funciona al 100% excepto las notificaciones push nativas.
- Ve a https://console.firebase.google.com
- Crea proyecto → Añade app Android → package: `com.ayudamayor.app`
- Descarga `google-services.json` y reemplaza el que hay en `app/google-services.json`

### 3. Compilar el APK de debug (para testear)
Opción A — desde Android Studio:
- Build → Build Bundle(s) / APK(s) → Build APK(s)
- El APK estará en: `app/build/outputs/apk/debug/app-debug.apk`

Opción B — desde terminal:
```bash
cd AyudaMayorAndroid
./gradlew assembleDebug
# APK en: app/build/outputs/apk/debug/app-debug.apk
```

### 4. Instalar en el dispositivo
Opción A — cable USB:
- Activa "Depuración USB" en el teléfono (Ajustes → Opciones de desarrollador)
- Run → Run 'app' en Android Studio

Opción B — sin cable (instalar APK directamente):
- Copia `app-debug.apk` al teléfono
- En el teléfono: Ajustes → Seguridad → Instalar apps de origen desconocido → activar
- Abre el APK desde el gestor de archivos

---

## Qué hace la app

La app es un WebView que carga directamente:
- **Vista Mayor**: https://mejoresiagratis.com/ayudamayor/views/mayor/index.php
- **Vista Familiar**: https://mejoresiagratis.com/ayudamayor/views/familiar/index.php

El servidor PHP, la IA y la base de datos son exactamente los mismos que en la versión web.
La app añade capacidades nativas accesibles desde JS via `window.NativeBridge`:

| Método JS | Función nativa |
|-----------|----------------|
| `NativeBridge.call("Pablo")` | Llamada telefónica real |
| `NativeBridge.sendSms("Pablo", "texto")` | SMS nativo |
| `NativeBridge.setTorch(true/false)` | Linterna |
| `NativeBridge.setVolume(75)` | Volumen del sistema |
| `NativeBridge.setBrightness(80)` | Brillo de pantalla |
| `NativeBridge.openApp("whatsapp")` | Abrir apps instaladas |
| `NativeBridge.getTier()` | Tier actual del usuario |

---

## Para publicar en Play Store (cuando estés listo)

1. Generar keystore de firma:
```bash
keytool -genkey -v -keystore ayudamayor.jks -keyalg RSA -keysize 2048 -validity 10000 -alias ayudamayor
```

2. Añadir a `app/build.gradle.kts`:
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("ayudamayor.jks")
        storePassword = "TU_PASSWORD"
        keyAlias = "ayudamayor"
        keyPassword = "TU_PASSWORD"
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

3. Compilar release:
```bash
./gradlew bundleRelease
# AAB en: app/build/outputs/bundle/release/app-release.aab
```

---

## Estructura del proyecto

```
app/src/main/
  java/com/ayudamayor/app/
    MainActivity.kt          ← WebView vista Mayor
    FamilyPanelActivity.kt   ← WebView vista Familiar
    AyudaMayorApp.kt         ← Application (init AdMob)
    bridge/
      NativeBridge.kt        ← Bridge JS↔Kotlin (llamadas, SMS, linterna...)
    billing/
      BillingManager.kt      ← Google Play Billing
    permissions/
      PermissionManager.kt   ← Gestión de permisos
    fcm/
      AyudaMayorFCMService.kt ← Notificaciones push nativas
  res/
    layout/activity_main.xml  ← Solo un WebView a pantalla completa
    drawable/                  ← Iconos vectoriales
    mipmap-*/                  ← Iconos launcher
    xml/network_security_config.xml
```
