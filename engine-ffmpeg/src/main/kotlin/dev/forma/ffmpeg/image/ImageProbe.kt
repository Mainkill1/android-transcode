package dev.forma.ffmpeg.image
import dev.forma.core.image.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/** Header facts are independent of file extension/MIME and of Android preview decoders. */
object ImageProbe {
    fun hash(file:File):String { val md=MessageDigest.getInstance("SHA-256");file.inputStream().use { input -> val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)} };return md.digest().joinToString(""){"%02x".format(it)} }
    fun inspect(localPath:String):ImageInfo {
        val file=File(localPath)
        if(!file.isFile || file.length()==0L)throw ImageFailure("DECODE_FAILED","Image is empty or missing.")
        if(file.length()>64L*1024*1024)throw ImageFailure("RESOURCE_LIMIT","Encoded image exceeds the 64 MiB inspection budget.")
        return inspectBytes(file.readBytes()).copy(hash=hash(file),bytes=file.length())
    }
    fun inspectBytes(b:ByteArray):ImageInfo {
        fun malformed():Nothing=throw ImageFailure("DECODE_FAILED","Truncated or damaged image header.")
        fun unsupported(reason:String):Nothing=throw ImageFailure("UNSUPPORTED_IMAGE",reason)
        fun checkRange(i:Int,n:Int){if(i<0 || n<0 || i.toLong()+n>b.size)malformed()}
        fun u(i:Int):Int {checkRange(i,1);return b[i].toInt() and 255}
        fun be16(i:Int)=u(i)*256+u(i+1)
        fun be32(i:Int):Long=(u(i).toLong() shl 24)+(u(i+1).toLong() shl 16)+(u(i+2).toLong() shl 8)+u(i+3)
        fun le32(i:Int):Long=u(i).toLong()+(u(i+1).toLong() shl 8)+(u(i+2).toLong() shl 16)+(u(i+3).toLong() shl 24)
        fun text(i:Int,n:Int):String{checkRange(i,n);return String(b,i,n,Charsets.ISO_8859_1)}
        fun exif(i:Int,n:Int):Pair<Int,Boolean> {
            checkRange(i,n);var t=i;if(n>=6 && text(i,6)=="Exif\u0000\u0000")t+=6
            if(t+8>i+n)return 1 to false
            val little=text(t,2)=="II";if(!little && text(t,2)!="MM")malformed()
            fun s(x:Int)=if(little)u(x)+u(x+1)*256 else be16(x)
            fun l(x:Int)=if(little)le32(x) else be32(x)
            if(s(t+2)!=42)malformed()
            val offset=l(t+4);if(offset>n-2)malformed();val dir=t+offset.toInt();val count=s(dir)
            if(count>1024 || dir.toLong()+2+count*12L>i+n)malformed()
            var o=1;var bad=false
            repeat(count){j->val p=dir+2+j*12;val tag=s(p);if(tag==0x112){if(s(p+2)!=3 || l(p+4)!=1L)malformed();o=s(p+8)};if(tag==0xA001 && s(p+8)!=1)bad=true}
            return o to bad
        }
        val info=when {
            b.size>=8 && b.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))->{
                var i=8;var w=0;var h=0;var depth=0;var alpha=ImageAlpha.UNKNOWN;var profile=ImageProfile.ASSUMED_SRGB;var orientation=1;var end=false;var data=false
                while(i<b.size){checkRange(i,12);val length=be32(i);if(length>Int.MAX_VALUE)malformed();val n=length.toInt();checkRange(i+8,n+4);val type=text(i+4,4)
                    val crc=CRC32();crc.update(b,i+4,n+4);if(crc.value!=be32(i+8+n))malformed()
                    when(type){
                        "IHDR"->{if(i!=8 || n!=13)malformed();w=be32(i+8).toInt();h=be32(i+12).toInt();depth=u(i+16);val color=u(i+17);if(color !in setOf(0,2,3,4,6))unsupported("Unsupported PNG color.");alpha=if(color==4 || color==6)ImageAlpha.PRESENT else ImageAlpha.OPAQUE}
                        "acTL","fcTL","fdAT"->unsupported("Animated PNG is Planned; select a still image.")
                        "iCCP"->unsupported("Embedded ICC conversion is not qualified; use an 8-bit sRGB copy.")
                        "sRGB"->{if(n!=1 || u(i+8)>3)malformed();profile=ImageProfile.SRGB}
                        "cICP"->unsupported("Wide gamut/HDR PNG is not qualified.")
                        "tRNS"->alpha=ImageAlpha.PRESENT
                        "eXIf"->{val (o,bad)=exif(i+8,n);orientation=o;if(bad)unsupported("Non-sRGB EXIF image.")}
                        "IDAT"->data=true
                        "IEND"->{if(n!=0)malformed();end=true;if(i+12!=b.size)malformed()}
                    };i+=12+n
                }
                if(!end || !data || w<=0 || h<=0)malformed()
                ImageInfo(w,h,ImageFormat.PNG,orientation,1,depth,alpha,profile)
            }
            b.size>=2 && u(0)==255 && u(1)==216->{
                var i=2;var w=0;var h=0;var depth=0;var components=0;var orientation=1;var frames=0;var eoi=false
                while(i<b.size){if(u(i)!=255)malformed();while(i<b.size && u(i)==255)i++;val marker=u(i++);if(marker==217){eoi=true;if(i!=b.size)unsupported("Multiple-image JPEG/MPO is Planned.");break};if(marker==216)unsupported("Multiple-image JPEG.");if(marker==1 || marker in 208..215)continue
                    val n=be16(i);if(n<2)malformed();checkRange(i,n)
                    when(marker){
                        in 192..195,in 197..199,in 201..203,in 205..207->{frames++;depth=u(i+2);h=be16(i+3);w=be16(i+5);components=u(i+7)}
                        225->{val t=text(i+2,n-2);if(t.startsWith("Exif\u0000\u0000")){val(o,bad)=exif(i+2,n-2);orientation=o;if(bad)unsupported("Non-sRGB EXIF image.")};if(t.contains("hdrgm",true) || t.contains("GainMap",true))unsupported("Ultra HDR gain maps are Planned.")}
                        226->{val t=text(i+2,n-2);if(t.startsWith("MPF"))unsupported("MPO/multiple-image JPEG is Planned.");if(t.startsWith("ICC_PROFILE"))unsupported("ICC conversion is not qualified.")}
                        238->{if(components==4 || text(i+2,n-2).startsWith("Adobe"))unsupported("Adobe/CMYK JPEG is not qualified.")}
                    }
                    i+=n
                    if(marker==218){while(i<b.size-1){if(u(i)==255 && u(i+1)!=0 && u(i+1) !in 208..215)break;i++}}
                }
                if(!eoi || frames!=1 || w<=0 || h<=0)malformed();if(components !in 1..3)unsupported("CMYK JPEG is Planned.")
                ImageInfo(w,h,ImageFormat.JPEG,orientation,1,depth,ImageAlpha.OPAQUE)
            }
            b.size>=12 && text(0,4)=="RIFF" && text(8,4)=="WEBP"->{
                if(le32(4)+8!=b.size.toLong())malformed()
                var i=12;var w=0;var h=0;var alpha=false;var frames=0;var orientation=1
                while(i<b.size){checkRange(i,8);val type=text(i,4);val length=le32(i+4);if(length>Int.MAX_VALUE)malformed();val n=length.toInt();checkRange(i+8,n)
                    when(type){
                        "VP8X"->{if(n!=10)malformed();val flags=u(i+8);if(flags and 2!=0)unsupported("Animated WebP is Planned.");alpha=flags and 16!=0;w=u(i+12)+u(i+13)*256+u(i+14)*65536+1;h=u(i+15)+u(i+16)*256+u(i+17)*65536+1}
                        "ANIM","ANMF"->unsupported("Animated WebP is Planned.")
                        "ICCP"->unsupported("ICC WebP conversion is not qualified.")
                        "EXIF"->{val(o,bad)=exif(i+8,n);orientation=o;if(bad)unsupported("Non-sRGB WebP.")}
                        "VP8 "->{frames++;if(n<10 || u(i+11)!=0x9d || u(i+12)!=1 || u(i+13)!=0x2a)malformed();if(w==0){w=(u(i+14)+u(i+15)*256) and 0x3fff;h=(u(i+16)+u(i+17)*256) and 0x3fff}}
                        "VP8L"->{frames++;if(n<5 || u(i+8)!=47)malformed();val bits=le32(i+9);if(w==0){w=((bits and 0x3fff)+1).toInt();h=(((bits shr 14) and 0x3fff)+1).toInt()};alpha=alpha || bits and (1L shl 28)!=0L}
                        "ALPH"->alpha=true
                    };i+=8+n+(n and 1)
                }
                if(frames!=1 || w<=0 || h<=0)malformed()
                ImageInfo(w,h,ImageFormat.WEBP,orientation,1,8,if(alpha)ImageAlpha.PRESENT else ImageAlpha.OPAQUE)
            }
            else->unsupported("Choose a JPEG, PNG or single-frame WebP. Animation, RAW and multipage formats are Planned.")
        }
        ImageValidation.requireSupported(info);return info
    }
}
