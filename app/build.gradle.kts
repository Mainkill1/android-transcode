plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val accelerationTests = providers.gradleProperty("accelerationTests").orNull != "false"
val nativeEnabled = providers.gradleProperty("ffmpegEnabled").orNull == "true"
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
    if (accelerationTests) {
        sourceSets["test"].java.srcDir(rootProject.file("testing/acceleration/appTest"))
        sourceSets["androidTest"].java.srcDir(rootProject.file("testing/acceleration/androidTest"))
        if (nativeEnabled) sourceSets["androidTest"].java.srcDir(rootProject.file("testing/acceleration/nativeAndroidTest"))
    }
    buildFeatures { compose = true }
    buildTypes {
        getByName("debug") {
            if (providers.gradleProperty("formaLab").orNull == "true") applicationIdSuffix = ".lab.codec"
        }
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
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    if (nativeEnabled) androidTestImplementation("com.arthenica:ffmpeg-kit-next:9.0.0")
}

// UI-only debug builds remain useful, but cannot be promoted to a release by accident.
// This checks intent; verify_android_native.py must still validate the produced AAR/APK.
val nativeEnabledForRelease = nativeEnabled
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(nativeEnabledForRelease) {
            "Forma releases require FFmpeg for Android. Set ffmpegEnabled=true and a source-built ffmpegRepo; then verify the actual native payload."
        }
    }
}
