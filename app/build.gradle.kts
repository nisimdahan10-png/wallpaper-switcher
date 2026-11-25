plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wallpaperswitcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wallpaperswitcher"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // מפתח חתימה קבוע – כך כל גרסה חדשה מתעדכנת מעל הקודמת בלי למחוק
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
