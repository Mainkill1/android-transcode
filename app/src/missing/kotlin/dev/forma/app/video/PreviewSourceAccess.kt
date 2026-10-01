package dev.forma.app.video

import android.content.Context
import dev.forma.core.Source

internal suspend fun <T> withPreviewSourcePaths(context:Context,sources:List<Source>,
    block:suspend (List<String>)->T):T = error("Native video preview is unavailable in this build.")
