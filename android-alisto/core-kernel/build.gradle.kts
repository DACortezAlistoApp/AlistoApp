plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ph.alisto.kernel"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "SUPABASE_URL", "\"https://fmjjyhmtdsvhufkgyjpr.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZtamp5aG10ZHN2aHVma2d5anByIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ5MTE5ODIsImV4cCI6MjExMDQ4Nzk4Mn0.xevkmMBAnIUcNKkp34PTnmlk-385SUwpwhbV0Kaygzs\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
