import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

// Cargar keystore desde propiedades locales o variables de entorno (CI)
val keystoreProperties = Properties()
val keystoreFile = rootProject.file("keystore.properties")
if (keystoreFile.exists()) {
    keystoreProperties.load(FileInputStream(keystoreFile))
}

android {
    namespace = "com.ayudamayor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ayudamayor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "3.2.32"
    }

    signingConfigs {
        create("release") {
            // GitHub Actions usa variables de entorno; local usa keystore.properties
            val ksFile = System.getenv("KEYSTORE_PATH")
                ?: keystoreProperties.getProperty("storeFile")
            val ksPass = System.getenv("KEYSTORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
            val kAlias = System.getenv("KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
            val kPass  = System.getenv("KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")

            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = kAlias
                keyPassword = kPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release") // misma firma en debug
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.billing.ktx)
    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
