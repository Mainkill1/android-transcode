package dev.forma.core

import java.util.ArrayDeque

/** Editable document. Source references and edits are saved, never copies of the media. */
data class MovieProject(
    val name: String = "My movie",
    val sequence: SequenceSpec = SequenceSpec(EditTimeline()),
    val settings: Settings = Settings(),
    val targetBytes: Long? = 10_000_000
) {
    init {
        require(name.isNotBlank() && name.length <= 100 && '\u0000' !in name) { "Give the movie a name of 1–100 characters." }
        require(settings.effects.isNeutral) { "Apply movie effects to its individual clips." }
        targetBytes?.let(UploadFit::validateTarget)
    }
    fun edit(command: TimelineCommand): MovieProject = copy(sequence = sequence.copy(timeline = sequence.timeline.apply(command)))
    fun toJob(id: String): JobSpec {
        val first = sequence.timeline.clips.firstOrNull() ?: error("Add a clip before exporting.")
        val job = JobSpec(id,first.source.copy(name="$name.${settings.container.extension}"),Trim(),settings,targetBytes=targetBytes,sequence=sequence)
        val problems = JobPlans.validate(job)
        require(problems.isEmpty()) { problems.joinToString("\n") }
        return job
    }
    /** A real queued render, not live playback and not a change to the user's final export settings. */
    fun previewJob(id: String): JobSpec {
        val c = sequence.canvas
        val ratio = minOf(1.0, 480.0 / maxOf(c.width, c.height))
        val previewCanvas = c.copy(width = maxOf(4, (c.width * ratio).toInt() / 2 * 2),
            height = maxOf(4, (c.height * ratio).toInt() / 2 * 2), fps = minOf(30, c.fps))
        val s = settings.copy(video = VideoEncoder.X264, rateControl = RateControl.QUALITY, crf = 28,
            container = if (settings.container.audioOnly) Container.M4A else Container.MP4,
            audio = if (settings.audio == AudioEncoder.NONE) AudioEncoder.NONE else AudioEncoder.AAC)
        return copy(name = "$name preview".take(100), sequence = sequence.copy(canvas = previewCanvas), settings = s, targetBytes = null).toJob(id)
    }
}

/** UI-owner confined. A committed trim drag or canvas change is one reversible document operation. */
class ProjectHistory(initial: MovieProject = MovieProject(), private val capacity: Int = 100) {
    init { require(capacity in 1..1000) }
    var current: MovieProject = initial
        private set
    private val past = ArrayDeque<MovieProject>()
    private val future = ArrayDeque<MovieProject>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun replace(next: MovieProject) {
        if (next == current) return
        past.addLast(current)
        while (past.size > capacity) past.removeFirst()
        current = next; future.clear()
    }
    fun undo(): Boolean { if (!canUndo) return false; future.addLast(current); current = past.removeLast(); return true }
    fun redo(): Boolean { if (!canRedo) return false; past.addLast(current); current = future.removeLast(); return true }
}
