plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(libs.junit) }
if (providers.gradleProperty("imageTests").orNull != "false") {
    sourceSets.test { kotlin.srcDir("../testing/image/core") }
}
