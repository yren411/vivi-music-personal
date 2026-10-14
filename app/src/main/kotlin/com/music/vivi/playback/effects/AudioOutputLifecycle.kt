package com.music.vivi.playback.effects

/** Media3's actual AudioTrack callbacks arm session notifications. READY alone is insufficient. */
class AudioOutputLifecycle(private val sessions: AudioEffectSessions, private val deck: String) {
    private var sessionId = 0
    private var tracks = 0
    private var disposed = false
    fun sessionChanged(id: Int) {
        if(disposed) return
        sessionId=id
        sessions.update(deck,id,tracks>0)
    }
    fun trackInitialized(id: Int) {
        if(disposed) return
        tracks++
        sessionChanged(id)
    }
    fun trackReleased() {
        if(disposed) return
        tracks=(tracks-1).coerceAtLeast(0)
        // An old AudioTrack may finish asynchronous release AFTER its replacement initializes.
        // It must not close the replacement's session.
        sessions.update(deck,sessionId,tracks>0)
    }
    fun dispose() {
        if(disposed) return
        disposed=true; tracks=0
        sessions.release(deck)
    }
}
