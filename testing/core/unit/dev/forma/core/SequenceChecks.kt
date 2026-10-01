package dev.forma.core

object SequenceChecks {
    private val source = Source("content://sample/a", "A.mp4", 6000, 640, 360, 1, 1)
    private fun clip(id: String = "a", src: Source = source, trim: Trim = Trim(), e: ClipEffects = ClipEffects()) =
        TimelineClip(id, src, trim, Settings(effects = e))
    private fun sequence(vararg clips: TimelineClip, overlap: Long = 0) =
        SequenceSpec(EditTimeline(clips.toList()), CanvasSpec(640, 360, 30), overlap)
    private fun fails(block: () -> Unit) = check(runCatching(block).isFailure)
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "delayed clip DSP trims the common timeline while standalone extraction retains sample duration" to {
            val delayed=source.copy(durationMs=3000,audioStreams=listOf(dev.forma.core.audio.SourceAudioFacts(0,48000,2,"stereo","fltp",2000000,totalSamples=96000,timelineOffsetUs=1000000)))
            val edit=dev.forma.core.audio.AudioEdit(nodes=listOf(dev.forma.core.audio.AudioEffectNode("fade","fades",parameters=dev.forma.core.audio.FadeParameters(fadeOutUs=250000))))
            val settings=Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,audioEdit=edit)
            val timeline=dev.forma.core.audio.AudioGraphPlanner.plan(delayed,Trim(),settings,preserveTimeline=true)
            check(timeline.filters.any {it.startsWith("atrim=start=0:end=3")})
            check(timeline.filters.any {it.startsWith("afade=t=out:st=2.75:d=0.25")})
            check(timeline.outputDurationUs==3000000L)
            val standalone=dev.forma.core.audio.AudioGraphPlanner.plan(delayed,Trim(),settings)
            check(standalone.originNormalized && standalone.outputDurationUs==2000000L)
        },

        "audio movie output and transport retain slowed final graph duration" to {
            val seq=sequence(clip(trim=Trim(0,2000)))
            val output=Settings(container=Container.M4A,audioEdit=dev.forma.core.audio.AudioEdit(rate=dev.forma.core.audio.AudioRate(1,2)))
            check(SequencePlanner.outputDuration(seq,output)==4000L)
            for(transport in listOf(false,true)) {
                val args=SequencePlanner.arguments(seq,output,listOf("/a"),"/out",audioTransport=transport)
                check(args[args.indexOf("-t")+1]=="4.000000")
            }
        },

        "per-clip file cap is retained and explicitly blocked for compound export" to {
            val edited=clip().copy(settings=Settings(audioEdit=dev.forma.core.audio.AudioEdit(output=dev.forma.core.audio.AudioOutputPolicy(maxBytes=100000))))
            val seq=sequence(edited)
            check(SequencePlanner.validate(seq,Settings()).any {"Per-clip audio byte limits" in it})
            check(seq.timeline.clips.single().settings.audioEdit.output.maxBytes==100000L)
        },

        "clip timing and audio graph compose without repeated input trim" to {
            val graph=dev.forma.core.audio.AudioEdit(nodes=listOf(dev.forma.core.audio.AudioEffectNode("gain","gain",parameters=dev.forma.core.audio.GainParameters(-6.0))))
            val settings=Settings(container=Container.M4A,effects=ClipEffects(speedPercent=200,volumePercent=50),audioEdit=graph.copy(rate=dev.forma.core.audio.AudioRate(2,1)))
            val args=Planner.arguments(source,Trim(1000,5000),settings,"/a","/out")
            val af=args[args.indexOf("-af")+1]
            check(Planner.outputDuration(source,Trim(1000,5000),settings)==1000L)
            check(af.split(',').count { it.startsWith("atrim=start") }==1)
            check("volume=0.500" in af && "volume=-6dB" in af)
            check("atempo=2" in af)
        },
        "sequence carries each clip graph and retains final output channel policy" to {
            val graph=dev.forma.core.audio.AudioEdit(nodes=listOf(dev.forma.core.audio.AudioEffectNode("gain","gain",parameters=dev.forma.core.audio.GainParameters(-6.0))))
            val edited=clip().copy(settings=Settings(effects=ClipEffects(speedPercent=200,volumePercent=50),audioEdit=graph))
            val seq=sequence(edited)
            val output=Settings(audioEdit=dev.forma.core.audio.AudioEdit(output=dev.forma.core.audio.AudioOutputPolicy(channels=dev.forma.core.audio.ChannelMode.MONO,sampleRateHz=44100)))
            check(SequencePlanner.clipSettings(edited,output,seq.canvas).audioEdit==graph)
            val args=SequencePlanner.arguments(seq,output,listOf("/a"),"/out")
            val fc=args[args.indexOf("-filter_complex")+1]
            check("volume=-6dB" in fc && "volume=0.500" in fc && "pan=mono" in fc)
            check("44100" in fc);check("-ac" !in args)
        },

        "audio movie permits a finite silent segment" to {
            val s = sequence(clip(), clip("b", source.copy(uri = "content://silent", audioTracks = 0)))
            check(SequencePlanner.validate(s, Settings(container = Container.M4A)).isEmpty())
            check(SequencePlanner.arguments(s, Settings(container = Container.M4A), listOf("/a", "/b"), "/out").any { "anullsrc" in it })
        },
        "composition budgets every decoder and the final encoder" to {
            val raw = SequencePlanner.arguments(sequence(clip(), clip("b")), Settings(), listOf("/a", "/b"), "/out")
            val bounded = WorkPolicy.withSequenceThreadBudget(raw, 12)
            check(bounded.count { it == "-threads:v" } == 3)
            check(bounded.take(4) == listOf("-filter_threads", "1", "-filter_complex_threads", "1"))
            check(bounded.takeLast(3) == listOf("-threads:v", "4", "/out"))
            fails { WorkPolicy.withThreadBudget(raw, 12) }
        },
        "join durations, not source count" to {
            check(SequencePlanner.duration(sequence(clip(), clip("b", trim = Trim(1000, 3000)))) == 8000L)
        },
        "crossfade subtracts actual overlap" to {
            check(SequencePlanner.duration(sequence(clip(), clip("b"), overlap = 500)) == 11500L)
        },
        "middle clip cannot overlap both neighbors beyond its length" to {
            check(SequencePlanner.validate(sequence(clip(), clip("b", trim = Trim(0, 800)), clip("c"), overlap = 500), Settings()).isNotEmpty())
        },
        "empty sequence cannot export" to { check(SequencePlanner.validate(sequence(), Settings()).isNotEmpty()) },
        "canvas rejects odd dimensions" to { fails { CanvasSpec(641, 360) } },
        "canvas color cannot inject a filter" to { fails { CanvasSpec(backgroundRgb = "black;movie=secret") } },
        "different resolutions are normalized" to {
            val s = sequence(clip(), clip("b", source.copy(uri = "content://sample/portrait", width = 360, height = 640)))
            val args = SequencePlanner.arguments(s, Settings(), listOf("/a", "/b"), "/out")
            check("-filter_complex" in args && args.any { "pad=640:360" in it && "concat=n=2:v=1:a=1" in it })
        },
        "neutral clips still trim before concatenation" to {
            val a = SequencePlanner.arguments(sequence(clip(trim = Trim(1000, 3000))), Settings(), listOf("/a"), "/out")
            check(a.any { "trim=start=1.000:end=3.000" in it && "atrim=start=1.000:end=3.000" in it })
        },
        "silent clip gets finite silence when other clips have audio" to {
            val a = SequencePlanner.arguments(sequence(clip(), clip("b", source.copy(uri = "content://b", audioTracks = 0))), Settings(), listOf("/a", "/b"), "/out")
            check(a.any { "anullsrc" in it && "atrim=duration=6.000000" in it })
        },
        "explicit sound removal stays removed" to {
            val a = SequencePlanner.arguments(sequence(clip(), clip("b")), Settings(audio = AudioEncoder.NONE), listOf("/a", "/b"), "/out")
            check("-an" in a && a.none { "anullsrc" in it })
        },
        "sequence audio preserves initial offsets before padding" to {
            val a = SequencePlanner.arguments(sequence(clip(trim = Trim(1000, 4000), e = ClipEffects(speedPercent = 200))), Settings(), listOf("/a"), "/out")
            check(a.any { "asetpts=PTS-1.000/TB" in it && "first_pts=0" in it })
        },
        "sequence reuses clip crop, speed and color filters" to {
            val e = ClipEffects(crop = CropRect(0, 0, 320, 180), speedPercent = 200, saturationPercent = 0)
            val a = SequencePlanner.arguments(sequence(clip(e = e)), Settings(), listOf("/a"), "/out")
            check(a.any { "crop=320:180:0:0" in it && "atempo=2.000" in it && "saturation=0.000" in it })
        },
        "crossfade uses video and audio overlap" to {
            val a = SequencePlanner.arguments(sequence(clip(), clip("b"), overlap = 500), Settings(), listOf("/a", "/b"), "/out")
            check(a.any { "xfade=" in it && "offset=5.500000" in it && "acrossfade=" in it })
        },
        "same URI cannot carry conflicting inspection facts" to {
            check(SequencePlanner.validate(sequence(clip(), clip("b", source.copy(width = 320))), Settings()).isNotEmpty())
        },
        "checked hardware cannot be bypassed through a sequence" to {
            check(SequencePlanner.validate(sequence(clip()), Settings(video = VideoEncoder.H264_HW, rateControl = RateControl.BITRATE)).isNotEmpty())
        },
        "missing sequence filters fail before executing" to {
            val caps = Capabilities(true, "", setOf("libx264", "aac"), setOf("mp4"), setOf("scale"))
            check(SequencePlanner.validate(sequence(clip()), Settings(), caps).any { "filter" in it.lowercase() })
        },
        "input output aliases are rejected" to { fails { SequencePlanner.arguments(sequence(clip()), Settings(), listOf("/a"), "/a") } },
        "job duration uses its immutable sequence" to {
            val s = sequence(clip(), clip("b"), overlap = 500)
            val job = JobSpec("id", source, Trim(), Settings(), sequence = s)
            check(JobPlans.duration(job) == 11500L)
        },
        "size limit is strictly less, not less or equal" to {
            check(UploadFit.fits(9_999_999, 10_000_000) && !UploadFit.fits(10_000_000, 10_000_000) && !UploadFit.fits(0, 10_000_000))
        },
        "byte budget preserves edits and does not shorten the movie" to {
            val s = Settings(effects = ClipEffects(speedPercent = 200), fps = 60)
            val budget = UploadFit.initial(s, 60000, true, 10_000_000)
            check(budget.effects == s.effects && budget.fps == 60 && budget.maxHeight == s.maxHeight)
            check(budget.rateControl == RateControl.BITRATE && budget.audio != AudioEncoder.NONE)
            check(budget.videoKbps + budget.audioKbps < 1333)
        },
        "oversize retry monotonically reduces bitrate" to {
            val s = UploadFit.initial(Settings(), 60000, true, 10_000_000)
            val next = UploadFit.retry(s, true, 10_000_000, 12_000_000)
            check(next != null && next.videoKbps < s.videoKbps && next.effects == s.effects)
        },
        "impossible target is not a truncated success" to { fails { UploadFit.initial(Settings(), 3_600_000, true, 100_000) } },
        "lossless audio cannot promise a bitrate-controlled size" to { fails { UploadFit.initial(Settings(container = Container.MKV, audio = AudioEncoder.FLAC), 60000, true, 10_000_000) } },
        "retries are bounded" to { check(UploadFit.MAX_ATTEMPTS == 4) },
        "frame quantization is accounted for once per clip" to {
            val s = sequence(clip(trim = Trim(0, 101)), clip("b", trim = Trim(0, 101)))
            check(SequencePlanner.duration(s) == 266L)
        }
    )
    fun runAll() { cases.forEach { (name, test) -> try { test() } catch (t: Throwable) { throw AssertionError(name, t) } } }
}
