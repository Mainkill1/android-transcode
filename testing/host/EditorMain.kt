fun main() {
    val count = dev.forma.core.EditorChecks.run() + dev.forma.core.TimelineChecks.run()
    dev.forma.core.SequenceChecks.runAll()
    dev.forma.core.ProjectChecks.runAll()
    println("Editor checks: $count passed; sequence ${dev.forma.core.SequenceChecks.cases.size}, project ${dev.forma.core.ProjectChecks.cases.size} passed")
}
