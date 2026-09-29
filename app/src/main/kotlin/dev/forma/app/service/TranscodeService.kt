package dev.forma.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.forma.app.FormaApplication
import dev.forma.app.MainActivity
import dev.forma.app.R
import dev.forma.app.data.LiveProgress
import dev.forma.app.work.*
import dev.forma.core.*
import dev.forma.core.image.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** User-started media work. Activity recreation does not stop it; service loss does. */
class TranscodeService : Service() {
    private val graph get() = (application as FormaApplication).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticket: RunCoordinator.Ticket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val uiGate = ProgressGate(WorkPolicy.UI_PROGRESS_MS, SystemClock::elapsedRealtime)
    private val notificationGate = ProgressGate(WorkPolicy.NOTIFICATION_MS, SystemClock::elapsedRealtime)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { graph.runs.stop(); if (ticket == null) stopSelfResult(startId); return START_NOT_STICKY }
            FINISH_CURRENT -> { graph.runs.finishCurrent(); if (ticket == null) stopSelfResult(startId); return START_NOT_STICKY }
            START -> Unit
            else -> { stopSelfResult(startId); return START_NOT_STICKY }
        }
        if (ticket?.job?.isCompleted == false) return START_NOT_STICKY
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        try {
            val initial = notification("Preparing queue", null)
            // ServiceCompat's API 34 mask strips MEDIA_PROCESSING even on API 35+.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, initial, type)
            else startForeground(NOTIFICATION, initial)
        }
        catch (error: Exception) {
            graph.queue.error.value = "Android could not start background conversion. Return to the app and try again. ${error.message.orEmpty()}"
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val awake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dev.forma.transcode:conversion")
        try {
            awake.setReferenceCounted(false)
            awake.acquire(6L * 60 * 60 * 1000) // bounded safety timeout, never keeps the screen on
        } catch (error: Exception) {
            graph.queue.error.value = "Could not keep background processing awake: ${error.message}"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val started = graph.runs.start { run -> graph.previews.cancelAndJoin(); graph.imagePreviews.cancelAndJoin(); process(run) }
        if (started == null) {
            releaseWake(awake)
            graph.queue.error.value = "The previous conversion is still stopping. Its files are being released."
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        ticket = started
        wakeLock = awake
        // Observe in the service, not the UI. A hidden Activity is not a worker subscription.
        scope.launch {
            val status = launch {
                graph.runs.state.collect { state ->
                    if (state.id == started.id && state.mode == RunMode.STOPPING) notify("Stopping safely…", null)
                    if (state.id == started.id && state.mode == RunMode.DRAINING) notify("Finishing current file; remaining jobs will wait", null)
                }
            }
            try {
                started.job.join()
                started.failure?.let { graph.queue.error.value = it }
            } finally {
                status.cancel()
                releaseWake(awake)
                if (ticket === started) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun process(run: RunCoordinator.Ticket) {
        var active: QueueJobSpec? = null
        var completed = 0
        try {
            graph.initialize()
            while (currentCoroutineContext().isActive && run.canTakeNext()) {
                // Retain ownership even if cancellation races a successful disk transaction.
                withContext(NonCancellable) { active = graph.queue.claimNext() }
                val spec = active ?: break
                currentCoroutineContext().ensureActive()
                try {
                    notify("Preparing ${spec.source.name}", null)
                    if (spec is QueueJobSpec.Image) {
                        var diagnostics:ImageExportDiagnostics?=null
                        graph.imageTranscoder.run(spec.job, { state ->
                            graph.queue.transition(spec.id, state,if(state==JobState.COMPLETED)diagnostics?.summary().orEmpty() else "")
                            graph.queue.progress.value = null
                            notify(if (state == JobState.COMPLETED) "Ready: ${spec.source.name}" else "Processing ${spec.source.name}", null)
                        }, { stage ->
                            stage.diagnostics?.let { diagnostics=it }
                            graph.queue.progress.value = LiveProgress(spec.id, image = stage)
                            if (notificationGate.accept(spec.id)) notify("${stage.stage.name.lowercase().replaceFirstChar { it.uppercase() }} ${spec.source.name} · attempt ${stage.attempt}", stage.fraction)
                        })
                    } else graph.transcoder.run((spec as QueueJobSpec.Av).job, { state ->
                        graph.queue.transition(spec.id, state)
                        graph.queue.progress.value = null
                        notify(when (state) { JobState.VERIFYING -> "Checking ${spec.source.name}"; JobState.COMPLETED -> "Ready: ${spec.source.name}"; else -> "Converting ${spec.source.name}" }, null)
                    }, { progress ->
                        if (uiGate.accept(spec.id)) graph.queue.progress.value = LiveProgress(spec.id, progress)
                        if (notificationGate.accept(spec.id)) {
                            val status = when (graph.runs.state.value.mode) {
                                RunMode.DRAINING -> "Finishing current: ${spec.source.name}"
                                RunMode.STOPPING -> "Stopping safely…"
                                else -> "Converting ${spec.source.name}"
                            }
                            notify(status, WorkPolicy.fraction(progress.processedMs, Planner.duration(spec.source, spec.trim)))
                        }
                    })
                    completed++
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: LinkageError) { graph.queue.transition(spec.id, JobState.FAILED, "The native encoder could not load: ${error.message}") }
                catch (error: Exception) { graph.queue.transition(spec.id, JobState.FAILED, error.message ?: "Conversion failed.") }
                finally { graph.queue.progress.value = null }
                active = null
            }
            if (completed > 0) {
                val waiting = graph.queue.entries.value.count { it.state == JobState.QUEUED }
                completion("$completed file(s) ready" + if (waiting > 0) " · $waiting waiting" else "")
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                active?.let { spec ->
                    if (graph.queue.entries.value.any { it.spec.id == spec.id && it.state in ACTIVE }) {
                        val reason = run.stopReason
                        graph.queue.transition(spec.id, if (reason == StopReason.USER) JobState.CANCELLED else JobState.INTERRUPTED,
                            when (reason) {
                                StopReason.USER -> "Cancelled. Other queued files are still waiting."
                                StopReason.TIME_LIMIT -> "Android's background time allowance ended. Restart from the app."
                                else -> "Processing was interrupted. Restart this job explicitly."
                            })
                    }
                }
            }
            throw cancel
        } finally { graph.queue.progress.value = null }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ticket?.let { graph.runs.stop(it.id, StopReason.TIME_LIMIT) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWake(wakeLock)
        stopSelf() // Android requires prompt stop; coordinator still owns native cleanup.
    }
    override fun onDestroy() {
        ticket?.let { graph.runs.stop(it.id, StopReason.SERVICE_STOPPED) }
        scope.cancel()
        releaseWake(wakeLock)
        super.onDestroy()
    }
    private fun releaseWake(wake: PowerManager.WakeLock?) {
        if (wake?.isHeld == true) runCatching { wake.release() }
    }
    private fun openIntent() = PendingIntent.getActivity(this, 1,
        Intent(this, MainActivity::class.java).putExtra("open_queue", true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(text: String, fraction: Float?): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, TranscodeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val finish = PendingIntent.getService(this, 3, Intent(this, TranscodeService::class.java).setAction(FINISH_CURRENT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_forma)
            .setContentTitle("Forma · media conversion").setContentText(text).setContentIntent(openIntent())
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .addAction(0, "Finish current", finish).addAction(0, "Stop", stop).build()
    }
    private fun allowed() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    // Publishing notification updates is not part of the output transaction. A
    // revoked permission or system notification failure must not fail an encode.
    private fun notify(text: String, fraction: Float?) = postNotification {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text, fraction))
    }
    private fun completion(text: String) = postNotification {
        getSystemService(NotificationManager::class.java).notify(COMPLETION,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_forma)
                .setContentTitle("Forma · ready to share").setContentText(text).setContentIntent(openIntent())
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
    }
    private fun postNotification(action: () -> Unit) {
        try { if (allowed()) action() }
        catch (error: RuntimeException) { Log.w("FormaService", "Notification update was unavailable", error) }
    }
    companion object {
        const val START = "dev.forma.START_QUEUE"
        const val STOP = "dev.forma.STOP_QUEUE"
        const val FINISH_CURRENT = "dev.forma.FINISH_CURRENT"
        private const val CHANNEL = "transcoding"
        private const val NOTIFICATION = 1
        private const val COMPLETION = 2
        private val ACTIVE = setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING)
    }
}
