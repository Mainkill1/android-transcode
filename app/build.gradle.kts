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
    // External test sources are packaged only in the instrumentation APK, never in main/release.
    if (providers.gradleProperty("settingsTests").orNull != "false") {
        sourceSets.getByName("test").java.srcDir(rootProject.file("testing/settings/app"))
        sourceSets.getByName("test").resources.srcDir(rootProject.file("testing/settings/fixtures"))
        sourceSets.getByName("androidTest").java.srcDir(rootProject.file("testing/settings/android"))
        sourceSets.getByName("androidTest").java.srcDir(rootProject.file("testing/settings/core"))
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
        debug { if (providers.gradleProperty("formaLab").orNull == "true") applicationIdSuffix = ".lab" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(project(":engine-ffmpeg"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.coroutines)
    implementation(libs.media3.exoplayer)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.androidx.runner)
    // Compose's transitive Espresso 3.5.0 uses InputManager reflection removed on API 36.
    androidTestImplementation(libs.espresso.core)
}

// UI-only debug builds remain useful, but cannot be promoted to a release by accident.
// This checks intent; verify_android_native.py must still validate the produced AAR/APK.
val nativeEnabledForRelease = providers.gradleProperty("ffmpegEnabled").orNull == "true"
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(nativeEnabledForRelease) {
            "Forma releases require FFmpeg for Android. Set ffmpegEnabled=true and a source-built ffmpegRepo; then verify the actual native payload."
        }
    }
}
