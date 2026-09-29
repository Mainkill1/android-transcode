package dev.forma.app.image
import android.graphics.*
import android.text.*
import dev.forma.core.image.*
import java.io.File
import kotlinx.coroutines.*
data class MarkupPlane(val path:String,val layoutIdentity:String)
/** One Canvas/StaticLayout implementation is used at preview and export resolutions. */
class ImageMarkupRenderer {
    suspend fun render(document:ImageEditDocument,geometry:ImageGeometryResult,directory:File):MarkupPlane=withContext(Dispatchers.Default) {
        ImageValidation.requireValid(document);currentCoroutineContext().ensureActive()
        val size=geometry.outputSize
        val bitmap=Bitmap.createBitmap(size.width,size.height,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap);canvas.clipRect(0,0,size.width,size.height)
            val m=geometry.sourceToOutput;val matrix=Matrix().apply { setValues(floatArrayOf(m.a.toFloat(),m.c.toFloat(),m.tx.toFloat(),m.b.toFloat(),m.d.toFloat(),m.ty.toFloat(),0f,0f,1f)) }
            canvas.save()
            canvas.concat(matrix)
            val w=geometry.orientedSize.width.toFloat();val h=geometry.orientedSize.height.toFloat()
            canvas.clipRect(geometry.cropLeft.toFloat(),geometry.cropTop.toFloat(),(geometry.cropLeft+geometry.cropWidth).toFloat(),(geometry.cropTop+geometry.cropHeight).toFloat())
            val ordered=document.annotations.filter { it.kind!=AnnotationKind.REDACTION }
            for(o in ordered){currentCoroutineContext().ensureActive();draw(canvas,o,w,h)}
            canvas.restore()
            for(o in document.annotations.filter{it.kind==AnnotationKind.REDACTION}) {
                currentCoroutineContext().ensureActive()
                val r=ImageGeometry.annotationBounds(geometry,o)?:continue
                val paint=Paint().apply {style=Paint.Style.FILL;color=Color.rgb(o.color.red,o.color.green,o.color.blue);isAntiAlias=false}
                canvas.drawRect(r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat(),paint)
            }
            val output=File(directory,"markup.png")
            output.outputStream().use { out->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)){"Markup plane could not be stored."} }
            MarkupPlane(output.path,"android-static-layout-v1:${document.annotations.hashCode()}:${size.width}x${size.height}")
        }finally{bitmap.recycle()}
    }
    private fun color(c:Rgba,opacity:Double=1.0)=Color.argb((c.alpha*opacity).toInt().coerceIn(0,255),c.red,c.green,c.blue)
    private fun draw(canvas:Canvas,o:ImageAnnotation,w:Float,h:Float) {
        val r=RectF((o.left*w).toFloat(),(o.top*h).toFloat(),(o.right*w).toFloat(),(o.bottom*h).toFloat())
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=color(o.color,o.opacity);strokeWidth=o.strokeWidth.toFloat();style=Paint.Style.STROKE }
        when(o.kind){
            AnnotationKind.REDACTION->{paint.isAntiAlias=false;paint.style=Paint.Style.FILL;paint.color=Color.rgb(o.color.red,o.color.green,o.color.blue)
                canvas.drawRect(kotlin.math.floor(r.left.toDouble()).toFloat(),kotlin.math.floor(r.top.toDouble()).toFloat(),kotlin.math.ceil(r.right.toDouble()).toFloat(),kotlin.math.ceil(r.bottom.toDouble()).toFloat(),paint)}
            AnnotationKind.RECTANGLE,AnnotationKind.ELLIPSE->{
                val fill=Paint(paint).apply {color=color(o.fill,o.opacity);style=Paint.Style.FILL}
                if(o.kind==AnnotationKind.RECTANGLE){canvas.drawRect(r,fill);if(o.strokeWidth>0)canvas.drawRect(r,paint)}else{canvas.drawOval(r,fill);if(o.strokeWidth>0)canvas.drawOval(r,paint)}
            }
            AnnotationKind.LINE,AnnotationKind.ARROW->{
                canvas.drawLine(r.left,r.top,r.right,r.bottom,paint)
                if(o.kind==AnnotationKind.ARROW){val angle=kotlin.math.atan2((r.bottom-r.top).toDouble(),(r.right-r.left).toDouble());val head=kotlin.math.max(o.strokeWidth*4,10.0)
                    for(delta in listOf(-.5,.5))canvas.drawLine(r.right,r.bottom,(r.right-head*kotlin.math.cos(angle+delta)).toFloat(),(r.bottom-head*kotlin.math.sin(angle+delta)).toFloat(),paint)}
            }
            AnnotationKind.TEXT->{
                val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {color=color(o.color,o.opacity);textSize=o.textSize.toFloat();typeface=Typeface.create(o.font,Typeface.NORMAL)}
                val align=when(o.alignment){TextAlignment.START->Layout.Alignment.ALIGN_NORMAL;TextAlignment.CENTER->Layout.Alignment.ALIGN_CENTER;TextAlignment.END->Layout.Alignment.ALIGN_OPPOSITE}
                val layout=StaticLayout.Builder.obtain(o.text,0,o.text.length,text,r.width().toInt().coerceAtLeast(1)).setAlignment(align).setIncludePad(false).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).build()
                canvas.save();canvas.clipRect(r);canvas.translate(r.left,r.top);layout.draw(canvas);canvas.restore()
            }
        }
    }
}
