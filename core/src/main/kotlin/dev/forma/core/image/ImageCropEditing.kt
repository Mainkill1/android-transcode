package dev.forma.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Handle movement is expressed in the upright original, independently of display transforms. */
object ImageCropEditing {
    fun drag(c:NormalizedCrop,corner:Int,p:ImagePoint,size:ImageSize,ratio:Double?):NormalizedCrop {
        require(corner in 0..3)
        val left=corner==0 || corner==3;val top=corner<2
        val ax=if(left)c.right else c.left;val ay=if(top)c.bottom else c.top
        val x=p.x.coerceIn(0.0,1.0);val y=p.y.coerceIn(0.0,1.0)
        val px:Double;val py:Double
        if(ratio==null) {
            px=if(left)min(x,ax-1.0/size.width) else max(x,ax+1.0/size.width)
            py=if(top)min(y,ay-1.0/size.height) else max(y,ay+1.0/size.height)
        } else {
            require(ratio.isFinite() && ratio>0)
            val availableX=(if(left)ax else 1-ax)*size.width
            val availableY=(if(top)ay else 1-ay)*size.height
            val wanted=max(abs(x-ax)*size.width,abs(y-ay)*size.height*ratio)
            val width=min(max(wanted,max(1.0,ratio)),min(availableX,availableY*ratio))
            px=ax+(if(left)-1 else 1)*width/size.width
            py=ay+(if(top)-1 else 1)*width/ratio/size.height
        }
        return NormalizedCrop(if(left)px else ax,if(top)py else ay,if(left)ax else px,if(top)ay else py)
    }
    fun resizeHeight(p:ImageOutputPolicy,height:Int)=p.copy(width=if(p.aspectLock)null else p.width,height=height,allowResizeToFit=false)
}
