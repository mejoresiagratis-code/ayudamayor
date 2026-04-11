import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

val keystoreProperties = Properties()
val keystoreFile = rootProject.file("keystore.properties")
if (keystoreFile.exists()) keystoreProperties.load(FileInputStream(keystoreFile))

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
        create("release") {
            val ksFile = System.getenv("KEYSTORE_PATH")
                ?: keystoreProperties.getProperty("storeFile")
            val ksPass = System.getenv("KEYSTORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
            val kAlias = System.getenv("KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
            val kPass  = System.getenv("KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")
            if (ksFile != null) {
                // Soporta ruta absoluta (CI) y relativa a raíz del proyecto (local)
                storeFile     = if (java.io.File(ksFile).isAbsolute)
                                    java.io.File(ksFile)
                                else
                                    rootProject.file(ksFile)
                storePassword = ksPass
                keyAlias      = kAlias
                keyPassword   = kPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig   = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
    // Firebase BOM — gestiona versiones automáticamente
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
