plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.forma.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.forma.transcode"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    if (providers.gradleProperty("audioTests").orNull != "false") {
        sourceSets.getByName("androidTest").java.srcDir("../testing/android")
    }
    if (providers.gradleProperty("imageTests").orNull != "false") {
        sourceSets.getByName("test").java.srcDir("../testing/image/app")
        sourceSets.getByName("test").java.srcDir("../testing/image/shared")
        sourceSets.getByName("androidTest").java.srcDir("../testing/image/android")
        sourceSets.getByName("androidTest").java.srcDir("../testing/image/shared")
    }
    buildTypes {
