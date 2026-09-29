package dev.forma.app.work

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class WorkRuntimeTest {
    @Test fun completedWorkerRetainsSlotUntilStatePublication() = runBlocking {
        withTimeout(5000) { completionPublicationChecks() }
    }
    @Test fun cancellationAndProgressRaces() = runBlocking { withTimeout(5000) { workRuntimeChecks() } }
}
