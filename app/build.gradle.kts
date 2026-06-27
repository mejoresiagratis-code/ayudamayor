import java.util.Properties
import java.io.File
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

fun loadProp(key: String): String? {
    val env = System.getenv(
        when(key) {
            "storeFile"     -> "KEYSTORE_PATH"
            "storePassword" -> "KEYSTORE_PASSWORD"
            "keyAlias"      -> "KEY_ALIAS"
            "keyPassword"   -> "KEY_PASSWORD"
            else            -> ""
        }
    )
    if (!env.isNullOrBlank()) return env
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return null
    val p = Properties()
    p.load(f.inputStream())
    return p.getProperty(key)
}

val ksPath    = loadProp("storeFile")
val ksPass    = loadProp("storePassword")
val ksAlias   = loadProp("keyAlias")
val ksKeyPass = loadProp("keyPassword")
val ksFile    = if (ksPath != null) File(ksPath) else null
val hasKeystore = ksFile?.exists() == true

android {
    namespace  = "com.ayudamayor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ayudamayor.app"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 39
        versionName   = "3.2.52"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile     = ksFile
                storePassword = ksPass
                keyAlias      = ksAlias
                keyPassword   = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Firma debug automática — no necesita keystore externo
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.firebase.crashlytics)
    implementation(libs.okhttp)
}
