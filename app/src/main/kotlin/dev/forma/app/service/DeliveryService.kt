package dev.forma.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import dev.forma.app.FormaApplication
import dev.forma.app.MainActivity
import dev.forma.app.R
import dev.forma.core.DeliveryReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns public-copy work after the editor leaves the screen. The queue journal survives service loss. */
class DeliveryService : Service() {
    private val graph get()=(application as FormaApplication).graph
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var activeRequests=0
    private var latestStartId=0

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Saving converted media",NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action!=SAVE) return START_NOT_STICKY
        try {
            val type=when {
                Build.VERSION.SDK_INT>=35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                Build.VERSION.SDK_INT>=29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            }
            if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notification(),type)
            else startForeground(NOTIFICATION,notification())
        } catch(error:Exception) {
            val message="Android could not keep the save active. Tap Retry save. ${error.message.orEmpty()}"
            graph.queue.error.value=message
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        activeRequests++
        latestStartId=startId
        val id=intent.getStringExtra(JOB_ID)
        scope.launch {
            try {
                graph.initialize()
                if(id==null) graph.deliveries.resumePending()
                else when(graph.queue.entries.value.firstOrNull { it.spec.id==id }?.delivery?.receipt) {
                    is DeliveryReceipt.Failed -> graph.deliveries.retry(id)
                    DeliveryReceipt.Waiting,is DeliveryReceipt.Copying -> graph.deliveries.resumePending()
                    else -> Unit
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) { graph.queue.error.value=error.message ?: "Could not save converted media." }
            finally { requestFinished() }
        }
        return START_REDELIVER_INTENT
    }

    private fun requestFinished() {
        activeRequests--
        if(activeRequests==0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
        }
    }

    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_forma)
            .setContentTitle("Saving converted media").setContentText("Copying to the chosen folder")
            .setContentIntent(open).setOngoing(true).build()
    }

    override fun onDestroy() { scope.cancel();super.onDestroy() }

    companion object {
        private const val SAVE="dev.forma.app.SAVE_DELIVERY"
        private const val JOB_ID="job_id"
        private const val CHANNEL="forma_save"
        private const val NOTIFICATION=132
        fun start(context:Context,id:String?=null) {
            ContextCompat.startForegroundService(context,Intent(context,DeliveryService::class.java)
                .setAction(SAVE).apply { if(id!=null) putExtra(JOB_ID,id) })
        }
    }
}
