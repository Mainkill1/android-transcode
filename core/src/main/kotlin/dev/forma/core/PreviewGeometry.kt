package dev.forma.core

sealed interface PreviewCrop {
    data class Valid(val crop:CropRect):PreviewCrop
    data class Unsupported(val reason:String):PreviewCrop
}

/** Display pixel geometry after FFmpeg's source autorotation, before an edit's crop. */
object PreviewGeometry {
    /** FFprobe display-matrix rotation is counterclockwise. Null means unsafe/unknown. */
    fun metadataRotation(matrix:String?,reportedDegrees:Double?):Int? {
        val reported=reportedDegrees?.let { angle ->
            if(!angle.isFinite()) return null
            val normalized=((angle%360+360)%360)
            listOf(0,90,180,270).firstOrNull { kotlin.math.abs(normalized-it)<0.01 ||
                (it==0 && kotlin.math.abs(normalized-360)<0.01) } ?: return null
        }
        if(matrix==null) return reported ?: 0
        val rows=matrix.lineSequence().filter { ':' in it }.map { line ->
            line.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        }.toList()
        if(rows.size!=3 || rows.any { it.size!=3 }) return null
        val values=rows.flatten()
        val turns=mapOf(
            0 to listOf(65536,0,0,0,65536,0,0,0,1073741824),
            90 to listOf(0,-65536,0,65536,0,0,0,0,1073741824),
            180 to listOf(-65536,0,0,0,-65536,0,0,0,1073741824),
            270 to listOf(0,65536,0,-65536,0,0,0,0,1073741824))
        val parsed=turns.entries.firstOrNull { it.value==values }?.key ?: return null
        return parsed.takeIf { reported==null || reported==it }
    }

    fun displaySize(sourceWidth:Int,sourceHeight:Int,orientation:Int?):Pair<Int,Int>? {
        if(sourceWidth<=0 || sourceHeight<=0) return null
        return when(orientation) {
            0,180 -> sourceWidth to sourceHeight
            90,270 -> sourceHeight to sourceWidth
            else -> null
        }
    }

    /** Inverts the added rotate/flip display transform; export crops before those effects. */
    fun mapCrop(sourceWidth:Int,sourceHeight:Int,orientation:Int?,effects:ClipEffects,displayRect:CropRect):PreviewCrop {
        val (w,h)=displaySize(sourceWidth,sourceHeight,orientation)
            ?: return PreviewCrop.Unsupported("This source has an unsupported display transform; crop is unavailable.")
        val (finalW,finalH)=turnedSize(w,h,effects.rotation)
        if(!inside(displayRect,finalW,finalH)) return PreviewCrop.Unsupported("Crop is outside the displayed frame.")
        var r=displayRect
        if(effects.flipVertical) r=vertical(r,finalH)
        if(effects.flipHorizontal) r=horizontal(r,finalW)
        r=when(effects.rotation) {
            QuarterTurn.NONE -> r
            QuarterTurn.CLOCKWISE -> counterclockwise(r,finalW,finalH)
            QuarterTurn.HALF -> half(r,finalW,finalH)
            QuarterTurn.COUNTERCLOCKWISE -> clockwise(r,finalW,finalH)
        }
        return if(validExportCrop(r,w,h)) PreviewCrop.Valid(r)
        else PreviewCrop.Unsupported("Crop needs even, in-bounds pixels of at least 4 × 4.")
    }

    /** Maps an exported crop back onto the full quick frame for the edit overlay. */
    fun displayRect(sourceWidth:Int,sourceHeight:Int,orientation:Int?,effects:ClipEffects,crop:CropRect):PreviewCrop {
        val (w,h)=displaySize(sourceWidth,sourceHeight,orientation)
            ?: return PreviewCrop.Unsupported("This source has an unsupported display transform; crop is unavailable.")
        if(!validExportCrop(crop,w,h)) return PreviewCrop.Unsupported("Crop needs even, in-bounds pixels of at least 4 × 4.")
        var r=crop
        r=when(effects.rotation) {
            QuarterTurn.NONE -> r
            QuarterTurn.CLOCKWISE -> clockwise(r,w,h)
            QuarterTurn.HALF -> half(r,w,h)
            QuarterTurn.COUNTERCLOCKWISE -> counterclockwise(r,w,h)
        }
        val (finalW,finalH)=turnedSize(w,h,effects.rotation)
        if(effects.flipHorizontal) r=horizontal(r,finalW)
        if(effects.flipVertical) r=vertical(r,finalH)
        return PreviewCrop.Valid(r)
    }

    fun validExportCrop(crop:CropRect,width:Int,height:Int):Boolean =
        inside(crop,width,height) && crop.width>=4 && crop.height>=4 &&
            listOf(crop.x,crop.y,crop.width,crop.height).all { it%2==0 }

    /** Preserve the displayed edge parity imposed by a rotated odd-sized source. */
    fun dragDisplayCorner(rect:CropRect,corner:Int,pointerX:Int,pointerY:Int,width:Int,height:Int):CropRect {
        require(corner in 0..3)
        val x=snapParity(pointerX,width,rect.x%2)
        val y=snapParity(pointerY,height,rect.y%2)
        val right=rect.x+rect.width;val bottom=rect.y+rect.height
        return when(corner) {
            0 -> CropRect(x,y,right-x,bottom-y)
            1 -> CropRect(rect.x,y,x-rect.x,bottom-y)
            2 -> CropRect(rect.x,rect.y,x-rect.x,y-rect.y)
            else -> CropRect(x,rect.y,right-x,y-rect.y)
        }
    }

    private fun snapParity(value:Int,max:Int,parity:Int):Int {
        val clamped=value.coerceIn(0,max)
        val below=clamped-Math.floorMod(clamped-parity,2)
        return when {
            below<0 -> below+2
            below+2<=max && clamped-below>1 -> below+2
            else -> below
        }
    }

    private fun inside(r:CropRect,w:Int,h:Int):Boolean = r.x>=0 && r.y>=0 && r.width>0 && r.height>0 &&
        r.x.toLong()+r.width<=w && r.y.toLong()+r.height<=h
    private fun turnedSize(w:Int,h:Int,turn:QuarterTurn):Pair<Int,Int> = when(turn) {
        QuarterTurn.CLOCKWISE,QuarterTurn.COUNTERCLOCKWISE -> h to w
        else -> w to h
    }
    private fun clockwise(r:CropRect,w:Int,h:Int)=CropRect(h-r.y-r.height,r.x,r.height,r.width)
    private fun counterclockwise(r:CropRect,w:Int,h:Int)=CropRect(r.y,w-r.x-r.width,r.height,r.width)
    private fun half(r:CropRect,w:Int,h:Int)=CropRect(w-r.x-r.width,h-r.y-r.height,r.width,r.height)
    private fun horizontal(r:CropRect,w:Int)=r.copy(x=w-r.x-r.width)
    private fun vertical(r:CropRect,h:Int)=r.copy(y=h-r.y-r.height)
}
