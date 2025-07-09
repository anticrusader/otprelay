// C:\Users\Sufian\AndroidStudioProjects\OTPRelay\app\build.gradle.kts

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.otprelay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.otprelay"
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

    // --- FIXES START HERE ---

    // Update Java compatibility to a more recent version (e.g., Java 17)
    // This addresses the warnings about deprecated source/target version 8.
    // Ensure your JDK in Android Studio is also set to 17 or higher.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17" // Must match the compileOptions targetCompatibility
    }

    buildFeatures {
        // Only buildConfig is needed for an XML-based project, no 'compose = true'
        buildConfig = true
    }

    // Add this 'packaging' block to handle duplicate files from dependencies.
    // This resolves the "2 files found with path 'META-INF/NOTICE.md'" error.
    packaging {
        resources {
            // Exclude common duplicate files that cause conflicts
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/*.txt" // Generic exclusion for text files in META-INF
            excludes += "/META-INF/*.SF"  // Signature files
            excludes += "/META-INF/*.DSA" // Signature files
            excludes += "/META-INF/*.RSA" // Signature files
        }
    }

    // --- FIXES END HERE ---
}

dependencies {
    // Core AndroidX libraries (these are standard for XML projects)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Material Design Components (for XML UI elements like MaterialCardView, etc.)
    implementation("com.google.android.material:material:1.12.0")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OkHttp for network requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // LocalBroadcastManager (for communication between service and activity)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // JavaMail dependencies for EmailSender.kt - ADDED THESE BACK
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}