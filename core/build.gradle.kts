plugins { alias(libs.plugins.kotlin.jvm) }
val formaTests = providers.gradleProperty("formaTests").orNull != "false"
kotlin {
    jvmToolchain(17)
    sourceSets.named("test") {
        kotlin.setSrcDirs(if (formaTests) listOf(rootProject.file("testing/core/unit")) else emptyList<File>())
    }
}
dependencies { if (formaTests) testImplementation(libs.junit) }
