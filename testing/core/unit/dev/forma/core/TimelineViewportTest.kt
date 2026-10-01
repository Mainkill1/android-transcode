package dev.forma.core

import org.junit.Assert.*
import org.junit.Test

class TimelineViewportTest {
    private val threeHours=10_800_000L

    @Test fun threeHourFilmstripPreservesOneMillisecondRequestsAtOneSecondZoom() {
        val fitted=TimelineViewport.fit(threeHours,Trim(3_600_000,3_605_000))
        val zoomed=fitted.copy(visibleStartMs=3_600_000,visibleDurationMs=1_000)
        assertEquals(3_600_001,zoomed.timeAt(1.0,1000.0))
        assertEquals(1.0,zoomed.xAt(3_600_001,1000.0),0.000001)
        assertEquals(3_600_001,zoomed.moveEdge(TrimEdge.START,3_600_001).trim.startMs)
    }

    @Test fun pinchKeepsAnchorAndPanClampsToSource() {
        val initial=TimelineViewport.fit(10_000,Trim())
        val zoomed=initial.pinch(750.0,2.0,1000.0)
        assertEquals(5_000,zoomed.visibleDurationMs)
        assertEquals(7_500,zoomed.timeAt(750.0,1000.0))
        assertEquals(0,zoomed.pan(100_000.0,1000.0).visibleStartMs)
        assertEquals(5_000,zoomed.pan(-100_000.0,1000.0).visibleStartMs)
        assertEquals(1_000,zoomed.pinch(500.0,100.0,1000.0).visibleDurationMs)
        assertEquals(10_000,zoomed.fit().visibleDurationMs)
    }

    @Test fun bracketsRespectFiftyMillisecondsAndAutoScrollAtVisibleEdge() {
        val viewport=TimelineViewport.fit(10_000,Trim(1_000,2_000))
            .copy(visibleStartMs=1_000,visibleDurationMs=1_000)
        assertEquals(1_950,viewport.moveEdge(TrimEdge.START,1_950).trim.startMs)
        assertThrows(IllegalArgumentException::class.java) {viewport.moveEdge(TrimEdge.START,1_951)}
        val scrolled=viewport.dragEdge(TrimEdge.END,995.0,1000.0,48.0)
        assertTrue(scrolled.visibleStartMs>viewport.visibleStartMs)
        assertTrue(scrolled.trim.endMs!!>2_000)
    }

    @Test fun cancelledGestureDoesNotChangeTimelineAndReleaseCreatesOneCommand() {
        val source=Source("content://video","clip.mp4",10_000,640,360,1)
        val clip=TimelineClip("one",source,Trim(1_000,8_000))
        val history=TimelineHistory(EditTimeline(listOf(clip)))
        val gesture=TrimGesture("one",clip.trim,source.durationMs)
        gesture.move(TrimEdge.START,1_250)
        gesture.move(TrimEdge.START,1_500)
        assertNull(gesture.cancel().commit())
        assertEquals(1_000,history.current.clips.single().trim.startMs)
        val committed=TrimGesture("one",clip.trim,source.durationMs).apply {
            move(TrimEdge.START,1_250);move(TrimEdge.START,1_500)
        }.commit()!!
        history.execute(committed)
        assertEquals(1_500,history.current.clips.single().trim.startMs)
        assertTrue(history.undo())
        assertEquals(1_000,history.current.clips.single().trim.startMs)
        assertFalse(history.canUndo)
    }
}
