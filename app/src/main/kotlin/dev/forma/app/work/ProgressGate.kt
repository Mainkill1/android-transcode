package dev.forma.app.work

/** Drops intermediate native callbacks, with no callback-sized coroutine or unbounded buffer. */
class ProgressGate(private val intervalMs: Long, private val clockMs: () -> Long) {
    init { require(intervalMs > 0) }
    private var key: String? = null
    private var last = Long.MIN_VALUE
    @Synchronized fun accept(job: String): Boolean {
        val now = clockMs()
        if (key != job || last == Long.MIN_VALUE || now < last || now - last >= intervalMs) {
            key = job
            last = now
            return true
        }
        return false
    }
}
