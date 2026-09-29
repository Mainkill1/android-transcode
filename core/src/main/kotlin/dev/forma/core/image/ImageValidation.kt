package dev.forma.core.image
object ImageValidation {
    const val MAX_PIXELS = 40_000_000L
    const val MAX_DOCUMENT_BYTES = 1_048_576L
    fun validate(d: ImageEditDocument): List<ImageProblem> = buildList {
        fun check(ok: Boolean, field: String, message: String) { if(!ok)add(ImageProblem("INVALID_DOCUMENT",field,message)) }
        fun number(v: Double, min: Double, max: Double, field: String) = check(v.isFinite() && v in min..max,field,"$field must be finite in $min..$max.")
        check(d.schema==1,"schema","Unsupported image document schema.")
        check(d.revision>=0,"revision","Revision must be nonnegative.")
        check(d.source.hash.matches(Regex("[a-f0-9]{64}")),"source.hash","Source identity must be SHA-256.")
        check(d.source.bytes>0,"source.bytes","Source must be nonempty.")
        check(d.quarterTurns in 0..3,"quarterTurns","Quarter turns must be 0..3.")
        val c=d.crop
        listOf(c.left,c.top,c.right,c.bottom).forEach { number(it,0.0,1.0,"crop") }
        check(c.left<c.right && c.top<c.bottom,"crop","Crop must have positive area.")
        val a=d.adjustments
        number(a.brightness,-1.0,1.0,"brightness");number(a.contrast,0.0,2.0,"contrast")
        number(a.saturation,0.0,3.0,"saturation");number(a.gamma,0.1,3.0,"gamma")
        number(a.blurSigma,0.0,20.0,"blurSigma");number(a.sharpenAmount,0.0,2.0,"sharpenAmount")
        check(a.sharpenRadius in 1..5,"sharpenRadius","Sharpen radius must be 1..5 output pixels.")
        check(d.annotations.size<=128,"annotations","At most 128 annotations are allowed.")
        check(d.annotations.map { it.id }.distinct().size==d.annotations.size,"annotations","Annotation IDs must be unique.")
        fun color(c: Rgba, field: String) = check(listOf(c.red,c.green,c.blue,c.alpha).all { it in 0..255 },field,"RGBA must be 0..255.")
        d.annotations.forEach { o ->
            check(o.id.isNotBlank() && o.id.length<=128,"id","Invalid object identifier.")
            listOf(o.left,o.top,o.right,o.bottom).forEach { number(it,0.0,1.0,"annotation.bounds") }
            if(o.kind in setOf(AnnotationKind.LINE,AnnotationKind.ARROW))check(o.left!=o.right || o.top!=o.bottom,"annotation.bounds","Line endpoints must be distinct.")
            else check(o.left<o.right && o.top<o.bottom,"annotation.bounds","Bounds must have positive area.")
            check(o.text.codePointCount(0,o.text.length)<=4096,"text","Text is limited to 4096 Unicode code points.")
            check(o.font in setOf("sans-serif","serif","monospace"),"font","Choose a bundled system font.")
            number(o.textSize,1.0,2048.0,"textSize");number(o.strokeWidth,0.0,256.0,"strokeWidth");number(o.opacity,0.0,1.0,"opacity")
            color(o.color,"annotation.color");color(o.fill,"annotation.fill")
        }
        val p=d.output
        check(p.jpegQuality in 1..100 && p.webpQuality in 1..100,"quality","Quality must be 1..100.")
        check(p.pngCompression in 0..9,"pngCompression","PNG compression effort must be 0..9.")
        check(p.targetBytes==null || p.targetBytes in 32_000L..2_000_000_000L,"targetBytes","Limit must be an integer from 32000 to 2000000000 bytes.")
        for(v in listOf(p.width,p.height,p.canvasWidth,p.canvasHeight))check(v==null || v in 1..40_000_000,"dimensions","Dimensions must be positive and bounded.")
        check(p.resizeMode!=ResizeMode.PIXELS || p.width!=null || p.height!=null,"dimensions","Pixel resize needs a dimension.")
        number(p.percent,0.001,10000.0,"percent");check(p.longestEdge in 1..40_000_000,"longestEdge","Invalid longest edge.")
        check((p.canvasWidth==null)==(p.canvasHeight==null),"canvas","Specify both canvas dimensions.")
        color(p.padding,"padding");p.flatten?.let { color(it,"flatten");check(it.alpha==255,"flatten","JPEG flatten background must be opaque.") }
        check(p.stripMetadata,"metadata","Selective metadata retention is Planned.")
        check(d.estimatedBytes()<=MAX_DOCUMENT_BYTES,"document","Image document exceeds 1 MiB.")
    }
    fun requireValid(d: ImageEditDocument) { val p=validate(d);if(p.isNotEmpty())throw ImageFailure("INVALID_DOCUMENT",p.joinToString("\n") { it.message }) }
    fun requireSupported(info: ImageInfo) {
        if(info.width<=0 || info.height<=0 || info.width.toLong()*info.height>MAX_PIXELS)throw ImageFailure("RESOURCE_LIMIT","Source exceeds the 40000000-pixel ceiling.")
        if(info.format==ImageFormat.AUTO || info.frameCount!=1 || info.orientation !in 1..8 || info.bitDepth!=8 || info.profile !in setOf(ImageProfile.SRGB,ImageProfile.ASSUMED_SRGB) || info.gainMap || info.alpha==ImageAlpha.UNKNOWN)throw ImageFailure("UNSUPPORTED_IMAGE","Only inspected single-frame 8-bit SDR sRGB JPEG, PNG and WebP are supported.")
    }
    fun requireMemory(info: ImageInfo, output: ImageSize, budget: Long, markup: Boolean) {
        val estimate=info.width.toLong()*info.height*12L + output.width.toLong()*output.height*(if(markup)24L else 16L)+8_388_608
        if(estimate>budget)throw ImageFailure("RESOURCE_LIMIT","Image needs approximately ${estimate/1_048_576} MiB; available processing budget is ${budget/1_048_576} MiB.")
    }
}
