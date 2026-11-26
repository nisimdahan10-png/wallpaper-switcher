plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// מפתח העלאה לגוגל פליי – מגיע מ-GitHub Secrets בזמן הבנייה (לא נשמר בקוד)
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE")
val releasePassword: String? = System.getenv("SIGNING_PASSWORD")

android {
    namespace = "com.nisimdahan.wallpaperswitcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nisimdahan.wallpaperswitcher"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "3.3"
    }

    signingConfigs {
        // מפתח קבוע לגרסאות הבדיקה – כך כל גרסה מתעדכנת מעל הקודמת
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null && releasePassword != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = "upload"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
