package dev.forma.core

import org.junit.Test
import org.junit.Assert.assertEquals
import dev.forma.core.settings.ConsumerSettings

class WorkPolicyTest {
    @Test fun resourcePolicy() = workPolicyChecks()
    @Test fun unfinishedPublicDeliveryProtectsVerifiedPrivateOutput() {
        val spec = JobSpec("00000000-0000-0000-0000-000000000001", Source("content://source", "clip.mp4", 1000),
            Trim(), Settings())
        val original = QueueEntry(spec, JobState.COMPLETED, completedAtMs = 0)
        val waiting = original.copy(delivery = Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO), DeliveryReceipt.Waiting))
        val failed = original.copy(delivery = Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO), DeliveryReceipt.Failed("Full", null)))
        val saved = original.copy(delivery = Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),
            DeliveryReceipt.Saved("content://media/output", "clip.mp4", 1, "a".repeat(64))))
        assertEquals(emptySet<String>(), ConsumerSettings.expiredHistory(listOf(waiting, failed), 0, 1))
        assertEquals(setOf(spec.id), ConsumerSettings.expiredHistory(listOf(saved), 0, 1))
    }
}
