plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val formaTests = providers.gradleProperty("formaTests").orNull != "false"
val nativeEnabled = providers.gradleProperty("ffmpegEnabled").orNull == "true"
val selectedTestBuild = providers.gradleProperty("formaTestBuildType").orElse("debug").get()
require(selectedTestBuild == "debug" || (formaTests && selectedTestBuild == "lab"))
val accelerationTests = providers.gradleProperty("accelerationTests").orNull != "false"
android {
    namespace = "dev.forma.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.forma.transcode"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = selectedTestBuild
    sourceSets["test"].java.setSrcDirs(if (formaTests) listOf(rootProject.file("testing/app/unit")) else emptyList<File>())
    sourceSets["androidTest"].java.setSrcDirs(if (formaTests) listOf(rootProject.file("testing/app/device"), rootProject.file("testing/shared")) else emptyList<File>())
    sourceSets["main"].java.srcDir(if (nativeEnabled) "src/native/kotlin" else "src/missing/kotlin")
    if (formaTests) sourceSets["androidTest"].manifest.srcFile(rootProject.file("testing/shared/AndroidManifest.xml"))
    // External test sources are packaged only in the instrumentation APK, never in main/release.
    if (formaTests && providers.gradleProperty("settingsTests").orNull != "false") {
        sourceSets.getByName("test").java.srcDir(rootProject.file("testing/settings/app"))
        sourceSets.getByName("test").resources.srcDir(rootProject.file("testing/settings/fixtures"))
        sourceSets.getByName("androidTest").java.srcDir(rootProject.file("testing/settings/android"))
        sourceSets.getByName("androidTest").java.srcDir(rootProject.file("testing/settings/core"))
    }
    if (formaTests && accelerationTests) {
        sourceSets["test"].java.srcDir(rootProject.file("testing/acceleration/appTest"))
        sourceSets["androidTest"].java.srcDir(rootProject.file("testing/acceleration/androidTest"))
        if (nativeEnabled) sourceSets["androidTest"].java.srcDir(rootProject.file("testing/acceleration/nativeAndroidTest"))
    }
    buildFeatures { compose = true }
    if (formaTests && providers.gradleProperty("audioTests").orNull != "false") {
        sourceSets.getByName("androidTest").java.srcDir("../testing/android")
    }
    if (formaTests && providers.gradleProperty("imageTests").orNull != "false") {
        sourceSets.getByName("test").java.srcDir("../testing/image/app")
        sourceSets.getByName("test").java.srcDir("../testing/image/shared")
        sourceSets.getByName("androidTest").java.srcDir("../testing/image/android")
        sourceSets.getByName("androidTest").java.srcDir("../testing/image/shared")
    }
    buildTypes {
        if (formaTests) create("lab") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".lab"
            matchingFallbacks += listOf("debug")
        }
        debug { if (formaTests && providers.gradleProperty("formaLab").orNull == "true") applicationIdSuffix = ".lab" }
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
    if (nativeEnabled) implementation("com.arthenica:ffmpeg-kit-next:9.0.0")
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
    if (formaTests) {
        debugImplementation(libs.compose.test.manifest)
        add("labImplementation", libs.compose.test.manifest)
        testImplementation(libs.junit)
        testImplementation("org.json:json:20240303")
        androidTestImplementation(platform(libs.compose.bom))
        androidTestImplementation(libs.compose.test)
        androidTestImplementation(libs.androidx.test)
        androidTestImplementation(libs.androidx.runner)
        // Compose's transitive Espresso 3.5.0 uses InputManager reflection removed on API 36.
        androidTestImplementation(libs.espresso.core)
        if (nativeEnabled) androidTestImplementation("com.arthenica:ffmpeg-kit-next:9.0.0")
    }
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

// Removable lab source sets and native diagnostic APIs belong ONLY to the test APK.
// A UI-only build deliberately has no lab class; the explicit host runner rejects it.
if (nativeEnabledForRelease && providers.gradleProperty("accelerationTests").orNull != "false") {
    android.sourceSets.getByName("androidTest").java.srcDir(rootProject.file("testing/acceleration/android"))
    android.sourceSets.getByName("androidTest").java.srcDir(rootProject.file("testing/acceleration/shared"))
    dependencies { androidTestImplementation("com.arthenica:ffmpeg-kit-next:9.0.0") }
}
