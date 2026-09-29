package dev.forma.app.data
import dev.forma.core.image.*
import org.json.JSONObject
import org.json.JSONArray
/** Closed typed schema: malformed/unknown data is rejected and its storage owner preserves bytes. */
object ImageDocumentCodec {
    private fun color(c:Rgba)=JSONArray(listOf(c.red,c.green,c.blue,c.alpha))
    private fun rgba(j:JSONArray):Rgba { require(j.length()==4);return Rgba((0..3).map { i->val n=j.get(i);require(n is Int);n as Int }.let {it[0]},j.getInt(1),j.getInt(2),j.getInt(3)) }
    fun encode(d:ImageEditDocument):JSONObject {
        val a=d.adjustments;val p=d.output;val c=d.crop
        return JSONObject().put("schema",d.schema).put("revision",d.revision).put("source",JSONObject().put("uri",d.source.uri).put("name",d.source.name).put("hash",d.source.hash).put("bytes",d.source.bytes))
            .put("crop",JSONArray(listOf(c.left,c.top,c.right,c.bottom))).put("cropAspectRatio",d.cropAspectRatio?:JSONObject.NULL).put("quarterTurns",d.quarterTurns).put("flipHorizontal",d.flipHorizontal).put("flipVertical",d.flipVertical)
            .put("adjustments",JSONObject().put("brightness",a.brightness).put("contrast",a.contrast).put("saturation",a.saturation).put("gamma",a.gamma).put("blurSigma",a.blurSigma).put("sharpenRadius",a.sharpenRadius).put("sharpenAmount",a.sharpenAmount))
            .put("annotations",JSONArray(d.annotations.map { o->JSONObject().put("id",o.id).put("kind",o.kind.name).put("bounds",JSONArray(listOf(o.left,o.top,o.right,o.bottom))).put("text",o.text).put("color",color(o.color)).put("fill",color(o.fill)).put("strokeWidth",o.strokeWidth).put("textSize",o.textSize).put("alignment",o.alignment.name).put("font",o.font).put("opacity",o.opacity) }))
            .put("output",JSONObject().put("format",p.format.name).put("jpegQuality",p.jpegQuality).put("webpQuality",p.webpQuality).put("lossless",p.lossless).put("pngCompression",p.pngCompression).put("resizeMode",p.resizeMode.name).put("width",p.width?:JSONObject.NULL).put("height",p.height?:JSONObject.NULL).put("percent",p.percent).put("longestEdge",p.longestEdge).put("aspectLock",p.aspectLock).put("allowUpscale",p.allowUpscale).put("allowResizeToFit",p.allowResizeToFit).put("canvasWidth",p.canvasWidth?:JSONObject.NULL).put("canvasHeight",p.canvasHeight?:JSONObject.NULL).put("anchor",p.anchor.name).put("padding",color(p.padding)).put("flatten",p.flatten?.let(::color)?:JSONObject.NULL).put("targetBytes",p.targetBytes?:JSONObject.NULL).put("stripMetadata",p.stripMetadata))
    }
    private fun closed(j:JSONObject,keys:String) {require(j.keys().asSequence().all { it in keys.split(',') }){"Unknown saved image operation."}}
    private fun JSONObject.text(k:String):String {val value=get(k);require(value is String){"$k must be text."};return value}
    private fun JSONObject.integer(k:String):Long {val n=get(k);require(n is Int || n is Long){"$k must be an integer."};return (n as Number).toLong()}
    private fun JSONObject.int(k:String):Int {val n=integer(k);require(n in Int.MIN_VALUE..Int.MAX_VALUE);return n.toInt()}
    private fun JSONObject.decimal(k:String):Double {val n=get(k);require(n is Number && n.toDouble().isFinite()){ "$k must be a finite number." };return n.toDouble()}
    private fun JSONObject.bool(k:String):Boolean {val n=get(k);require(n is Boolean);return n}
    private fun JSONObject.nullInt(k:String)=if(isNull(k))null else int(k)
    private fun bounds(a:JSONArray):List<Double> {require(a.length()==4);return (0..3).map {val n=a.get(it);require(n is Number && n.toDouble().isFinite());n.toDouble()} }
    fun decode(j:JSONObject):ImageEditDocument {
        require(j.toString().toByteArray(Charsets.UTF_8).size<=ImageValidation.MAX_DOCUMENT_BYTES)
        closed(j,"schema,revision,source,crop,cropAspectRatio,quarterTurns,flipHorizontal,flipVertical,adjustments,annotations,output")
        require(j.int("schema")==1){"Unsupported image schema; preserved for recovery."}
        val s=j.getJSONObject("source");closed(s,"uri,name,hash,bytes");val c=bounds(j.getJSONArray("crop"));val a=j.getJSONObject("adjustments");closed(a,"brightness,contrast,saturation,gamma,blurSigma,sharpenRadius,sharpenAmount")
        val list=j.getJSONArray("annotations");require(list.length()<=128)
        val objects=(0 until list.length()).map {i->val o=list.getJSONObject(i);closed(o,"id,kind,bounds,text,color,fill,strokeWidth,textSize,alignment,font,opacity");val b=bounds(o.getJSONArray("bounds"));ImageAnnotation(o.text("id"),AnnotationKind.valueOf(o.text("kind")),b[0],b[1],b[2],b[3],o.text("text"),rgba(o.getJSONArray("color")),rgba(o.getJSONArray("fill")),o.decimal("strokeWidth"),o.decimal("textSize"),TextAlignment.valueOf(o.text("alignment")),o.text("font"),o.decimal("opacity"))}
        val p=j.getJSONObject("output");closed(p,"format,jpegQuality,webpQuality,lossless,pngCompression,resizeMode,width,height,percent,longestEdge,aspectLock,allowUpscale,allowResizeToFit,canvasWidth,canvasHeight,anchor,padding,flatten,targetBytes,stripMetadata")
        return ImageEditDocument(j.int("schema"),ImageSource(s.text("uri"),s.text("name"),s.text("hash"),s.integer("bytes")),j.integer("revision"),NormalizedCrop(c[0],c[1],c[2],c[3]),j.int("quarterTurns"),j.bool("flipHorizontal"),j.bool("flipVertical"),ImageAdjustments(a.decimal("brightness"),a.decimal("contrast"),a.decimal("saturation"),a.decimal("gamma"),a.decimal("blurSigma"),a.int("sharpenRadius"),a.decimal("sharpenAmount")),objects,
            ImageOutputPolicy(ImageFormat.valueOf(p.text("format")),p.int("jpegQuality"),p.int("webpQuality"),p.bool("lossless"),p.int("pngCompression"),ResizeMode.valueOf(p.text("resizeMode")),p.nullInt("width"),p.nullInt("height"),p.decimal("percent"),p.int("longestEdge"),p.bool("aspectLock"),p.bool("allowUpscale"),p.bool("allowResizeToFit"),p.nullInt("canvasWidth"),p.nullInt("canvasHeight"),CanvasAnchor.valueOf(p.text("anchor")),rgba(p.getJSONArray("padding")),if(p.isNull("flatten"))null else rgba(p.getJSONArray("flatten")),if(p.isNull("targetBytes"))null else p.integer("targetBytes"),p.bool("stripMetadata")),if(j.isNull("cropAspectRatio"))null else j.decimal("cropAspectRatio")).also(ImageValidation::requireValid).frozen()
    }
    fun encodeInfo(i:ImageInfo)=JSONObject().put("width",i.width).put("height",i.height).put("format",i.format.name).put("orientation",i.orientation).put("frameCount",i.frameCount?:JSONObject.NULL).put("bitDepth",i.bitDepth).put("alpha",i.alpha.name).put("profile",i.profile.name).put("gainMap",i.gainMap).put("hash",i.hash).put("bytes",i.bytes).put("orientationApplied",i.orientationApplied).put("minimumAlpha",i.minimumAlpha?:JSONObject.NULL)
    fun decodeInfo(j:JSONObject)=ImageInfo(j.int("width"),j.int("height"),ImageFormat.valueOf(j.text("format")),j.int("orientation"),j.nullInt("frameCount"),j.int("bitDepth"),ImageAlpha.valueOf(j.text("alpha")),ImageProfile.valueOf(j.text("profile")),j.bool("gainMap"),j.text("hash"),j.integer("bytes"),j.bool("orientationApplied"),j.nullInt("minimumAlpha")).also(ImageValidation::requireSupported)
}
