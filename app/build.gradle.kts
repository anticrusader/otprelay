// Ensure you have the Android Gradle Plugin and Kotlin plugin at the top of the file
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.otprelay" // Make sure this matches your actual package name (e.g., com.example.otprelay)
    compileSdk = 34 // Or your target SDK version

    defaultConfig {
        applicationId = "com.example.otprelay" // Make sure this matches your actual package name
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {

    // Core AndroidX libraries
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0") // Essential for AppCompatActivity and general UI compatibility

    // Material Design Components (fixes MaterialCardView, SwitchMaterial, Button styles, cornerRadius, etc.)
    implementation("com.google.android.material:material:1.12.0")

    // ConstraintLayout (fixes layout_constraintTop_toTopOf, layout_constraintStart_toStartOf, etc.)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OkHttp for network requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // LocalBroadcastManager (for communication between service and activity)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
