# Cómo obtener el APK desde el móvil usando GitHub Actions

## Lo que necesitas
- Cuenta de GitHub (gratis en github.com)
- App GitHub en el móvil (Play Store, gratis)
- Este proyecto descomprimido en el móvil

---

## PASO 1 — Crear el repositorio en GitHub

1. Abre **github.com** en el navegador del móvil
2. Pulsa el **+** arriba a la derecha → **New repository**
3. Rellena:
   - Repository name: `AyudaMayorAndroid`
   - Visibility: **Private** (tu código es privado)
   - NO marques ninguna casilla de inicialización
4. Pulsa **Create repository**
5. GitHub te muestra una página con instrucciones — déjala abierta

---

## PASO 2 — Subir el proyecto desde el móvil

### Opción A — Desde la app GitHub (más fácil)
1. Instala la app **GitHub** desde Play Store
2. Inicia sesión
3. En la app: el repositorio recién creado aparecerá vacío
4. Pulsa **Upload files**
5. Selecciona TODOS los archivos del proyecto descomprimido
6. Commit message: `Initial commit`
7. Pulsa **Commit changes**

### Opción B — Desde el navegador (GitHub.com)
1. En la página del repositorio vacío pulsa **uploading an existing file**
2. Arrastra o selecciona los archivos
3. Sube los archivos en este orden (GitHub no permite subcarpetas en subida web,
   así que sube el ZIP completo):
   - Primero sube el archivo ZIP `AyudaMayorAndroid_v3.zip`
   - Luego ve al README para ver las instrucciones de descompresión

### Opción C — Desde terminal en el móvil (Termux)
Si tienes Termux instalado:
```bash
pkg install git
cd /ruta/a/AyudaMayorAndroid
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/TU_USUARIO/AyudaMayorAndroid.git
git push -u origin main
```

---

## PASO 3 — GitHub compila el APK automáticamente

En cuanto subes el código, GitHub Actions arranca solo.

1. Ve a tu repositorio en github.com
2. Pulsa la pestaña **Actions** (arriba, entre "Pull requests" y "Projects")
3. Verás un workflow llamado **"Build APK"** ejecutándose
4. El círculo amarillo = compilando. Tarda ~3-5 minutos
5. Cuando se pone verde ✅ = APK listo

---

## PASO 4 — Descargar el APK al móvil

1. Pulsa sobre el workflow completado (el que tiene ✅)
2. Baja hasta la sección **Artifacts**
3. Pulsa **AyudaMayor-debug**
4. Se descarga un ZIP con el APK dentro
5. Abre el ZIP → extrae `app-debug.apk`
6. Pulsa el APK para instalar

Si Android dice "instalación bloqueada":
- Ve a **Ajustes → Seguridad → Instalar apps de origen desconocido**
- Actívalo para el navegador o gestor de archivos que uses
- Vuelve a pulsar el APK

---

## PASO 5 — Para actualizaciones futuras

Cada vez que quieras una nueva versión:
1. Modifica el código en GitHub (desde la web o la app)
2. GitHub compila el nuevo APK automáticamente
3. Descárgalo desde Actions → el workflow más reciente → Artifacts

---

## Si el workflow falla (círculo rojo ❌)

1. Pulsa sobre el workflow fallido
2. Pulsa sobre el job **build**
3. Expande el paso que falló (tiene ❌ en rojo)
4. Copia el error y compártelo — lo resuelvo

---

## Nota sobre google-services.json

El archivo `app/google-services.json` incluido es un placeholder.
La app compila y funciona sin él (llamadas, SOS, IA, todo funciona).
Solo las notificaciones push nativas (FCM) requieren el archivo real.
Cuando quieras activarlas:
1. Ve a console.firebase.google.com
2. Crea proyecto → Añade app Android → package: com.ayudamayor.app
3. Descarga el google-services.json real
4. Súbelo al repositorio reemplazando el placeholder
5. GitHub recompila automáticamente
