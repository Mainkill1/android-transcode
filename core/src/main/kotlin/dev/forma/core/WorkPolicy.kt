package dev.forma.core

/** Resource policy, not a guarantee about vendor codec pools or device frame times. */
object WorkPolicy {
    const val UI_PROGRESS_MS = 250L
    const val NOTIFICATION_MS = 1000L
    fun encodeThreads(processors: Int): Int = (processors - 1).coerceIn(1, 4)
    fun fraction(processedMs: Long, durationMs: Long): Float? = if (durationMs <= 0) null else
        (processedMs.toDouble() / durationMs).coerceIn(0.0, 0.99).toFloat()

    /** Input options precede -i; output options precede the output URL. No shell. */
    fun withThreadBudget(arguments: List<String>, processors: Int): List<String> {
        require(arguments.count { it == "-i" } == 1 && arguments.size >= 4)
        return budget(arguments,processors)
    }
    /** Image markup adds one private input; both decoder pools stay bounded. */
    fun withImageThreadBudget(arguments:List<String>,processors:Int):List<String> {
        require(arguments.count{it=="-i"} in 1..2 && arguments.size>=4)
        return budget(arguments,processors)
    }
    private fun budget(arguments:List<String>,processors:Int):List<String> {
        require(arguments.none { it.substringBefore(':') in setOf("-threads", "-filter_threads", "-filter_complex_threads") })
        val threads = encodeThreads(processors).toString()
        val outputThreads = if ("-c:v" in arguments) listOf("-threads:v", threads) else emptyList()
        return listOf("-filter_threads", "2", "-filter_complex_threads", "2") +
            arguments.dropLast(1).flatMap { if(it=="-i")listOf("-threads:v",threads,it) else listOf(it) } +
            outputThreads + arguments.last()
    }
}
