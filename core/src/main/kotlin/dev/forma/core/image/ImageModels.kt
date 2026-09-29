package dev.forma.core.image

import java.util.Collections
import dev.forma.core.Settings
import dev.forma.core.settings.MediaPreferences

enum class ImageFormat(val extension: String, val mime: String, val encoder: String) {
    AUTO("", "image/*", ""), JPEG("jpg", "image/jpeg", "mjpeg"), PNG("png", "image/png", "png"), WEBP("webp", "image/webp", "libwebp");
    val decoder get()=when(this){PNG->"png";JPEG->"mjpeg";WEBP->"webp";AUTO->""}
    val demuxer get()=when(this){PNG->"png_pipe";JPEG->"jpeg_pipe";WEBP->"webp_pipe";AUTO->""}
}
enum class ImageAlpha { OPAQUE, PRESENT, UNKNOWN }
enum class ImageProfile { SRGB, ASSUMED_SRGB, UNSUPPORTED, UNKNOWN }
data class ImageSource(val uri: String, val name: String, val hash: String, val bytes: Long,val originalUri:String?=null) {
    fun isOriginalDestination(destination:String)=destination==uri || destination==originalUri
}
data class ImageInfo(val width: Int, val height: Int, val format: ImageFormat,
    val orientation: Int = 1, val frameCount: Int? = 1, val bitDepth: Int = 8,
    val alpha: ImageAlpha = ImageAlpha.UNKNOWN, val profile: ImageProfile = ImageProfile.ASSUMED_SRGB,
    val gainMap: Boolean = false, val hash: String = "", val bytes: Long = -1, val orientationApplied: Boolean = false, val minimumAlpha: Int? = null)
data class NormalizedCrop(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 1.0, val bottom: Double = 1.0)
data class ImageAdjustments(val brightness: Double = 0.0, val contrast: Double = 1.0, val saturation: Double = 1.0,
    val gamma: Double = 1.0, val blurSigma: Double = 0.0, val sharpenRadius: Int = 1, val sharpenAmount: Double = 0.0)
data class Rgba(val red: Int = 0, val green: Int = 0, val blue: Int = 0, val alpha: Int = 255) {
    fun ffmpeg() = "0x%02x%02x%02x@%.8f".format(java.util.Locale.ROOT, red,green,blue,alpha/255.0)
}
enum class AnnotationKind { TEXT, ARROW, LINE, RECTANGLE, ELLIPSE, REDACTION }
enum class TextAlignment { START, CENTER, END }
data class ImageAnnotation(val id: String, val kind: AnnotationKind, val left: Double = 0.1, val top: Double = 0.1,
    val right: Double = 0.7, val bottom: Double = 0.3, val text: String = "", val color: Rgba = Rgba(),
    val fill: Rgba = Rgba(0,0,0,0), val strokeWidth: Double = 3.0, val textSize: Double = 32.0,
    val alignment: TextAlignment = TextAlignment.START, val font: String = "sans-serif", val opacity: Double = 1.0)
enum class ResizeMode { ORIGINAL, PIXELS, PERCENT, LONGEST_EDGE }
enum class CanvasAnchor { TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }
data class ImageOutputPolicy(val format: ImageFormat = ImageFormat.AUTO, val jpegQuality: Int = 90, val webpQuality: Int = 80,
    val lossless: Boolean = false, val pngCompression: Int = 6, val resizeMode: ResizeMode = ResizeMode.ORIGINAL,
    val width: Int? = null, val height: Int? = null, val percent: Double = 100.0, val longestEdge: Int = 1600,
    val aspectLock: Boolean = true, val allowUpscale: Boolean = false, val allowResizeToFit: Boolean = false,
    val canvasWidth: Int? = null, val canvasHeight: Int? = null, val anchor: CanvasAnchor = CanvasAnchor.CENTER,
    val padding: Rgba = Rgba(0,0,0,0), val flatten: Rgba? = null, val targetBytes: Long? = 10_000_000,
    val stripMetadata: Boolean = true)
data class ImageEditDocument(val schema: Int = 1, val source: ImageSource, val revision: Long = 0,
    val crop: NormalizedCrop = NormalizedCrop(), val quarterTurns: Int = 0, val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false, val adjustments: ImageAdjustments = ImageAdjustments(),
    val annotations: List<ImageAnnotation> = emptyList(), val output: ImageOutputPolicy = ImageOutputPolicy(), val cropAspectRatio: Double? = null) {
    fun frozen() = copy(annotations=Collections.unmodifiableList(ArrayList(annotations)))
    fun estimatedBytes(): Long = 1024L + source.uri.length*4L + (source.originalUri?.length?:0)*4L + source.name.length*4L + annotations.sumOf { 384L+it.text.length*4L }
}
class ImageJobSpec(val id: String, document: ImageEditDocument, val info: ImageInfo? = null,
    val resolvedFormat:ImageFormat?=null, val preferences:MediaPreferences=MediaPreferences.legacy(Settings())) {
    init {require(resolvedFormat!=ImageFormat.AUTO){"Resolved image format must be concrete."}}
    val document: ImageEditDocument = document.frozen()
    fun copy(id: String = this.id, document: ImageEditDocument = this.document, info: ImageInfo? = this.info,
        resolvedFormat: ImageFormat? = this.resolvedFormat, preferences: MediaPreferences = this.preferences) =
        ImageJobSpec(id,document,info,resolvedFormat,preferences)
    override fun equals(other: Any?) = other is ImageJobSpec && id == other.id && document == other.document &&
        info == other.info && resolvedFormat==other.resolvedFormat && preferences==other.preferences
    override fun hashCode() = 31*(31*id.hashCode()+document.hashCode())+preferences.hashCode()
}
data class ImageSize(val width: Int, val height: Int)
data class ImageAttempt(val index: Int, val format: ImageFormat, val quality: Int, val scale: Double = 1.0,
    val rendererIdentity: String = "forma-image-rgb-v1", val documentHash: String = "", val markupPath: String? = null,
    val sizeOverride: ImageSize? = null)
data class ImageProblem(val code: String, val field: String?, val message: String)
class ImageFailure(val code: String, message: String) : IllegalArgumentException("$code: $message")
enum class ImageStage { STAGING, INSPECTING, PREPARING, RENDERING, ENCODING, VERIFYING, PUBLISHING }
data class ImageExportDiagnostics(val requestedFormat:ImageFormat,val effectiveFormat:ImageFormat,
    val requestedSize:ImageSize,val effectiveSize:ImageSize,val requestedQuality:Int?,val effectiveQuality:Int?,
    val attempts:Int,val bytes:Long,val alpha:ImageAlpha,val nativeBuild:String) {
    fun summary()="Requested $requestedFormat ${requestedSize.width} × ${requestedSize.height}"+
        (requestedQuality?.let{" quality $it"}?:"")+"; effective $effectiveFormat ${effectiveSize.width} × ${effectiveSize.height}"+
        (effectiveQuality?.let{" quality $it"}?:" lossless")+" · $bytes bytes · $attempts attempt(s) · ${alpha.name.lowercase()} alpha · ancillary metadata stripped · $nativeBuild"
}
data class ImageStageProgress(val stage: ImageStage, val attempt: Int = 1, val fraction: Float? = null,val diagnostics:ImageExportDiagnostics?=null)

/** The queue tag, never duration or a video setting, selects the processing path. */
sealed interface QueueJobSpec {
    val id:String
    val source:dev.forma.core.Source
    val trim:dev.forma.core.Trim
    val settings:dev.forma.core.Settings
    val preferences:MediaPreferences
    val targetBytes:Long?
    val sequence:dev.forma.core.SequenceSpec?
    val mime:String
    val extension:String
    data class Av(val job:dev.forma.core.JobSpec):QueueJobSpec {
        override val id get()=job.id
        override val source get()=job.source
        override val trim get()=job.trim
        override val settings get()=job.settings
        override val preferences get()=job.preferences
        override val targetBytes get()=job.targetBytes
        override val sequence get()=job.sequence
        override val mime get()=settings.container.mime
        override val extension get()=settings.container.extension
    }
    data class Image(val job:ImageJobSpec):QueueJobSpec {
        override val id get()=job.id
        override val source get()=dev.forma.core.Source(job.document.source.uri,job.document.source.name,0,
            job.info?.width?:0,job.info?.height?:0,bytes=job.document.source.bytes,imageInfo=job.info,imageOriginalUri=job.document.source.originalUri)
        override val trim get()=dev.forma.core.Trim()
        override val settings get()=dev.forma.core.Settings()
        override val preferences get()=job.preferences
        override val targetBytes get()=job.document.output.targetBytes
        override val sequence:dev.forma.core.SequenceSpec? get()=null
        val format get()=job.resolvedFormat?:job.document.output.format.takeIf { it!=ImageFormat.AUTO }?:if(job.info?.alpha==ImageAlpha.PRESENT)ImageFormat.PNG else ImageFormat.JPEG
        override val mime get()=format.mime
        override val extension get()=format.extension
    }
    fun copy(id:String=this.id,source:dev.forma.core.Source=this.source,trim:dev.forma.core.Trim=this.trim,
        settings:dev.forma.core.Settings=this.settings,preferences:MediaPreferences=this.preferences,
        targetBytes:Long?=this.targetBytes,sequence:dev.forma.core.SequenceSpec?=this.sequence):QueueJobSpec = when(this) {
        is Av->Av(job.copy(id=id,source=source,trim=trim,settings=settings,preferences=preferences,targetBytes=targetBytes,sequence=sequence))
        is Image->{require(source==this.source && trim==this.trim && settings==this.settings && targetBytes==this.targetBytes && sequence==null){"Use the immutable image document to edit image jobs."};Image(job.copy(id=id,preferences=preferences))}
    }
}
