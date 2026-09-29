package dev.forma.app.audio
import dev.forma.core.*
import dev.forma.core.audio.*
import org.junit.Assert.*
import org.junit.Test

class AudioEditorStateTest {
    private fun gain(db:Double)=AudioEdit(nodes=listOf(AudioEffectNode("g","gain",parameters=GainParameters(db))))
    @Test fun draggingMakesOneUndoStepAndNewEditClearsRedo() {
        val history=AudioEditHistory()
        history.update(gain(-1.0),false);history.update(gain(-3.0),false);history.update(gain(-6.0),true)
        assertEquals(gain(-6.0),history.current)
        assertEquals(AudioEdit(),history.undo())
        assertEquals(gain(-6.0),history.redo())
        history.undo();history.update(gain(2.0),true);assertFalse(history.canRedo)
    }
    @Test fun staleRenderCannotReplaceNewerSettings() {
        val state=AudioPreviewState(identity="new",status="Updating")
        assertEquals(state,state.accept("old","old.wav"))
        assertEquals("Rendered preview",state.accept("new","new.wav").status)
    }
    @Test fun encodingPresetPreservesAudioEdits() {
        val current=Settings(audioEdit=gain(-6.0))
        assertEquals(current.audioEdit,AudioEditorSettings.preset(current,Goal.AUDIO,Quality.SMALL).audioEdit)
    }

    @Test fun legacyStereoChangesInvalidatePreview() {
        assertNotEquals(AudioEditorSettings.previewKey(null,Settings(stereo=true)),AudioEditorSettings.previewKey(null,Settings(stereo=false)))
    }

    @Test fun encodingChangesThatAffectPreviewClockInvalidateItsIdentity() {
        assertNotEquals(AudioEditorSettings.previewKey(null,Settings(audio=AudioEncoder.OPUS)),AudioEditorSettings.previewKey(null,Settings(audio=AudioEncoder.FLAC)))
    }
}
