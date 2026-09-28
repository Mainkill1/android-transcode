package dev.forma.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.forma.app.FormaApplication
import dev.forma.app.MainActivity
import dev.forma.app.R
import dev.forma.app.data.LiveProgress
import dev.forma.core.*
import kotlinx.coroutines.*

/** Started by an explicit visible UI action. Never auto-restarts a half-written encode. */
class TranscodeService : Service() {
    private val graph get() = (application as FormaApplication).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    @Volatile private var interruptedState = JobState.INTERRUPTED
    @Volatile private var interruptedMessage = "Android stopped processing. Restart this file explicitly."
    private var lastNotification = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            interruptedState = JobState.CANCELLED
            interruptedMessage = "Cancelled by you. Other queued files were left waiting."
            runner?.cancel()
            if (runner == null) stopSelf()
            return START_NOT_STICKY
        }
        if (runner?.isCompleted == false) return START_NOT_STICKY
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        try { ServiceCompat.startForeground(this, NOTIFICATION, notification("Preparing queue", null), type) }
        catch (e: Exception) {
            graph.queue.error.value = "Android did not allow background processing: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        interruptedState = JobState.INTERRUPTED
        interruptedMessage = "Android stopped processing. Restart this file explicitly."
        runner = scope.launch(Dispatchers.IO) {
            var active: JobSpec? = null
            try {
                graph.ready.await()
                while (isActive) {
                    // Capture ownership even if cancellation races the repository dispatcher return.
                    withContext(NonCancellable) { active = graph.queue.claimNext() }
                    val spec = active ?: break
                    try {
                        ensureActive()
                        graph.transcoder.run(spec, { graph.queue.transition(spec.id, it) }, { progress ->
                            graph.queue.progress.value = LiveProgress(spec.id, progress)
                            updateNotification(spec.source.name, progress.fraction(Planner.duration(spec.source, spec.trim)))
                        })
                        graph.queue.transition(spec.id, JobState.COMPLETED, "Verified output is ready to open or export.")
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        graph.queue.transition(spec.id, JobState.FAILED, e.message ?: "Conversion failed.")
                    } finally { graph.queue.progress.value = null }
                    active = null
                }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) {
                    active?.let { spec ->
                        if (graph.queue.entries.value.any { it.spec.id == spec.id && it.state in setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING) })
                            graph.queue.transition(spec.id, interruptedState, interruptedMessage)
                    }
                }
                throw cancel
            }
            catch (e: Exception) { graph.queue.error.value = "Queue processing stopped: ${e.message}" }
            finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        interruptedState = JobState.INTERRUPTED
        interruptedMessage = "Android's media-processing time allowance was exhausted. Restart from the app."
        runner?.cancel()
        stopSelf()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun notification(text: String, fraction: Float?): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, TranscodeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_forma)
            .setContentTitle("Forma · converting media").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .addAction(0, "Stop queue", stop).build()
    }
    private fun updateNotification(text: String, fraction: Float?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotification < 1000) return
        lastNotification = now
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text, fraction))
    }
    companion object {
        const val START = "dev.forma.START_QUEUE"
        const val STOP = "dev.forma.STOP_QUEUE"
        private const val CHANNEL = "transcoding"
        private const val NOTIFICATION = 1
    }
}
