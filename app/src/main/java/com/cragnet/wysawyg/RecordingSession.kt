package com.cragnet.wysawyg

/** Editor eligibility controls Start only. UI lifecycle never changes the session phase. */
enum class RecordingPhase { IDLE, STARTING, CAPTURING, TRANSCRIBING, INTERRUPTED }

data class RecordingSession(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val id: String? = null,
    val startedAt: Long = 0,
    val message: String = ""
) {
    fun canStart(eligible: Boolean, recoveryReady: Boolean) =
        phase == RecordingPhase.IDLE && eligible && recoveryReady
    val canStop: Boolean get() = phase == RecordingPhase.CAPTURING
    val keepsIndicator: Boolean get() = phase != RecordingPhase.IDLE
    fun editorChanged(): RecordingSession = this
    fun stop(): RecordingSession = if (canStop) copy(phase = RecordingPhase.TRANSCRIBING) else this
    fun interrupted(reason: String): RecordingSession =
        if (phase == RecordingPhase.IDLE) this else copy(phase = RecordingPhase.INTERRUPTED, message = reason)
}
