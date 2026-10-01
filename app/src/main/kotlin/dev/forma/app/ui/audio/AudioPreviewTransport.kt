@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui.audio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import dev.forma.app.ui.*
import dev.forma.app.ui.FormaButton as Button
import dev.forma.app.ui.FormaOutlinedButton as OutlinedButton
import dev.forma.ffmpeg.audio.AudioPreviewResult
import kotlinx.coroutines.delay
import kotlin.math.pow

/** Monitoring never mutates the graph. Every ExoPlayer call stays on the main thread. */
@Composable internal fun AudioPreviewTransport(result:AudioPreviewResult) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    val player=remember(result.identity) { ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.DEFAULT,true);setHandleAudioBecomingNoisy(true)
    } }
    var mode by remember(result.identity) { mutableStateOf("Rendered") }
    var playing by remember { mutableStateOf(false) }
    var matching by remember { mutableStateOf(false) }
    var position by remember(result.identity) { mutableLongStateOf(0) }
    var error by remember(result.identity) { mutableStateOf<String?>(null) }
    fun play(which:String) {
        val where=if(player.mediaItemCount>0)player.currentPosition else 0
        mode=which
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(if(which=="Original")result.original else result.rendered)))
        player.prepare();player.seekTo(where.coerceIn(0,result.durationMs));player.play()
    }
    DisposableEffect(player,lifecycle) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(isPlaying:Boolean) { playing=isPlaying }
            override fun onPlayerError(e:PlaybackException) { error=e.message ?: "Could not play preview." }
        }
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP)player.pause() }
        player.addListener(listener);lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);player.removeListener(listener);player.release() }
    }
    LaunchedEffect(player) { while(true) { position=player.currentPosition;delay(200) } }
    LaunchedEffect(matching,mode) {
        val current=if(mode=="Original")result.originalWaveform.rmsDb else result.waveform.rmsDb
        val reference=listOfNotNull(result.originalWaveform.rmsDb,result.waveform.rmsDb).minOrNull()
        player.volume=if(matching && current!=null && reference!=null)10.0.pow((reference-current)/20).toFloat().coerceIn(0f,1f) else 1f
    }
    Text("Preview · first ${mediaTime(result.durationMs)}",style=MaterialTheme.typography.labelSmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={play("Original")}) { Text("Play original") }
        Button(onClick={play("Rendered")}) { Text("Play edited") }
        OutlinedButton(onClick={if(playing)player.pause() else if(player.mediaItemCount>0)player.play() else play(mode)}) { Text(if(playing)"Pause" else "Resume") }
    }
    Text("$mode · ${mediaTime(position)} / ${mediaTime(result.durationMs)}",style=MaterialTheme.typography.labelMedium)
    Slider(value=position.toFloat().coerceIn(0f,result.durationMs.toFloat()),onValueChange={player.seekTo(it.toLong())},valueRange=0f..result.durationMs.toFloat(),modifier=Modifier.formaTouchTarget())
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Text("Match listening level",Modifier.weight(1f));Switch(matching,{matching=it},modifier=Modifier.formaTouchTarget()) }
    if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
}
