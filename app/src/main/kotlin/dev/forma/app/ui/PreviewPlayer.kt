package dev.forma.app.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import dev.forma.app.video.VideoRenderState
import kotlinx.coroutines.delay

/** Screen-owned Media3 playback; a stale edit disposes the player with its surface. */
@Composable fun VideoPreviewPlayer(render:VideoRenderState.Ready) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val player=remember(render.file.path) {ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT,true)
        setHandleAudioBecomingNoisy(true)
        setMediaItem(MediaItem.fromUri(Uri.fromFile(render.file)))
        repeatMode=Player.REPEAT_MODE_ONE
        prepare();playWhenReady=true
    }}
    var playing by remember(render.file.path) {mutableStateOf(false)}
    var position by remember(render.file.path) {mutableLongStateOf(0)}
    var error by remember(render.file.path) {mutableStateOf<String?>(null)}
    DisposableEffect(player,lifecycle) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(isPlaying:Boolean) {playing=isPlaying}
            override fun onPlayerError(failure:PlaybackException) {error=failure.message ?: "Could not play preview."}
        }
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP)player.pause()}
        player.addListener(listener);lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);player.removeListener(listener);player.release()}
    }
    LaunchedEffect(player) {while(true) {position=player.currentPosition;delay(200)}}
    Column(Modifier.fillMaxWidth().testTag("rendered-video-preview")) {
        Text("Rendered preview",style=MaterialTheme.typography.labelMedium)
        AndroidView(factory={SurfaceView(it).also(player::setVideoSurfaceView)},
            modifier=Modifier.fillMaxWidth().height(260.dp))
        Row {
            FormaTextButton(onClick={if(playing)player.pause() else player.play()}) {Text(if(playing)"Pause preview" else "Play preview")}
            Text(mediaTime(position),modifier=Modifier.padding(top=12.dp),style=MaterialTheme.typography.labelMedium)
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}
