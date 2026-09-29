package dev.forma.core.image
import dev.forma.core.Capabilities

data class ImagePlan(val spec:ImageJobSpec,val attempt:ImageAttempt,val geometry:ImageGeometryResult,val effects:ImageEffectGraph,
    val expectedAlpha:ImageAlpha,val requiredFilters:Set<String>) {
    fun arguments(input:String,output:String):List<String> {
        require(input!=output){"Original cannot be the output."}
        val p=spec.document.output
        val filters=mutableListOf<String>();filters+=geometry.filters
        if(effects.filters.isNotEmpty()){filters+="format=gbrap";filters+=effects.filters}
        val args=mutableListOf("-hide_banner","-nostdin","-v","error","-xerror","-n","-noautorotate","-i",input)
        attempt.markupPath?.let { args+=listOf("-i",it) }
        val color=p.flatten
        val flatten= color!=null && (attempt.format==ImageFormat.JPEG || (p.format==ImageFormat.JPEG && (attempt.rendererIdentity.contains("proxy") || attempt.rendererIdentity.contains("actual"))))
        val complex=attempt.markupPath!=null || flatten
        if(complex){
            val graph=mutableListOf<String>()
            graph+="[0:v:0]${(filters+"format=rgba").joinToString(",")}[base]"
            var current="base"
            if(attempt.markupPath!=null){graph+="[base][1:v:0]overlay=0:0:format=rgb:alpha=straight[marked]";current="marked"}
            if(flatten && color!=null){val size=geometry.outputSize
                graph+="color=c=${color.ffmpeg()}:s=${size.width}x${size.height},format=rgba[bg]";graph+="[bg][$current]overlay=0:0:format=rgb:alpha=straight[flat]";current="flat"}
            graph+="[$current]format=${if(attempt.format==ImageFormat.JPEG)"yuvj444p" else "rgba"}[out]"
            args+=listOf("-filter_complex",graph.joinToString(";"),"-map","[out]")
        }else{
            if(filters.isNotEmpty())args+=listOf("-vf",filters.joinToString(","))
            args+=listOf("-map","0:v:0")
        }
        args+=listOf("-an","-sn","-dn","-map_metadata","-1","-map_chapters","-1","-frames:v","1","-c:v",attempt.format.encoder)
        when(attempt.format){
            ImageFormat.PNG->args+=listOf("-pix_fmt","rgba","-compression_level",p.pngCompression.toString())
            ImageFormat.JPEG->args+=listOf("-pix_fmt","yuvj444p","-q:v",(2+(100-attempt.quality)*29/99).toString())
            ImageFormat.WEBP->args+=listOf("-pix_fmt",if(expectedAlpha==ImageAlpha.PRESENT)"yuva420p" else "yuv420p","-quality",attempt.quality.toString(),"-lossless",if(p.lossless)"1" else "0")
            ImageFormat.AUTO->error("Unresolved format")
        }
        args+=listOf("-f","image2","-update","1",output)
        return args
    }
}
object ImagePlanner {
    fun resolveFormat(info:ImageInfo,spec:ImageJobSpec,caps:Capabilities):ImageFormat {
        val requested=spec.document.output.format
        val geometry=ImageGeometry.resolve(info,spec.document,ImageAttempt(0,ImageFormat.PNG,90))
        val transparent=hasAlpha(info,geometry,spec.document.output)
        return if(requested!=ImageFormat.AUTO)requested else if("libwebp" in caps.encoders)ImageFormat.WEBP else if(transparent)ImageFormat.PNG else ImageFormat.JPEG
    }
    private fun hasAlpha(info:ImageInfo,g:ImageGeometryResult,p:ImageOutputPolicy) = info.alpha==ImageAlpha.PRESENT ||
        ((g.outputSize.width>g.contentSize.width || g.outputSize.height>g.contentSize.height) && p.padding.alpha<255)
    fun plan(info:ImageInfo,spec:ImageJobSpec,attempt:ImageAttempt,caps:Capabilities):ImagePlan {
        ImageValidation.requireValid(spec.document);ImageValidation.requireSupported(info)
        if(!caps.available || attempt.format.encoder !in caps.encoders || "image2" !in caps.muxers)throw ImageFailure("CAPABILITY_UNAVAILABLE","${attempt.format} needs its native encoder and image2 muxer. ${caps.reason}")
        if(info.hash!=spec.document.source.hash || info.bytes!=spec.document.source.bytes)throw ImageFailure("SOURCE_CHANGED","Original identity changed. Reselect the source.")
        val g=ImageGeometry.resolve(info,spec.document,attempt);val effects=ImageEffects.compile(spec.document.adjustments,g,if(attempt.rendererIdentity.contains("proxy"))attempt.scale else 1.0)
        if(hasAlpha(info,g,spec.document.output) && attempt.format==ImageFormat.JPEG && spec.document.output.flatten==null)throw ImageFailure("ALPHA_WOULD_BE_LOST","Choose an opaque JPEG flatten background explicitly.")
        if(spec.document.annotations.isNotEmpty() && attempt.markupPath==null)throw ImageFailure("MARKUP_REQUIRED","Active objects require a full-resolution markup plane.")
        val needed=(g.filters.map { it.substringBefore('=') }+effects.requiredFilters+
            if(effects.filters.isNotEmpty())listOf("format") else emptyList()).toMutableSet()
        if(attempt.markupPath!=null || (spec.document.output.flatten!=null && (attempt.format==ImageFormat.JPEG || spec.document.output.format==ImageFormat.JPEG)))needed+=setOf("format","overlay","color")
        val missing=needed-caps.filters
        if(missing.isNotEmpty())throw ImageFailure("CAPABILITY_UNAVAILABLE","Native filters unavailable: ${missing.joinToString()}.")
        val alpha=if(attempt.format==ImageFormat.JPEG)ImageAlpha.OPAQUE else if(hasAlpha(info,g,spec.document.output))ImageAlpha.PRESENT else ImageAlpha.OPAQUE
        return ImagePlan(spec,attempt,g,effects,alpha,needed)
    }
}
