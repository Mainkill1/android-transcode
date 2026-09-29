plugins { alias(libs.plugins.kotlin.jvm) }
val formaTests = providers.gradleProperty("formaTests").orNull != "false"
kotlin {
    jvmToolchain(17)
    sourceSets.named("test") { kotlin.setSrcDirs(if(formaTests) listOf(rootProject.file("testing/core/unit")) else emptyList<File>()) }
    if (formaTests && providers.gradleProperty("settingsTests").orNull != "false") {
        sourceSets.named("test") {
            kotlin.srcDir(rootProject.file("testing/settings/core"))
            kotlin.srcDir(rootProject.file("testing/settings/junit"))
        }
    }
}
dependencies { if(formaTests) testImplementation(libs.junit) }
if (formaTests && providers.gradleProperty("imageTests").orNull != "false") {
    sourceSets.test { kotlin.srcDir("../testing/image/core") }
}
