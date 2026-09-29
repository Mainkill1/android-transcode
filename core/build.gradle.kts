plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    jvmToolchain(17)
    if (providers.gradleProperty("settingsTests").orNull != "false") {
        sourceSets.named("test") {
            kotlin.srcDir(rootProject.file("testing/settings/core"))
            kotlin.srcDir(rootProject.file("testing/settings/junit"))
        }
    }
}
dependencies { testImplementation(libs.junit) }
if (providers.gradleProperty("imageTests").orNull != "false") {
    sourceSets.test { kotlin.srcDir("../testing/image/core") }
}
