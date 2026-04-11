import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

// Keystore desde keystore.properties local (desarrollo) o variables de entorno (CI)
val ksPath = System.getenv("KEYSTORE_PATH")
    ?: run {
        val props = Properties()
        val f = rootProject.file("keystore.properties")
        if (f.exists()) { props.load(f.inputStream()); props.getProperty("storeFile") } else null
    }
val ksPass = System.getenv("KEYSTORE_PASSWORD")
    ?: run {
        val props = Properties()
        val f = rootProject.file("keystore.properties")
        if (f.exists()) { props.load(f.inputStream()); props.getProperty("storePassword") } else null
    }
val ksAlias = System.getenv("KEY_ALIAS")
    ?: run {
        val props = Properties()
        val f = rootProject.file("keystore.properties")
        if (f.exists()) { props.load(f.inputStream()); props.getProperty("keyAlias") } else null
    }
val ksKeyPass = System.getenv("KEY_PASSWORD")
    ?: run {
        val props = Properties()
        val f = rootProject.file("keystore.properties")
        if (f.exists()) { props.load(f.inputStream()); props.getProperty("keyPassword") } else null
    }

// true solo si el keystore existe y está configurado
val hasKeystore = ksPath != null && java.io.File(ksPath).exists()

android {
    namespace  = "com.ayudamayor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ayudamayor.app"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 20
        versionName   = "3.2.32"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile     = java.io.File(ksPath!!)   // ruta absoluta o relativa — File() lo resuelve
                storePassword = ksPass
                keyAlias      = ksAlias
                keyPassword   = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Solo firmar con keystore si existe — si no, el APK queda sin firmar
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Firma debug automática de Android — no necesita keystore externo
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.billing.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
