plugins {
    id("com.android.application")
}

val malKeystoreFile = System.getenv("MAL_KEYSTORE_FILE")
val malKeystorePassword = System.getenv("MAL_KEYSTORE_PASSWORD")
val malKeyAlias = System.getenv("MAL_KEY_ALIAS")
val malKeyPassword = System.getenv("MAL_KEY_PASSWORD")
val malReleaseSigningReady = listOf(
    malKeystoreFile,
    malKeystorePassword,
    malKeyAlias,
    malKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.shixu.minibrowser"
    compileSdk = 36

    defaultConfig {
        // IMPORTANT: Keep this applicationId forever if you want Android updates to retain app/WebView data.
        applicationId = "com.shixu.minibrowser"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "2.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Public GitHub repository used by the in-app updater.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"hotship/MAL-Browser\"")
    }

    if (malReleaseSigningReady) {
        signingConfigs {
            create("malRelease") {
                storeFile = file(malKeystoreFile!!)
                storePassword = malKeystorePassword
                keyAlias = malKeyAlias
                keyPassword = malKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (malReleaseSigningReady) {
                signingConfig = signingConfigs.getByName("malRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.webkit:webkit:1.17.1")
}
