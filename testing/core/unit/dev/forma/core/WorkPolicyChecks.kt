package dev.forma.core

fun workPolicyChecks() {
    var checks = 0
    fun expect(test: Boolean) { check(test); checks++ }
    expect(WorkPolicy.encodeThreads(1) == 1)
    expect(WorkPolicy.encodeThreads(2) == 1)
    expect(WorkPolicy.encodeThreads(4) == 3)
    expect(WorkPolicy.encodeThreads(32) == 4)
    expect(WorkPolicy.fraction(-1, 1000) == 0f)
    expect(WorkPolicy.fraction(1000, 1000) == .99f)
    expect(WorkPolicy.fraction(100, 0) == null)
    val args = listOf("-n", "-i", "/file with spaces.mp4", "-c:v", "libx264", "-f", "mp4", "/out.mp4")
    val limited = WorkPolicy.withThreadBudget(args, 8)
    expect(limited.last() == args.last())
    expect(limited.windowed(2).count { it == listOf("-threads:v", "4") } == 2)
    expect(limited.indexOf("-threads:v") < limited.indexOf("-i"))
    expect(limited.windowed(2).any { it == listOf("-filter_threads", "2") })
    expect(limited.windowed(2).any { it == listOf("-i", "/file with spaces.mp4") })
    var rejected = false
    try { WorkPolicy.withThreadBudget(listOf("-threads:v", "0") + args, 8) } catch (_: IllegalArgumentException) { rejected = true }
    expect(rejected)
    println("$checks work policy checks passed")
}
fun main() = workPolicyChecks()
