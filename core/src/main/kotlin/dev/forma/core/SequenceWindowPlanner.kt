package dev.forma.core

sealed interface MovieWindowResult {
    data class Planned(val window:MovieWindow):MovieWindowResult
    data class Unsupported(val reason:String):MovieWindowResult
}

/** Original-movie frame interval plus the short sequence needed to render it. */
data class MovieWindow(val sequence:SequenceSpec,val requestStartFrame:Long,val requestEndFrame:Long,
    val renderStartFrame:Long,val renderEndFrame:Long) {
    val seekFrames:Long get()=requestStartFrame-renderStartFrame
    val outputFrames:Long get()=requestEndFrame-requestStartFrame
}

/** A bounded source-time window that preserves complete transition overlap at its edges. */
object SequenceWindowPlanner {
    fun movieTimeForClip(sequence:SequenceSpec,clipId:String,sourceMs:Long):Long? {
        val index=sequence.timeline.clips.indexOfFirst {it.id==clipId}
        if(index<0)return null
        val clip=sequence.timeline.clips[index]
        val end=clip.trim.endMs ?: clip.source.durationMs
        if(sourceMs !in clip.trim.startMs..end)return null
        val frames=SequencePlanner.frames(sequence)
        val startFrames=frames.take(index).sum()-SequencePlanner.overlapFrames(sequence)*index
        val localMs=(sourceMs-clip.trim.startMs)*100/clip.settings.effects.speedPercent
        return startFrames*1_000/sequence.canvas.fps+localMs
    }

    fun window(project:MovieProject,playheadMs:Long,limitMs:Long=5_000):MovieWindowResult {
        if(limitMs !in 50..5_000) return MovieWindowResult.Unsupported("Choose a preview from 50 ms to five seconds.")
        val sequence=project.sequence
        val clips=sequence.timeline.clips
        if(clips.isEmpty())return MovieWindowResult.Unsupported("Add a movie clip before previewing.")
        if(project.settings.container.audioOnly)
            return MovieWindowResult.Unsupported("Audio-only movies use the full render preview.")
        if(project.settings.audioEdit!=Settings().audioEdit)
            return MovieWindowResult.Unsupported("Movie-wide audio effects need a full render preview.")
        if(clips.any {clip ->clip.source.videoTracks==0 || clip.settings.audioEdit!=Settings().audioEdit ||
                clip.settings.effects.let {it.fadeInMs>0 || it.fadeOutMs>0 || it.audioFadeInMs>0 || it.audioFadeOutMs>0 || it.normalizeAudio}})
            return MovieWindowResult.Unsupported("This clip has a temporal effect that needs a full render preview.")
        val fps=sequence.canvas.fps
        val counts=runCatching {SequencePlanner.frames(sequence)}.getOrElse {
            return MovieWindowResult.Unsupported(it.message ?: "Could not plan movie frame timing.")
        }
        val overlap=SequencePlanner.overlapFrames(sequence)
        val starts=ArrayList<Long>(counts.size)
        var cursor=0L
        counts.forEach {count ->starts+=cursor;cursor+=count-overlap}
        val total=counts.sum()-overlap*(counts.size-1)
        if(total<=0)return MovieWindowResult.Unsupported("Movie transitions consume the selected clips.")
        val requestedCount=((limitMs*fps+999)/1_000).coerceIn(1,total)
        val center=(playheadMs.coerceAtLeast(0)*fps+500)/1_000
        val requestedStart=(center-requestedCount/2).coerceIn(0,total-requestedCount)
        val requestedEnd=requestedStart+requestedCount
        var renderStart=requestedStart
        var renderEnd=requestedEnd
        if(overlap>0)for(index in 0 until counts.lastIndex) {
            val transitionEnd=starts[index]+counts[index]
            val transitionStart=transitionEnd-overlap
            if(renderStart in transitionStart..transitionEnd)
                renderStart=(transitionStart-1).coerceAtLeast(0)
            if(renderEnd in transitionStart..transitionEnd)
                renderEnd=(transitionEnd+1).coerceAtMost(total)
        }
        if(renderEnd-renderStart>fps*30L)
            return MovieWindowResult.Unsupported("This transition needs more than 30 seconds of preview work.")
        val selected=buildList {
            clips.forEachIndexed {index,clip ->
                val localStart=(renderStart-starts[index]).coerceIn(0,counts[index])
                val localEnd=(renderEnd-starts[index]).coerceIn(0,counts[index])
                if(localEnd<=localStart)return@forEachIndexed
                val sourceEnd=clip.trim.endMs ?: clip.source.durationMs
                val speed=clip.settings.effects.speedPercent
                val sourceStart=if(localStart==0L)clip.trim.startMs
                    else (clip.trim.startMs+localStart*speed*10/fps).coerceAtMost(sourceEnd-1)
                val end=if(localStart==0L && localEnd==counts[index])sourceEnd
                    else sourceEndForFrames(sourceStart,sourceEnd,localEnd-localStart,speed,fps)
                        ?: return MovieWindowResult.Unsupported("Could not align a clip to the preview frame grid.")
                add(clip.copy(trim=Trim(sourceStart,end)))
            }
        }
        if(selected.isEmpty())return MovieWindowResult.Unsupported("No video frames fall inside the preview window.")
        val cropped=runCatching {sequence.copy(timeline=EditTimeline(selected))}.getOrElse {
            return MovieWindowResult.Unsupported(it.message ?: "Preview sequence is invalid.")
        }
        val actual=runCatching {SequencePlanner.frames(cropped)}.getOrElse {
            return MovieWindowResult.Unsupported(it.message ?: "Preview sequence has invalid timing.")
        }
        val actualFrames=actual.sum()-overlap*(actual.size-1)
        if(actualFrames!=renderEnd-renderStart)
            return MovieWindowResult.Unsupported("Preview frames could not align with the movie timeline.")
        val previewSettings=project.settings.copy(container=Container.MP4,video=VideoEncoder.X264,
            rateControl=RateControl.QUALITY,audio=if(project.settings.audio==AudioEncoder.NONE)AudioEncoder.NONE else AudioEncoder.AAC)
        if(SequencePlanner.validate(cropped,previewSettings).isNotEmpty())
            return MovieWindowResult.Unsupported("This movie segment needs a full render preview.")
        return MovieWindowResult.Planned(MovieWindow(cropped,requestedStart,requestedEnd,renderStart,renderEnd))
    }

    private fun sourceEndForFrames(start:Long,end:Long,wanted:Long,speed:Int,fps:Int):Long? {
        if(wanted<=0 || end<=start)return null
        fun frames(at:Long):Long {
            val outputMs=(at-start)*100/speed
            return (outputMs*fps+999)/1_000
        }
        var low=start+1;var high=end
        while(low<high) {
            val middle=low+(high-low)/2
            if(frames(middle)<wanted)low=middle+1 else high=middle
        }
        return low.takeIf {frames(it)==wanted}
    }
}
