package dev.forma.core.image

import java.util.Collections

enum class ImageFormat(val extension: String, val mime: String, val encoder: String) {
    AUTO("", "image/*", ""), JPEG("jpg", "image/jpeg", "mjpeg"), PNG("png", "image/png", "png"), WEBP("webp", "image/webp", "libwebp")
}
enum class ImageAlpha { OPAQUE, PRESENT, UNKNOWN }
enum class ImageProfile { SRGB, ASSUMED_SRGB, UNSUPPORTED, UNKNOWN }
data class ImageSource(val uri: String, val name: String, val hash: String, val bytes: Long)
data class ImageInfo(val width: Int, val height: Int, val format: ImageFormat,
    val orientation: Int = 1, val frameCount: Int? = 1, val bitDepth: Int = 8,
    val alpha: ImageAlpha = ImageAlpha.UNKNOWN, val profile: ImageProfile = ImageProfile.ASSUMED_SRGB,
    val gainMap: Boolean = false, val hash: String = "", val bytes: Long = -1, val orientationApplied: Boolean = false)
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
    val annotations: List<ImageAnnotation> = emptyList(), val output: ImageOutputPolicy = ImageOutputPolicy()) {
    fun frozen() = copy(annotations=Collections.unmodifiableList(ArrayList(annotations)))
    fun estimatedBytes(): Long = 1024L + source.uri.length*4L + source.name.length*4L + annotations.sumOf { 384L+it.text.length*4L }
}
class ImageJobSpec(val id: String, document: ImageEditDocument, val info: ImageInfo? = null) {
    val document: ImageEditDocument = document.frozen()
    fun copy(id: String = this.id) = ImageJobSpec(id,document,info)
    override fun equals(other: Any?) = other is ImageJobSpec && id == other.id && document == other.document && info == other.info
    override fun hashCode() = 31*id.hashCode()+document.hashCode()
}
data class ImageSize(val width: Int, val height: Int)
data class ImageAttempt(val index: Int, val format: ImageFormat, val quality: Int, val scale: Double = 1.0,
    val rendererIdentity: String = "forma-image-rgb-v1", val documentHash: String = "", val markupPath: String? = null,
    val sizeOverride: ImageSize? = null)
data class ImageProblem(val code: String, val field: String?, val message: String)
class ImageFailure(val code: String, message: String) : IllegalArgumentException("$code: $message")
enum class ImageStage { STAGING, INSPECTING, PREPARING, RENDERING, ENCODING, VERIFYING, PUBLISHING }
data class ImageStageProgress(val stage: ImageStage, val attempt: Int = 1, val fraction: Float? = null)
