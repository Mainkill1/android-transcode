package dev.forma.app.data

import android.content.Context
import android.net.Uri
import dev.forma.core.Source
import dev.forma.ffmpeg.FfmpegBridge

internal suspend fun probeDocument(context: Context, uri: Uri, bridge: FfmpegBridge): Source =
    error("Native media inspection is unavailable in this build.")
