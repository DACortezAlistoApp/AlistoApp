plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ph.alisto.customer"
    compileSdk = 36

    defaultConfig {
        applicationId = "ph.alisto.customer"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "4.1.0-api36"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ALISTO_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ALISTO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ALISTO_KEY_ALIAS")
                keyPassword = System.getenv("ALISTO_KEY_PASSWORD") ?: System.getenv("ALISTO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("ALISTO_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(project(":core-kernel"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
