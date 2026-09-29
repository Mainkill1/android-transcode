pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Optional source rebuild of Compose's graphics-path native dependency.
        // See docs/device-validation.md; never modify cached or packaged ELF binaries.
        providers.gradleProperty("graphicsPathRepo").orNull?.let { graphicsRepo ->
            require(file(graphicsRepo).isDirectory) { "Graphics local Maven repository does not exist: $graphicsRepo" }
            exclusiveContent {
                forRepository { maven { url = uri(graphicsRepo) } }
                filter { includeModule("androidx.graphics", "graphics-path") }
            }
        }
        if (providers.gradleProperty("ffmpegEnabled").orNull == "true") {
            val nativeRepo = providers.gradleProperty("ffmpegRepo").orNull
                ?: error("Set -PffmpegRepo=/absolute/path/to/bundle-android-aar-24-maven. See docs/ffmpeg.md.")
            require(file(nativeRepo).isDirectory) { "FFmpeg local Maven repository does not exist: $nativeRepo" }
            exclusiveContent {
                forRepository { maven { url = uri(nativeRepo) } }
                filter { includeModule("com.arthenica", "ffmpeg-kit-next") }
            }
        }
    }
}
rootProject.name = "Forma"
include(":app", ":core", ":engine-ffmpeg")
