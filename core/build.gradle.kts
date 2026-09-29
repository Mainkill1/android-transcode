plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(libs.junit) }

if (providers.gradleProperty("accelerationTests").orNull != "false") {
    kotlin.sourceSets.getByName("test").kotlin.srcDir(rootProject.file("testing/acceleration/core"))
    kotlin.sourceSets.getByName("test").kotlin.srcDir(rootProject.file("testing/acceleration/shared"))
}
