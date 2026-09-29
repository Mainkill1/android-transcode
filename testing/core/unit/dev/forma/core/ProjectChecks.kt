package dev.forma.core

object ProjectChecks {
    private val source = Source("content://a", "a.mp4", 8000, 640, 360, 1, 1)
    private fun clip(id: String = "a") = TimelineClip(id, source)
    private fun fails(block: () -> Unit) = check(runCatching(block).isFailure)
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "empty project is editable but not exportable" to { val p = MovieProject(); fails { p.toJob("x") } },
        "timeline equality describes content" to { check(EditTimeline(listOf(clip())) == EditTimeline(listOf(clip()))) },
        "queued movie is an independent snapshot" to { val p = MovieProject(sequence = SequenceSpec(EditTimeline(listOf(clip())))); val job = p.toJob("x"); val next = p.edit(TimelineCommand.Duplicate("a", "b")); check(job.sequence!!.timeline.clips.size == 1 && next.sequence.timeline.clips.size == 2) },
        "default upload cap is decimal ten MB" to { check(MovieProject().targetBytes == 10_000_000L) },
        "undo restores canvas size limit and clips together" to { val h = ProjectHistory(); val p = MovieProject(sequence = SequenceSpec(EditTimeline(listOf(clip())), CanvasSpec(720,1280)), targetBytes = 25_000_000); h.replace(p); check(h.undo() && h.current == MovieProject()); check(h.redo() && h.current == p) },
        "new edit invalidates redo" to { val h = ProjectHistory(); h.replace(h.current.edit(TimelineCommand.Append(clip()))); h.undo(); h.replace(h.current.copy(name="Other")); check(!h.canRedo) },
        "identical project creates no undo entry" to { val h = ProjectHistory(); h.replace(MovieProject()); check(!h.canUndo) },
        "history capacity is bounded" to { val h = ProjectHistory(capacity=2); repeat(3) { h.replace(h.current.copy(name="Movie $it")) }; check(h.undo() && h.undo() && !h.undo()) },
        "preview is a low resolution real render without a size limit" to { val p = MovieProject(sequence=SequenceSpec(EditTimeline(listOf(clip())),CanvasSpec(1080,1920,60))); val j=p.previewJob("x"); val canvas=requireNotNull(j.sequence).canvas; check(j.targetBytes==null && canvas.height==480 && canvas.fps==30 && p.sequence.canvas.height==1920) },
        "preview preserves source trims and effects" to { val c=clip().copy(trim=Trim(1000,7000),settings=Settings(effects=ClipEffects(speedPercent=200))); val p=MovieProject(sequence=SequenceSpec(EditTimeline(listOf(c)))); check(p.previewJob("x").sequence!!.timeline.clips==listOf(c)) },
        "invalid cap cannot enter project" to { fails { MovieProject(targetBytes=5) } },
        "invalid command cannot change project history" to { val h=ProjectHistory(); fails { h.replace(h.current.edit(TimelineCommand.Remove("absent"))) }; check(!h.canUndo && h.current.sequence.timeline.clips.isEmpty()) }
    )
    fun runAll() { cases.forEach { (name, test) -> try { test() } catch(e: Throwable) { throw AssertionError(name,e) } } }
}
