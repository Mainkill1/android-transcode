package dev.forma.core.settings

import dev.forma.core.*
import java.io.File
import java.util.UUID

/** Existing queue/notification consumers. Absent values use registry defaults. */
object ConsumerSettings {
    const val DAY_MS=86_400_000L
    val boundIds=setOf("queue.auto_start_added","queue.on_error","queue.interrupted_prompt",
        "queue.notification_detail","queue.completion_sound","queue.keep_screen_on","privacy.history_days")
    fun choice(values:PreferenceValues,id:String) = ((values[id] ?: SettingCatalog[id].defaultValue) as SettingValue.Choice).value
    fun historyDays(values:PreferenceValues) = ((values["privacy.history_days"] ?: SettingCatalog["privacy.history_days"].defaultValue) as SettingValue.Integer).value.toInt()
    fun autoStart(values:PreferenceValues,idle:Boolean) = idle && choice(values,"queue.auto_start_added")=="auto"
    fun continueAfterError(values:PreferenceValues) = choice(values,"queue.on_error")=="continue"
    fun notification(values:PreferenceValues,status:String,name:String?=null):String =
        if(name!=null && choice(values,"queue.notification_detail")=="details") "$status · ${name.filter { !it.isISOControl() }.take(120)}" else status
    fun keepScreenAwake(values:PreferenceValues,visible:Boolean,encoding:Boolean) =
        visible && encoding && choice(values,"queue.keep_screen_on")=="encoding"
    fun expiredHistory(entries:List<QueueEntry>,days:Int,nowMs:Long):Set<String> {
        require(days in 0..90 && nowMs>=0)
        val age=days*DAY_MS
        return entries.filter { entry -> entry.state==JobState.COMPLETED &&
            entry.delivery.receipt !in setOf(DeliveryReceipt.Waiting) &&
            entry.delivery.receipt !is DeliveryReceipt.Copying &&
            entry.delivery.receipt !is DeliveryReceipt.Failed && entry.completedAtMs?.let {
            it>=0 && it<=nowMs && nowMs-it>=age
        }==true }.map { it.spec.id }.toSet()
    }
}

/** Private managed files use validated job IDs; source display names never form paths. */
object ManagedMediaPaths {
    fun work(root:File,id:String):File {
        require(runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false)) { "Invalid managed media identifier." }
        return File(root,id).also { require(it.canonicalFile.parentFile==root.canonicalFile) { "Invalid managed media path." } }
    }
}
