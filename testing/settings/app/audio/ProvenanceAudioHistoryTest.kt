package dev.forma.app.audio

import dev.forma.core.audio.*
import dev.forma.core.settings.SettingValue
import org.junit.Assert.*
import org.junit.Test

class ProvenanceAudioHistoryTest {
    @Test fun undoAndRedoRestoreChannelInheritanceAsWellAsTheAudioGraph() {
        val history=AudioEditHistory()
        val source=AudioEdit(output=AudioOutputPolicy(channels=ChannelMode.SOURCE))
        val mono=source.copy(output=source.output.copy(channels=ChannelMode.MONO))
        history.resetBaseline(source,null)
        history.update(mono,true,SettingValue.Choice("mono"))
        assertEquals(source,history.undo())
        assertNull(history.currentChannelOverride)
        assertEquals(mono,history.redo())
        assertEquals(SettingValue.Choice("mono"),history.currentChannelOverride)
    }
    @Test fun equalExplicitChannelIsAnUndoableOriginChange() {
        val history=AudioEditHistory()
        val source=AudioEdit(output=AudioOutputPolicy(channels=ChannelMode.SOURCE))
        history.resetBaseline(source,null)
        history.update(source,true,SettingValue.Choice("source"))
        assertTrue(history.canUndo)
        history.undo()
        assertNull(history.currentChannelOverride)
        history.redo()
        assertEquals(SettingValue.Choice("source"),history.currentChannelOverride)
    }
}
