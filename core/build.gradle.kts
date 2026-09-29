plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    jvmToolchain(17)
    sourceSets.named("test") {
        kotlin.srcDir(rootProject.file("testing/settings/core"))
        kotlin.srcDir(rootProject.file("testing/settings/junit"))
    }
}
dependencies { testImplementation(libs.junit) }
