package dev.forma.testing

import dev.forma.core.*
import java.io.File

fun main(args: Array<String>) {
    val input = File(args[0])
    val root = File(args[1])
    val source = Source(input.path, "fixture", 6000, 640, 360, 1, 1)
    val cases = editorSmokeCases() + listOf(
        EditorCase("offset-trim", Trim(0, 5000), Settings(maxHeight = 0, fps = 30, effects = ClipEffects(volumePercent = 75)), 640, 360),
        EditorCase("offset-speed", Trim(0, 5000), Settings(maxHeight = 0, fps = 30, effects = ClipEffects(speedPercent = 200)), 640, 360),
        EditorCase("fast-source-fps", Trim(1000, 5000), Settings(maxHeight = 0, effects = ClipEffects(speedPercent = 200)), 640, 360),
        EditorCase("slow-source-fps", Trim(1000, 3000), Settings(maxHeight = 0, effects = ClipEffects(speedPercent = 25)), 640, 360)
    )
    File(root, "cases.tsv").printWriter().use { writer ->
        for (case in cases) {
            val output = File(root, "${case.name}.${case.settings.container.extension}")
            val argv = Planner.arguments(source, case.trim, case.settings, input.path, output.path)
            File(root, "${case.name}.argv").writeText(argv.joinToString("\u0000"))
            writer.println(listOf(case.name, output.name, Planner.outputDuration(source, case.trim, case.settings), case.width ?: 0, case.height ?: 0).joinToString("\t"))
        }
    }
}
