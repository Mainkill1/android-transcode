package dev.forma.app.data

import dev.forma.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes public copying independently of native encode ownership. */
class DeliveryWorker(private val queue:QueueRepository,private val files:MediaFiles,
    private val publisher:PublicOutputPublisher) {
    private val mutex=Mutex()

    suspend fun resumePending()=mutex.withLock {
        queue.entries.value.filter { it.state==JobState.COMPLETED &&
            (it.delivery.receipt==DeliveryReceipt.Waiting || it.delivery.receipt is DeliveryReceipt.Copying) }
            .forEach { deliver(it) }
    }

    suspend fun retry(id:String)=mutex.withLock {
        val entry=queue.entries.value.first { it.spec.id==id && it.state==JobState.COMPLETED }
        val failed=entry.delivery.receipt as? DeliveryReceipt.Failed ?: error("This save is not waiting for a retry.")
        val retained=failed.uri
        require(retained==null || publisher.clearRetainedPartial(entry,retained)) {
            "The partial file could not be removed from this folder. Remove it there before retrying."
        }
        val destination=requireNotNull(entry.delivery.destination)
        check(queue.updateDelivery(id,failed,Delivery(destination,DeliveryReceipt.Waiting))) { "Save state changed." }
        deliver(queue.entries.value.first { it.spec.id==id })
    }

    private suspend fun deliver(entry:QueueEntry) {
        try { publisher.publish(entry,files.output(entry.spec)) }
        catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            val message=error.message ?: "Could not save output."
            val latest=queue.entries.value.firstOrNull { it.spec.id==entry.spec.id }
            if(latest?.delivery?.receipt==DeliveryReceipt.Waiting && latest.delivery.destination!=null)
                queue.updateDelivery(entry.spec.id,DeliveryReceipt.Waiting,
                    Delivery(latest.delivery.destination,DeliveryReceipt.Failed(message.take(500),null)))
            queue.error.value="${entry.spec.source.name}: $message"
        }
    }
}
