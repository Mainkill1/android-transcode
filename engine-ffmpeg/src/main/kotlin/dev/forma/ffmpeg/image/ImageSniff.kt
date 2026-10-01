package dev.forma.ffmpeg.image
import java.io.InputStream
object ImageSniff {
    fun isImage(input:InputStream,checkActive:()->Unit={}):Boolean {
        val b=ByteArray(12);var n=0
        while(n<b.size){checkActive();val count=input.read(b,n,b.size-n);if(count<0)break;if(count==0)continue;n+=count}
        return (n>=2 && b[0]==(-1).toByte() && b[1]==(-40).toByte()) ||
            (n>=8 && b[0]==(-119).toByte() && b[1]==80.toByte()) ||
            (n>=6 && String(b,0,3)=="GIF") || (n>=12 && String(b,0,4)=="RIFF" && String(b,8,4)=="WEBP")
    }
}
