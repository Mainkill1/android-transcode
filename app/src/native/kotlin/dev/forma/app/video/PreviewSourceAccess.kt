package dev.forma.app.video

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import dev.forma.core.Source
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep SAF registrations alive until the managed native encode has returned. */
internal suspend fun <T> withPreviewSourcePaths(context:Context,sources:List<Source>,
    block:suspend (List<String>)->T):T {
    val registered=mutableListOf<String>()
    val paths=mutableMapOf<String,String>()
    try {
        val inputs=sources.map {source ->
            currentCoroutineContext().ensureActive()
            paths.getOrPut(source.uri) {
                val uri=Uri.parse(source.uri)
                when(uri.scheme) {
                    "file" -> requireNotNull(uri.path) {"The source path is unavailable. Reselect this video."}
                    "content" -> FFmpegKitConfig.getSafParameterForRead(context,uri,true).also {url ->
                        require(url.isNotBlank()) {"The source cannot be opened for preview. Reselect this video."}
                        registered+=url
                    }
                    else -> error("Reselect this video through the system picker.")
                }
            }
        }
        return block(inputs)
    } finally {
        registered.forEach {FFmpegKitConfig.unregisterSafProtocolUrl(it)}
    }
}
