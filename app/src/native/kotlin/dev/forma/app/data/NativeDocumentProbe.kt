package dev.forma.app.data

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import dev.forma.core.Source
import dev.forma.ffmpeg.FfmpegBridge

/** FFmpegKit's SAF protocol opens the provider URI itself, including providers whose
 * descriptors cannot be reopened through /proc/self/fd. The reusable URL is always
 * unregistered after the single managed probe. */
internal suspend fun probeDocument(context: Context, uri: Uri, bridge: FfmpegBridge): Source {
    val safUrl = FFmpegKitConfig.getSafParameterForRead(context, uri, true)
    require(safUrl.isNotBlank()) { "The selected document cannot be inspected on this device." }
    try {
        return bridge.probe(safUrl)
    } finally {
        FFmpegKitConfig.unregisterSafProtocolUrl(safUrl)
    }
}
