fun main() {
    val count = dev.forma.core.EditorChecks.run() + dev.forma.core.TimelineChecks.run()
    println("Editor checks: $count passed")
}
