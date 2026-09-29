package dev.forma.core.image
import kotlin.math.*
data class ImagePoint(val x: Double, val y: Double)
data class ImageMatrix(val a: Double=1.0,val b: Double=0.0,val c: Double=0.0,val d: Double=1.0,val tx: Double=0.0,val ty: Double=0.0) {
    fun map(p: ImagePoint)=ImagePoint(a*p.x+c*p.y+tx,b*p.x+d*p.y+ty)
    /** Applies this transform, then next. */
    fun then(n:ImageMatrix)=ImageMatrix(n.a*a+n.c*b,n.b*a+n.d*b,n.a*c+n.c*d,n.b*c+n.d*d,n.a*tx+n.c*ty+n.tx,n.b*tx+n.d*ty+n.ty)
    fun inverse():ImageMatrix { val det=a*d-b*c;require(det!=0.0);return ImageMatrix(d/det,-b/det,-c/det,a/det,(c*ty-d*tx)/det,(b*tx-a*ty)/det) }
}
data class ImageGeometryResult(val orientedSize:ImageSize,val cropLeft:Int,val cropTop:Int,val cropWidth:Int,val cropHeight:Int,
    val contentSize:ImageSize,val outputSize:ImageSize,val padX:Int,val padY:Int,val sourceToOutput:ImageMatrix,val encodedToOutput:ImageMatrix,
    val filters:List<String>) { val outputToSource get()=sourceToOutput.inverse() }
object ImageGeometry {
    private fun rounded(v:Double)=floor(v+.5).toInt().coerceAtLeast(1)
    fun orientation(info:ImageInfo):Pair<ImageSize,ImageMatrix> {
        val w=info.width.toDouble();val h=info.height.toDouble();val o=if(info.orientationApplied)1 else info.orientation
        val m=when(o) {
            1->ImageMatrix();2->ImageMatrix(-1.0,0.0,0.0,1.0,w,0.0);3->ImageMatrix(-1.0,0.0,0.0,-1.0,w,h);4->ImageMatrix(1.0,0.0,0.0,-1.0,0.0,h)
            5->ImageMatrix(0.0,1.0,1.0,0.0);6->ImageMatrix(0.0,1.0,-1.0,0.0,h,0.0);7->ImageMatrix(0.0,-1.0,-1.0,0.0,h,w);8->ImageMatrix(0.0,-1.0,1.0,0.0,0.0,w)
            else->throw ImageFailure("UNSUPPORTED_IMAGE","Unknown EXIF orientation.")
        }
        return (if(o>=5)ImageSize(info.height,info.width) else ImageSize(info.width,info.height)) to m
    }
    fun resolve(info:ImageInfo,d:ImageEditDocument,attempt:ImageAttempt):ImageGeometryResult {
        ImageValidation.requireValid(d);ImageValidation.requireSupported(info)
        val (upright,orient)=orientation(info)
        val left=floor(d.crop.left*upright.width).toInt();val top=floor(d.crop.top*upright.height).toInt()
        val right=ceil(d.crop.right*upright.width).toInt();val bottom=ceil(d.crop.bottom*upright.height).toInt()
        if(left<0 || top<0 || right>upright.width || bottom>upright.height || right<=left || bottom<=top)throw ImageFailure("INVALID_DOCUMENT","Crop is outside the oriented source.")
        val cw=right-left;val ch=bottom-top;var w=cw;var h=ch
        var matrix=ImageMatrix(tx=-left.toDouble(),ty=-top.toDouble())
        val filters=mutableListOf<String>()
        if(!info.orientationApplied)filters+=when(info.orientation){2->listOf("hflip");3->listOf("hflip","vflip");4->listOf("vflip");5->listOf("transpose=clock","hflip");6->listOf("transpose=clock");7->listOf("transpose=clock","vflip");8->listOf("transpose=cclock");else->emptyList()}
        if(left!=0 || top!=0 || cw!=upright.width || ch!=upright.height)filters+="crop=$cw:$ch:$left:$top:exact=1"
        repeat(d.quarterTurns){ matrix=matrix.then(ImageMatrix(0.0,1.0,-1.0,0.0,h.toDouble(),0.0));val old=w;w=h;h=old;filters+="transpose=clock" }
        if(d.flipHorizontal){matrix=matrix.then(ImageMatrix(-1.0,0.0,0.0,1.0,w.toDouble(),0.0));filters+="hflip"}
        if(d.flipVertical){matrix=matrix.then(ImageMatrix(1.0,0.0,0.0,-1.0,0.0,h.toDouble()));filters+="vflip"}
        val p=d.output
        var nw=w;var nh=h
        when(p.resizeMode){
            ResizeMode.ORIGINAL->Unit
            ResizeMode.PERCENT->{nw=rounded(w*p.percent/100);nh=rounded(h*p.percent/100)}
            ResizeMode.LONGEST_EDGE->{val s=p.longestEdge.toDouble()/max(w,h);nw=rounded(w*s);nh=rounded(h*s)}
            ResizeMode.PIXELS->{
                if(p.aspectLock){val s=if(p.width!=null)p.width.toDouble()/w else p.height!!.toDouble()/h;nw=rounded(w*s);nh=rounded(h*s)}
                else {nw=p.width?:w;nh=p.height?:h}
            }
        }
        if(!p.allowUpscale && (nw>w || nh>h))throw ImageFailure("INVALID_DOCUMENT","Enable Allow upscale to enlarge this image.")
        nw=rounded(nw*attempt.scale);nh=rounded(nh*attempt.scale)
        attempt.sizeOverride?.let { nw=it.width;nh=it.height }
        if(nw!=w || nh!=h) {
            filters+=listOf("format=gbrap","premultiply=inplace=1","scale=$nw:$nh:flags=lanczos","unpremultiply=inplace=1")
            matrix=matrix.then(ImageMatrix(nw.toDouble()/w,0.0,0.0,nh.toDouble()/h))
        }
        val ow=if(attempt.sizeOverride!=null)nw else p.canvasWidth?.let { rounded(it*attempt.scale) }?:nw
        val oh=if(attempt.sizeOverride!=null)nh else p.canvasHeight?.let { rounded(it*attempt.scale) }?:nh
        if(ow<nw || oh<nh || ow.toLong()*oh>ImageValidation.MAX_PIXELS)throw ImageFailure("RESOURCE_LIMIT","Canvas must contain the image and remain below the pixel ceiling.")
        val column=p.anchor.ordinal%3;val row=p.anchor.ordinal/3
        val px=when(column){0->0;1->(ow-nw)/2;else->ow-nw};val py=when(row){0->0;1->(oh-nh)/2;else->oh-nh}
        if(ow!=nw || oh!=nh){filters+="pad=$ow:$oh:$px:$py:color=${p.padding.ffmpeg()}";matrix=matrix.then(ImageMatrix(tx=px.toDouble(),ty=py.toDouble()))}
        return ImageGeometryResult(upright,left,top,cw,ch,ImageSize(nw,nh),ImageSize(ow,oh),px,py,matrix,orient.then(matrix),filters)
    }
}
