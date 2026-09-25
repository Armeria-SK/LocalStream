plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localstream.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.localstream.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "1.0.0"
    }

    // Release key (CN=Armeria), supplied by CI from repository secrets. A fresh CI runner
    // generates a new debug key every run, so a stable key is what lets each APK install
    // over the previous one. Without these variables (local builds) release falls back to
    // the debug key and still produces an installable APK.
    val releaseKeystore = System.getenv("LOCALSTREAM_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "pkcs12"
                storePassword = System.getenv("LOCALSTREAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LOCALSTREAM_KEY_ALIAS")
                // PKCS12 keystores protect the key with the store password.
                keyPassword = System.getenv("LOCALSTREAM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    testImplementation("junit:junit:4.13.2")
}
