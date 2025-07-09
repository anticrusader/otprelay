// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Apply the Android Application plugin and Kotlin Android plugin, but not Compose
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Remove or comment out the line below as you don't want Compose
    // alias(libs.plugins.kotlin.compose) apply false
}