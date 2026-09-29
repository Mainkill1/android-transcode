plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
val nativeEnabled = providers.gradleProperty("ffmpegEnabled").orNull == "true"
android {
    namespace = "dev.forma.ffmpeg"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDir(if (nativeEnabled) "src/native/kotlin" else "src/missing/kotlin")
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core"))
    implementation(libs.coroutines)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    if (nativeEnabled) implementation("com.arthenica:ffmpeg-kit-next:9.0.0")
}
