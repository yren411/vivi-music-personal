package com.music.vivi.playback.effects

/** Public Android control-session events, owned by real outputs rather than a primary-player flag. */
class AudioEffectSessions(private val emit: (Event) -> Unit) {
    enum class Role { PRIMARY, PREPARED, FADING }
    enum class Kind { OPEN, CLOSE, PROMOTE, RELEASE }
    data class Event(val kind: Kind, val deck: String, val sessionId: Int, val role: Role)
    private data class Output(var role: Role, var session: Int = 0)
    private val outputs = linkedMapOf<String, Output>()

    fun register(deck: String, role: Role) { check(deck !in outputs); outputs[deck] = Output(role) }
    fun session(deck: String) = outputs[deck]?.session ?: 0

    /** Prepared/paused AudioTracks retain attachment. Invalid IDs and stopped outputs do not. */
    fun update(deck: String, sessionId: Int, prepared: Boolean) {
        val output = outputs[deck] ?: return
        val next = if (prepared && sessionId > 0) sessionId else 0
        val previous = output.session
        if (previous == next) return
        val alreadyOwned = next > 0 && outputs.values.any { it.session == next }
        output.session = next
        // Open replacement FIRST, then close the exact previous ID if no output still owns it.
        if (next > 0 && !alreadyOwned) emit(Event(Kind.OPEN, deck, next, output.role))
        if (previous > 0 && outputs.values.none { it.session == previous }) emit(Event(Kind.CLOSE, deck, previous, output.role))
    }

    fun promote(deck: String) {
        val incoming = outputs[deck] ?: return
        outputs.values.filter { it.role == Role.PRIMARY }.forEach { it.role = Role.FADING }
        incoming.role = Role.PRIMARY
        emit(Event(Kind.PROMOTE, deck, incoming.session, incoming.role))
    }

    fun release(deck: String) {
        val output = outputs[deck] ?: return
        update(deck, 0, false)
        outputs.remove(deck)
        emit(Event(Kind.RELEASE, deck, output.session, output.role))
    }
    fun closeAll() { outputs.keys.toList().forEach(::release) }
}
