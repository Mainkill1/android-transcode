package dev.forma.core

import org.junit.Test

class EditorTest {
    @Test fun effects() { EditorChecks.run() }
    @Test fun timeline() { TimelineChecks.run() }
}
