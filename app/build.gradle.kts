plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.javier.iconx"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.javier.iconx"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Llave fija incluida en el repo para que cada versión nueva se instale
    // encima de la anterior sin tener que desinstalar.
    signingConfigs {
        create("fija") {
            storeFile = file("iconx.keystore")
            storePassword = "iconxapp"
            keyAlias = "iconx"
            keyPassword = "iconxapp"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fija")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
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
