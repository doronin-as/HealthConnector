plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableSigningStoreFile = System.getenv("HC_SIGNING_STORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }

android {
    namespace = "ru.doronin.healthconnector"
    compileSdk = 36

    signingConfigs {
        if (stableSigningStoreFile?.exists() == true) {
            create("stableRelease") {
                storeFile = stableSigningStoreFile
                storePassword = System.getenv("HC_SIGNING_STORE_PASSWORD")
                    ?.takeIf { it.isNotBlank() } ?: "android"
                keyAlias = System.getenv("HC_SIGNING_KEY_ALIAS")
                    ?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
                keyPassword = System.getenv("HC_SIGNING_KEY_PASSWORD")
                    ?.takeIf { it.isNotBlank() } ?: "android"
            }
        }
    }

    defaultConfig {
        applicationId = "ru.doronin.healthconnector.stable"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.6.8"
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            signingConfigs.findByName("stableRelease")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
