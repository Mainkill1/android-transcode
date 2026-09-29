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

    @Test fun audioImportPreservesSeededDefaultsAndTheAudioGraph() {
        val current=Settings(audioKbps=128,stereo=false,audioEdit=gain(-6.0))
        assertEquals(current.copy(container=Container.M4A),AudioEditorSettings.audioImport(current))
        val wav=current.copy(container=Container.WAV,audio=AudioEncoder.PCM_F32LE)
        assertEquals(wav,AudioEditorSettings.audioImport(wav))
        assertEquals(current.copy(container=Container.FLAC,audio=AudioEncoder.FLAC),AudioEditorSettings.audioImport(current.copy(container=Container.MKV,audio=AudioEncoder.FLAC)))
        assertEquals(wav,AudioEditorSettings.audioImport(wav.copy(container=Container.MKV)))
    }

    @Test fun firstUndoRestoresSeededSourceRouting() {
        val original=AudioEdit(output=AudioOutputPolicy(channels=ChannelMode.SOURCE))
        val history=AudioEditHistory()
        history.resetBaseline(original)
        assertFalse(history.canUndo)
        history.update(original.copy(nodes=gain(-6.0).nodes),true)
        assertEquals(original,history.undo())
    }

    @Test fun stereoControlUsesEffectiveRoutingAndPreservesEffects() {
        val source=Settings(stereo=true,audioEdit=gain(-6.0).copy(output=AudioOutputPolicy(channels=ChannelMode.SOURCE)))
        assertFalse(AudioEditorSettings.stereoEnabled(source))
        val stereo=AudioEditorSettings.withStereo(source,true)
        assertTrue(AudioEditorSettings.stereoEnabled(stereo))
        assertEquals(source.audioEdit.nodes,stereo.audioEdit.nodes)
        assertEquals(ChannelMode.STEREO,stereo.audioEdit.output.channels)
        assertEquals(ChannelMode.SOURCE,AudioEditorSettings.withStereo(stereo,false).audioEdit.output.channels)
    }

    @Test fun unsupportedAudioDefaultRequiresExplicitOutputChoice() {
        val current=Settings(container=Container.WEBM,video=VideoEncoder.VP9,audio=AudioEncoder.OPUS)
        assertEquals(current,AudioEditorSettings.audioImport(current))
        assertNotNull(AudioEditorSettings.audioImportProblem(current))
        assertNull(AudioEditorSettings.audioImportProblem(current.copy(container=Container.WAV,audio=AudioEncoder.PCM_S16LE)))
    }

    @Test fun legacyStereoChangesInvalidatePreview() {
        assertNotEquals(AudioEditorSettings.previewKey(null,Settings(stereo=true)),AudioEditorSettings.previewKey(null,Settings(stereo=false)))
    }

    @Test fun encodingChangesThatAffectPreviewClockInvalidateItsIdentity() {
        assertNotEquals(AudioEditorSettings.previewKey(null,Settings(audio=AudioEncoder.OPUS)),AudioEditorSettings.previewKey(null,Settings(audio=AudioEncoder.FLAC)))
    }
}
