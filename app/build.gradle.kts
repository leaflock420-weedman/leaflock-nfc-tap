plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.leaflock.nfctap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leaflock.nfctap"
        // Tap to Pay on Android requires Android 13+
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0-taptopay"
        buildConfigField(
            "String",
            "POS_API_BASE",
            "\"https://leaflock-paypal-pos.onrender.com\""
        )
        // true = Stripe simulated reader (no real card charge) for testing
        buildConfigField("boolean", "STRIPE_SIMULATED", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Set simulated false for production builds after Stripe is live
            buildConfigField("boolean", "STRIPE_SIMULATED", "false")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Stripe Terminal — phone SoftPOS / Tap to Pay
    implementation("com.stripe:stripeterminal-taptopay:4.7.3")
    implementation("com.stripe:stripeterminal-core:4.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
