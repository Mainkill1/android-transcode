package dev.forma.core.image
import java.util.Locale
import kotlin.math.*
data class ImageEffectGraph(val filters:List<String>,val requiredFilters:Set<String>,val alphaPolicy:String="straight RGB correction; premultiplied spatial operations")
object ImageEffects {
    private fun n(v:Double)=String.format(Locale.ROOT,"%.12f",v)
    fun pixel(c:Rgba,a:ImageAdjustments):Rgba {
        val rgb=listOf(c.red,c.green,c.blue).map { (((it/255.0-.5)*a.contrast+.5+a.brightness).coerceIn(0.0,1.0)).pow(1/a.gamma) }
        val y=.2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2]
        val v=rgb.map { floor((y+a.saturation*(it-y)).coerceIn(0.0,1.0)*255+.5).toInt() }
        return Rgba(v[0],v[1],v[2],c.alpha)
    }
    fun compile(a:ImageAdjustments,geometry:ImageGeometryResult?=null,proxyScale:Double=1.0):ImageEffectGraph {
        val filters=mutableListOf<String>()
        if(a.brightness!=0.0 || a.contrast!=1.0 || a.gamma!=1.0) {
            val expr="pow(clip((val/255-0.5)*${n(a.contrast)}+0.5+${n(a.brightness)},0,1),${n(1/a.gamma)})*255"
            filters+="lutrgb=r='$expr':g='$expr':b='$expr'"
        }
        if(a.saturation!=1.0){val s=a.saturation;val r=.2126*(1-s);val g=.7152*(1-s);val b=.0722*(1-s)
            filters+="colorchannelmixer=rr=${n(r+s)}:rg=${n(g)}:rb=${n(b)}:gr=${n(r)}:gg=${n(g+s)}:gb=${n(b)}:br=${n(r)}:bg=${n(g)}:bb=${n(b+s)}"
        }
        if(a.blurSigma>0 || a.sharpenAmount>0){
            filters+="premultiply=inplace=1"
            if(a.blurSigma>0)filters+="gblur=sigma=${n(a.blurSigma*proxyScale)}:planes=15"
            if(a.sharpenAmount>0){val k=2*a.sharpenRadius+1;filters+="unsharp=luma_msize_x=$k:luma_msize_y=$k:luma_amount=${n(a.sharpenAmount)}:chroma_msize_x=$k:chroma_msize_y=$k:chroma_amount=${n(a.sharpenAmount)}:alpha_msize_x=$k:alpha_msize_y=$k:alpha_amount=${n(a.sharpenAmount)}"}
            filters+="unpremultiply=inplace=1"
        }
        return ImageEffectGraph(filters,filters.map { it.substringBefore('=') }.toSet())
    }
}
