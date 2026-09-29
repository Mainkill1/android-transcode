package dev.forma.core

object TimelineChecks {
    private val source = Source("content://fixture", "fixture.mp4", 10_000, 640, 480, 1, 1)
    private fun clip(id: String) = TimelineClip(id, source, Trim(1000, 9000), Settings())
    private fun rejected(block: () -> Unit) { check(runCatching(block).exceptionOrNull() is IllegalArgumentException) }
    fun run(): Int {
        var count = 0
        fun test(name: String, body: () -> Unit) { body(); count++; println("PASS $name") }
        test("split preserves source ranges, settings and total duration") {
            val t = EditTimeline(listOf(clip("a")))
            val n = t.apply(TimelineCommand.Split("a", 4000, "b"))
            check(n.clips.map { it.trim } == listOf(Trim(1000, 4000), Trim(4000, 9000)))
            check(n.durationMs == t.durationMs); check(t.clips.size == 1)
        }
        test("move uses final index and preserves every clip") {
            val t = EditTimeline(listOf(clip("a"), clip("b"), clip("c")))
            check(t.apply(TimelineCommand.Move("a", 2)).clips.map { it.id } == listOf("b", "c", "a"))
        }
        test("duplicate gets a new identity and identical source edits") {
            val t = EditTimeline(listOf(clip("a"))).apply(TimelineCommand.Duplicate("a", "b"))
            check(t.clips[1] == t.clips[0].copy(id = "b"))
        }
        test("duplicate identifiers are rejected") { rejected { EditTimeline(listOf(clip("a"), clip("a"))) } }
        test("split cannot create a zero-duration clip") { rejected { EditTimeline(listOf(clip("a"))).apply(TimelineCommand.Split("a", 1000, "b")) } }
        test("unsupported temporal fade splitting is explicit") {
            val a = clip("a").copy(settings = Settings(effects = ClipEffects(fadeInMs = 500)))
            rejected { EditTimeline(listOf(a)).apply(TimelineCommand.Split("a", 4000, "b")) }
        }
        test("removing the last clip creates a valid empty draft") {
            val t = EditTimeline(listOf(clip("a"))).apply(TimelineCommand.Remove("a"))
            check(t.clips.isEmpty()); check(t.durationMs == 0L)
        }
        test("caller list mutation cannot rewrite a snapshot") {
            val input = mutableListOf(clip("a")); val t = EditTimeline(input); input.clear()
            check(t.clips.size == 1)
            check(runCatching { (t.clips as MutableList<TimelineClip>).clear() }.isFailure)
        }
        test("undo and redo restore exact snapshots") {
            val h = TimelineHistory(EditTimeline(listOf(clip("a"))))
            h.execute(TimelineCommand.Duplicate("a", "b")); check(h.current.clips.size == 2)
            check(h.undo()); check(h.current.clips.size == 1); check(h.redo()); check(h.current.clips.size == 2)
        }
        test("editing after undo discards the redo branch") {
            val h = TimelineHistory(EditTimeline(listOf(clip("a"))))
            h.execute(TimelineCommand.Duplicate("a", "b")); h.undo()
            h.execute(TimelineCommand.Duplicate("a", "c")); check(!h.redo())
        }
        test("bounded history evicts only the oldest undo state") {
            val h = TimelineHistory(EditTimeline(listOf(clip("a"))), 2)
            h.execute(TimelineCommand.Duplicate("a", "b")); h.execute(TimelineCommand.Duplicate("a", "c"))
            h.execute(TimelineCommand.Duplicate("a", "d")); check(h.undo()); check(h.undo()); check(!h.undo())
        }
        test("failed edit does not alter history or current project") {
            val h = TimelineHistory(EditTimeline(listOf(clip("a"))))
            rejected { h.execute(TimelineCommand.TrimClip("a", Trim(9000, 8000))) }
            check(h.current.clips == listOf(clip("a"))); check(!h.undo())
        }
        test("effects remain per-source across encoding presets") {
            val a = SourceEdit(source, effects = ClipEffects(speedPercent = 200))
            check(a.snapshot(Planner.preset(Goal.SMALLER, Quality.SMALL)).effects.speedPercent == 200)
            check(SourceEdit(source).snapshot(Settings()).effects.isNeutral)
        }
        return count
    }
}
