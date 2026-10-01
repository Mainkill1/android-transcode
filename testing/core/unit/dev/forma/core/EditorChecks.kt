package dev.forma.core

/** Shared assertions: invoked by JVM tests and the standalone host runner, never production. */
object EditorChecks {
    private val source = Source("content://fixture", "fixture.mp4", 10_000, 640, 480, 1, 1)
    private fun args(e: ClipEffects, trim: Trim = Trim(2000, 8000)) =
        Planner.arguments(source, trim, Settings(effects = e), "/input with spaces.mp4", "/output.mp4")
    private fun value(tokens: List<String>, flag: String) = tokens[tokens.indexOf(flag) + 1]
    private fun rejected(block: () -> Unit) { check(runCatching(block).exceptionOrNull() is IllegalArgumentException) }
    fun run(): Int {
        var count = 0
        fun test(name: String, body: () -> Unit) { body(); count++; println("PASS $name") }
        test("neutral edits preserve legacy trim arguments") {
            val a = args(ClipEffects()); check("-ss" in a); check(value(a, "-t") == "6.000"); check("-af" !in a)
        }
        test("trim precedes speed and audio uses matching duration") {
            val a = args(ClipEffects(speedPercent = 200)); check("-ss" !in a)
            check(value(a, "-t") == "3.000")
            check(value(a, "-vf").startsWith("trim=start=2.000:end=8.000,setpts=PTS-2.000/TB,setpts=PTS/2.000"))
            check(value(a, "-af").startsWith("atrim=start=2.000:end=8.000,asetpts=PTS-2.000/TB,atempo=2.000,asetpts=PTS-STARTPTS+STARTPTS/2.000"))
        }
        test("slow motion chains supported tempo factors") {
            val a = args(ClipEffects(speedPercent = 25)); check(value(a, "-t") == "24.000")
            check(value(a, "-af").contains("atempo=0.500,atempo=0.500"))
        }
        test("four times speed does not use sample-skipping tempo factor") {
            check(value(args(ClipEffects(speedPercent = 400)), "-af").contains("atempo=2.000,atempo=2.000"))
        }
        test("crop and rotation occur before final resize") {
            val vf = value(args(ClipEffects(crop = CropRect(20, 10, 320, 200), rotation = QuarterTurn.CLOCKWISE, flipHorizontal = true)), "-vf")
            check(vf.indexOf("crop=320:200:20:10") < vf.indexOf("transpose=clock"))
            check(vf.indexOf("transpose=clock") < vf.indexOf("scale=")); check("hflip" in vf)
        }
        test("deinterlacing precedes rotation of scan lines") {
            val a = Planner.arguments(source, Trim(), Settings(deinterlace = true,
                effects = ClipEffects(rotation = QuarterTurn.CLOCKWISE)), "/in", "/out")
            val vf = value(a, "-vf")
            check(vf.indexOf("yadif") < vf.indexOf("transpose"))
        }
        test("invalid crop bounds and odd geometry are rejected") {
            rejected { args(ClipEffects(crop = CropRect(630, 0, 320, 200))) }
            rejected { args(ClipEffects(crop = CropRect(0, 0, 321, 200))) }
        }
        test("metadata rotation gives the editor the same crop bounds as export") {
            val rotated=source.copy(displayRotationDegrees=90)
            check(PreviewGeometry.displaySize(640,480,90)==(480 to 640))
            val crop=CropRect(20,100,200,320)
            check(PreviewGeometry.mapCrop(640,480,90,ClipEffects(),crop)==PreviewCrop.Valid(crop))
            val vf=value(Planner.arguments(rotated,Trim(),Settings(effects=ClipEffects(crop=crop)),"/in","/out"),"-vf")
            check("crop=200:320:20:100" in vf)
            check(Planner.validate(rotated,Trim(),Settings(effects=ClipEffects(crop=CropRect(400,0,200,320)))).isNotEmpty())
        }
        test("all quarter-turn metadata orientations preserve crop geometry") {
            for(turn in listOf(0,90,180,270)) {
                val dimensions=PreviewGeometry.displaySize(640,480,turn)!!
                val selected=CropRect(20,20,100,200)
                check(PreviewGeometry.mapCrop(640,480,turn,ClipEffects(),selected)==PreviewCrop.Valid(selected))
                check(PreviewGeometry.displayRect(640,480,turn,ClipEffects(),selected)==PreviewCrop.Valid(selected))
                check(dimensions==if(turn==90 || turn==270) 480 to 640 else 640 to 480)
            }
        }
        test("crop mapping inverts added rotation and mirror") {
            val effects=ClipEffects(rotation=QuarterTurn.CLOCKWISE,flipHorizontal=true)
            val selected=CropRect(40,60,200,300)
            val visual=PreviewGeometry.displayRect(640,480,0,effects,selected)
            check(visual is PreviewCrop.Valid)
            check(PreviewGeometry.mapCrop(640,480,0,effects,visual.crop)==PreviewCrop.Valid(selected))
            check(PreviewGeometry.mapCrop(641,481,0,ClipEffects(rotation=QuarterTurn.CLOCKWISE),CropRect(20,20,200,200)) is PreviewCrop.Unsupported)
            val oddSizeEffects=ClipEffects(rotation=QuarterTurn.CLOCKWISE)
            val oddOverlay=PreviewGeometry.displayRect(641,481,0,oddSizeEffects,CropRect(0,0,640,480)) as PreviewCrop.Valid
            check(oddOverlay.crop==CropRect(1,0,480,640))
            val oddDrag=PreviewGeometry.dragDisplayCorner(oddOverlay.crop,0,31,20,481,641)
            check(oddDrag.x==31)
            check(PreviewGeometry.mapCrop(641,481,0,oddSizeEffects,oddDrag) is PreviewCrop.Valid)
        }
        test("unsupported display matrix blocks crop") {
            check(PreviewGeometry.mapCrop(640,480,null,ClipEffects(),CropRect(0,0,100,100)) is PreviewCrop.Unsupported)
        }
        test("display matrix parser accepts quarter turns and rejects shear") {
            val pure="""00000000: 0 -65536 0
00000001: 65536 0 0
00000002: 0 0 1073741824"""
            check(PreviewGeometry.metadataRotation(pure,90.0)==90)
            check(PreviewGeometry.metadataRotation(pure,45.0)==null)
            check(PreviewGeometry.metadataRotation(pure.replace("-65536","-30000"),90.0)==null)
            check(PreviewGeometry.metadataRotation(null,-90.0)==270)
        }
        test("overflowing crop is rejected") { rejected { args(ClipEffects(crop = CropRect(Int.MAX_VALUE, 0, 320, 200))) } }
        test("invalid speed never divides by zero") { rejected { args(ClipEffects(speedPercent = 0)) } }
        test("fades are measured on edited duration") {
            val a = args(ClipEffects(speedPercent = 200, fadeOutMs = 1000, audioFadeOutMs = 500))
            check("fade=t=out:st=2.000:d=1.000" in value(a, "-vf"))
            check("afade=t=out:st=2.500:d=0.500" in value(a, "-af"))
            rejected { args(ClipEffects(speedPercent = 400, fadeInMs = 2000)) }
        }
        test("normalization precedes gain and final fades") {
            val af = value(args(ClipEffects(normalizeAudio = true, volumePercent = 50, audioFadeInMs = 200)), "-af")
            check(af.indexOf("loudnorm=") < af.indexOf("volume=0.500"))
            check(af.indexOf("volume=0.500") < af.indexOf("afade=")); check("aresample=48000" in af)
        }
        test("audio-only does not silently discard visual edits") {
            check(Planner.validate(source, Trim(), Settings(container = Container.M4A, effects = ClipEffects(rotation = QuarterTurn.HALF))).isNotEmpty())
        }
        test("missing sound does not silently discard requested audio effects") {
            check(Planner.validate(source.copy(audioTracks = 0), Trim(), Settings(effects = ClipEffects(volumePercent = 50))).isNotEmpty())
        }
        test("edited hardware geometry is not advertised as qualified") {
            check(Planner.validate(source, Trim(), Settings(video = VideoEncoder.H264_HW, rateControl = RateControl.BITRATE,
                fps = 30, effects = ClipEffects(rotation = QuarterTurn.CLOCKWISE))).any { "hardware" in it.lowercase() })
        }
        test("required effect filters are capability checked") {
            val caps = Capabilities(true, "", setOf("libx264", "aac"), setOf("mp4"), setOf("scale"))
            val errors = Planner.validate(source, Trim(), Settings(effects = ClipEffects(blurSigma = 3)), caps)
            check(errors.any { "gblur" in it }); check(errors.any { "trim" in it })
        }
        test("locale cannot change FFmpeg decimal syntax") {
            val previous = java.util.Locale.getDefault()
            try { java.util.Locale.setDefault(java.util.Locale.GERMANY)
                check("eq=brightness=0.100" in value(args(ClipEffects(brightnessPercent = 10)), "-vf"))
            } finally { java.util.Locale.setDefault(previous) }
        }
        test("paths remain argument tokens") { check("/input with spaces.mp4" in args(ClipEffects())) }
        return count
    }
}
