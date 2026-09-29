package dev.forma.core

import java.util.Collections
import java.util.ArrayDeque

/** Ordered-clip editing foundation. This is not a multitrack compositor or an export queue. */
data class TimelineClip(val id: String, val source: Source, val trim: Trim = Trim(), val settings: Settings = Settings())

sealed interface TimelineCommand {
    data class Append(val clip: TimelineClip) : TimelineCommand
    data class Remove(val id: String) : TimelineCommand
    data class Move(val id: String, val finalIndex: Int) : TimelineCommand
    data class Duplicate(val id: String, val newId: String) : TimelineCommand
    data class Split(val id: String, val sourceTimeMs: Long, val rightId: String) : TimelineCommand
    data class TrimClip(val id: String, val trim: Trim) : TimelineCommand
    data class UpdateSettings(val id: String, val settings: Settings) : TimelineCommand
}

class EditTimeline(clips: List<TimelineClip> = emptyList()) {
    val clips: List<TimelineClip> = Collections.unmodifiableList(ArrayList(clips))
    val durationMs: Long
    init {
        require(clips.size <= 200) { "A timeline supports at most 200 clips." }
        require(clips.all { it.id.isNotBlank() && it.id.length <= 128 }) { "Clip IDs must be nonblank and at most 128 characters." }
        require(clips.map { it.id }.distinct().size == clips.size) { "Clip IDs must be unique." }
        var total = 0L
        for (clip in clips) {
            // Saved documents retain unsupported future audio graphs; runtime validation owns executability.
            require(clip.source.durationMs>0 && clip.trim.startMs>=0 && (clip.trim.endMs ?: clip.source.durationMs)<=clip.source.durationMs && (clip.trim.endMs ?: clip.source.durationMs)>clip.trim.startMs) { "${clip.id}: Invalid source range." }
            val structural=clip.settings.copy(video=VideoEncoder.X264,container=if(clip.source.videoTracks>0)Container.MP4 else Container.M4A)
            val problems=EditPipeline.validate(clip.source,clip.trim,structural,null)
            require(problems.isEmpty()) { "${clip.id}: ${problems.joinToString("; ")}" }
            val duration=runCatching { Planner.outputDuration(clip.source,clip.trim,structural) }
                .getOrElse { EditPipeline.duration(clip.source,clip.trim,clip.settings.effects) }
            require(total <= Long.MAX_VALUE - duration) { "Timeline duration is too large." }
            total += duration
        }
        durationMs = total
    }

    override fun equals(other: Any?): Boolean = other is EditTimeline && clips == other.clips
    override fun hashCode(): Int = clips.hashCode()

    fun apply(command: TimelineCommand): EditTimeline {
        val next = clips.toMutableList()
        fun index(id: String) = next.indexOfFirst { it.id == id }.also { require(it >= 0) { "Unknown clip: $id" } }
        when (command) {
            is TimelineCommand.Append -> next.add(command.clip)
            is TimelineCommand.Remove -> next.removeAt(index(command.id))
            is TimelineCommand.Move -> {
                require(command.finalIndex in next.indices) { "Move destination is outside the timeline." }
                val clip = next.removeAt(index(command.id)); next.add(command.finalIndex, clip)
            }
            is TimelineCommand.Duplicate -> {
                val i = index(command.id); next.add(i + 1, next[i].copy(id = command.newId))
            }
            is TimelineCommand.Split -> {
                val i = index(command.id); val clip = next[i]
                val end = clip.trim.endMs ?: clip.source.durationMs
                require(command.sourceTimeMs > clip.trim.startMs && command.sourceTimeMs < end) { "Split must be inside the kept source range." }
                val e = clip.settings.effects
                require(e.fadeInMs == 0L && e.fadeOutMs == 0L && e.audioFadeInMs == 0L && e.audioFadeOutMs == 0L) {
                    "Splitting a clip with fades needs temporal effect remapping. Remove fades before splitting in this foundation."
                }
                next[i] = clip.copy(trim = Trim(clip.trim.startMs, command.sourceTimeMs))
                next.add(i + 1, clip.copy(id = command.rightId, trim = Trim(command.sourceTimeMs, end)))
            }
            is TimelineCommand.TrimClip -> { val i = index(command.id); next[i] = next[i].copy(trim = command.trim) }
            is TimelineCommand.UpdateSettings -> { val i = index(command.id); next[i] = next[i].copy(settings = command.settings) }
        }
        return EditTimeline(next)
    }
}

/** UI-owner confined. Commit one command on drag-end, not one command per pointer sample. */
class TimelineHistory(initial: EditTimeline = EditTimeline(), private val capacity: Int = 100) {
    init { require(capacity in 1..1000) { "History capacity must be 1–1000." } }
    var current: EditTimeline = initial
        private set
    private val past = ArrayDeque<EditTimeline>()
    private val future = ArrayDeque<EditTimeline>()
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    fun execute(command: TimelineCommand) {
        val next = current.apply(command) // Validate before touching history.
        if (next.clips == current.clips) return
        past.addLast(current)
        while (past.size > capacity) past.removeFirst()
        future.clear(); current = next
    }
    fun undo(): Boolean {
        if (!canUndo) return false
        future.addLast(current); current = past.removeLast(); return true
    }
    fun redo(): Boolean {
        if (!canRedo) return false
        past.addLast(current); current = future.removeLast(); return true
    }
}
