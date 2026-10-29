package com.cragnet.wysawyg

import org.junit.Assert.*
import org.junit.Test

class RecordingSessionTest {
    @Test fun navigationAndHiddenKeyboardLeaveTheSameCaptureAndStopAvailable() {
        val recording = RecordingSession(RecordingPhase.CAPTURING, "same-session", 42)
        var state = recording
        repeat(10) { state = state.editorChanged() }
        assertEquals(recording, state)
        assertTrue(state.canStop)
        assertTrue(state.keepsIndicator)
        assertFalse(state.canStart(eligible = false, recoveryReady = true))
    }

    @Test fun onlyEligibleIdleSessionCanStartAndRepeatedStopIsIdempotent() {
        assertFalse(RecordingSession().canStart(false, true))
        assertFalse(RecordingSession().canStart(true, false))
        assertTrue(RecordingSession().canStart(true, true))
        val stopped = RecordingSession(RecordingPhase.CAPTURING, "one").stop()
        assertEquals(RecordingPhase.TRANSCRIBING, stopped.phase)
        assertEquals(stopped, stopped.stop())
        assertFalse(stopped.canStop)
        assertFalse(stopped.canStart(true, true))
    }

    @Test fun interruptionKeepsIdentityAndNeverRestartsWhenAnEditorReturns() {
        val interrupted = RecordingSession(RecordingPhase.CAPTURING, "one").interrupted("OS interruption")
        assertEquals("one", interrupted.id)
        assertEquals(RecordingPhase.INTERRUPTED, interrupted.editorChanged().phase)
        assertFalse(interrupted.canStart(true, true))
        assertTrue(interrupted.keepsIndicator)
        assertEquals(interrupted, interrupted.stop())
    }
}
