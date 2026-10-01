package dev.forma.core

import org.junit.Assert.*
import org.junit.Test

class SequenceWindowPlannerTest {
    private fun source(id:String,duration:Long)=Source("content://movie/$id","$id.mp4",duration,320,180,1)
    private fun project(clips:List<TimelineClip>,transitionMs:Long=0)=MovieProject(
        sequence=SequenceSpec(EditTimeline(clips),CanvasSpec(320,180,30),transitionMs),targetBytes=null)

    @Test fun threeHourClipWindowStagesOnlyNearbySourceTime() {
        val movie=project(listOf(TimelineClip("long",source("long",10_800_000))))
        val result=SequenceWindowPlanner.window(movie,7_200_000) as MovieWindowResult.Planned
        val clipped=result.window.sequence.timeline.clips.single().trim
        assertTrue(clipped.startMs in 7_196_000..7_199_000)
        assertTrue(clipped.endMs!! in 7_201_000..7_204_000)
        assertTrue(SequencePlanner.duration(result.window.sequence)<=5_200)
        assertEquals(150,result.window.requestEndFrame-result.window.requestStartFrame)
    }

    @Test fun transitionWindowKeepsBothSourcesAndTheFullDissolve() {
        val movie=project(listOf(TimelineClip("red",source("red",4_000)),
            TimelineClip("blue",source("blue",4_000))),1_000)
        val result=SequenceWindowPlanner.window(movie,3_100,500) as MovieWindowResult.Planned
        val window=result.window
        assertEquals(listOf("red","blue"),window.sequence.timeline.clips.map {it.id})
        assertEquals(30,SequencePlanner.overlapFrames(window.sequence))
        assertTrue(window.renderEndFrame>window.requestEndFrame)
        assertTrue(SequencePlanner.frames(window.sequence).all {it>30})
        assertEquals(15,window.requestEndFrame-window.requestStartFrame)
    }

    @Test fun speedAndClipOrderMapTheCorrectSourceRange() {
        val fast=TimelineClip("fast",source("fast",4_000),settings=Settings(effects=ClipEffects(speedPercent=200)))
        val next=TimelineClip("next",source("next",4_000))
        val movie=project(listOf(fast,next))
        val result=SequenceWindowPlanner.window(movie,1_500,1_000) as MovieWindowResult.Planned
        assertEquals(listOf("fast"),result.window.sequence.timeline.clips.map {it.id})
        val trim=result.window.sequence.timeline.clips.single().trim
        assertTrue(trim.startMs in 1_950..2_050)
        assertTrue(trim.endMs!! in 3_900..4_000)
        assertEquals(30,result.window.requestEndFrame-result.window.requestStartFrame)
        assertEquals(1_500L,SequenceWindowPlanner.movieTimeForClip(movie.sequence,"fast",3_000))
        val reordered=project(listOf(next,fast))
        assertEquals(5_500L,SequenceWindowPlanner.movieTimeForClip(reordered.sequence,"fast",3_000))
        assertEquals("next",(SequenceWindowPlanner.window(reordered,1_500,1_000) as MovieWindowResult.Planned)
            .window.sequence.timeline.clips.single().id)
    }

    @Test fun unsupportedTemporalEffectsAndInvalidSplitLeaveProjectUntouched() {
        val fading=TimelineClip("fade",source("fade",4_000),settings=Settings(effects=ClipEffects(fadeInMs=250)))
        val movie=project(listOf(fading))
        assertTrue(SequenceWindowPlanner.window(movie,2_000) is MovieWindowResult.Unsupported)
        assertThrows(IllegalArgumentException::class.java) {movie.edit(TimelineCommand.Split("fade",2_000,"right"))}
        assertEquals(1,movie.sequence.timeline.clips.size)
    }
}
