package dev.forma.testing

import dev.forma.core.*

/** Shared by the physical-device command and independent desktop filter qualification. */
data class EditorCase(val name: String, val trim: Trim, val settings: Settings, val width: Int? = null, val height: Int? = null)
fun editorSmokeCases(): List<EditorCase> {
    val base = Settings(maxHeight = 0, fps = 30)
    return listOf(
        EditorCase("neutral", Trim(1000, 5000), base, 640, 360),
        EditorCase("speed", Trim(1000, 5000), base.copy(effects = ClipEffects(speedPercent = 200)), 640, 360),
        EditorCase("crop-color", Trim(1000, 5000), base.copy(effects = ClipEffects(crop = CropRect(2, 4, 320, 180),
            rotation = QuarterTurn.CLOCKWISE, flipHorizontal = true, brightnessPercent = 10, saturationPercent = 80, blurSigma = 1, sharpen = true)), 180, 320),
        EditorCase("fades", Trim(1000, 5000), base.copy(effects = ClipEffects(fadeInMs = 250, fadeOutMs = 500,
            volumePercent = 75, audioFadeInMs = 250, audioFadeOutMs = 500, normalizeAudio = true)), 640, 360),
        EditorCase("audio-only", Trim(1000, 3000), base.copy(container = Container.M4A, effects = ClipEffects(speedPercent = 50)))
    )
}
