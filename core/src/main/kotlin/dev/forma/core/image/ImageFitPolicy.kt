package dev.forma.core.image
object ImageFitPolicy {
    fun fits(bytes:Long,target:Long?)=bytes>0 && (target==null || bytes<target)
    fun candidates(spec:ImageJobSpec,info:ImageInfo,format:ImageFormat=spec.resolvedFormat?:spec.document.output.format):List<ImageAttempt> {
        val p=spec.document.output
        val actual=if(format!=ImageFormat.AUTO)format else if(info.alpha==ImageAlpha.PRESENT)ImageFormat.PNG else ImageFormat.JPEG
        val quality=if(actual==ImageFormat.WEBP)p.webpQuality else p.jpegQuality
        val lossy=actual==ImageFormat.JPEG || (actual==ImageFormat.WEBP && !p.lossless)
        val q=listOf(1.0,.9,.8,.7,.6,.5,.4);val scale=listOf(1.0,1.0,.85,.75,.65,.55,.45)
        val unique=linkedMapOf<Pair<Int,ImageSize>,ImageAttempt>()
        for(i in 0..6){
            val a=ImageAttempt(i,actual,if(lossy)kotlin.math.max(kotlin.math.floor(quality*q[i]+.5).toInt(),kotlin.math.min(quality,35)) else quality,
                if(p.allowResizeToFit)scale[i] else 1.0,documentHash=spec.document.hashCode().toString())
            val size=ImageGeometry.resolve(info,spec.document,a).outputSize
            unique.putIfAbsent(a.quality to size,a)
            if(p.targetBytes==null)break
        }
        return unique.values.mapIndexed { i,a->a.copy(index=i) }
    }
}
