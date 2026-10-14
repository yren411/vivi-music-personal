package com.music.vivi.playback.effects

import org.junit.Assert.*
import org.junit.Test
import com.music.vivi.playback.effects.AudioEffectSessions.*

class AudioOutputLifecycleTest {
    private class Fixture {
        val events=mutableListOf<Event>()
        val sessions=AudioEffectSessions(events::add)
        init { sessions.register("output",Role.PREPARED) }
        val output=AudioOutputLifecycle(sessions,"output")
        fun actions()=events.filter { it.kind in listOf(Kind.OPEN,Kind.CLOSE) }.map { it.kind to it.sessionId }
    }
    @Test fun allocatedIdAndReadinessCannotOpenBeforeAnAudioTrackExists() {
        val f=Fixture(); f.output.sessionChanged(101)
        assertTrue(f.actions().isEmpty())
        f.output.trackInitialized(101)
        assertEquals(listOf(Kind.OPEN to 101),f.actions())
        f.sessions.promote("output"); f.output.sessionChanged(101)
        assertEquals(1,f.actions().size)
    }
    @Test fun asynchronousOldTrackReleaseCannotCloseAReplacementOutput() {
        val f=Fixture(); f.output.trackInitialized(111); f.output.trackInitialized(112)
        assertEquals(listOf(Kind.OPEN to 111,Kind.OPEN to 112,Kind.CLOSE to 111),f.actions())
        f.output.trackReleased(); assertEquals(112,f.sessions.session("output"))
        f.output.trackReleased(); assertEquals(Kind.CLOSE to 112,f.actions().last())
    }
    @Test fun routeReleaseAndRecreationOpenAndCloseOnlyRealOutputs() {
        val f=Fixture(); f.output.trackInitialized(121); f.output.trackReleased()
        f.output.sessionChanged(122); assertEquals(2,f.actions().size)
        f.output.trackInitialized(122)
        assertEquals(listOf(Kind.OPEN to 121,Kind.CLOSE to 121,Kind.OPEN to 122),f.actions())
    }
    @Test fun errorCancellationAndServiceDestructionCannotResurrectAReleasedOutput() {
        val f=Fixture(); f.output.trackInitialized(131); f.output.dispose(); f.output.dispose()
        f.output.sessionChanged(132); f.output.trackInitialized(132); f.output.trackReleased()
        assertEquals(listOf(Kind.OPEN to 131,Kind.CLOSE to 131),f.actions())
    }
}
