package com.music.vivi.playback.effects

import org.junit.Assert.*
import org.junit.Test
import com.music.vivi.playback.effects.AudioEffectSessions.*

class AudioEffectSessionsTest {
    private class Fixture {
        val events = mutableListOf<Event>()
        val registry = AudioEffectSessions(events::add)
        fun add(deck: String, id: Int, role: Role = Role.PREPARED) {
            registry.register(deck, role); registry.update(deck, id, true)
        }
        fun actions() = events.filter { it.kind in listOf(Kind.OPEN, Kind.CLOSE) }.map { it.kind to it.sessionId }
    }
    @Test fun normalPreparedPlaybackPauseResumeAndSeekDoNotChurnTheLiveSession() {
        val f=Fixture(); f.add("primary", 11, Role.PRIMARY)
        repeat(4) { f.registry.update("primary",11,true) }
        assertEquals(listOf(Kind.OPEN to 11),f.actions())
        f.registry.release("primary")
        assertEquals(listOf(Kind.OPEN to 11,Kind.CLOSE to 11),f.actions())
    }
    @Test fun automaticManualCrossfadeAndAutoMixKeepBothSessionsThroughPromotion() {
        for (reason in listOf("automatic","manual","automix")) {
            val f=Fixture(); f.add("old",21,Role.PRIMARY); f.add(reason,22)
            f.registry.promote(reason)
            assertEquals(listOf(Kind.OPEN to 21,Kind.OPEN to 22),f.actions())
            assertEquals(22,f.registry.session(reason))
            f.registry.release("old")
            assertEquals(listOf(Kind.OPEN to 21,Kind.OPEN to 22,Kind.CLOSE to 21),f.actions())
            f.registry.update(reason,22,true)
            assertEquals(3,f.actions().size)
            f.registry.closeAll(); assertEquals(Kind.CLOSE to 22,f.actions().last())
        }
    }
    @Test fun routeOrAudioTrackReplacementOpensNewIdBeforeClosingTheOldOne() {
        val f=Fixture(); f.add("deck",31)
        f.registry.update("deck",32,true)
        assertEquals(listOf(Kind.OPEN to 31,Kind.OPEN to 32,Kind.CLOSE to 31),f.actions())
        f.registry.release("deck"); assertEquals(Kind.CLOSE to 32,f.actions().last())
    }
    @Test fun invalidIdAndStoppedOrFailedOutputsNeverOpenAControlSession() {
        val f=Fixture(); f.add("deck",0)
        f.registry.update("deck",-1,true); f.registry.update("deck",42,false)
        assertTrue(f.actions().isEmpty())
        f.registry.update("deck",42,true); f.registry.update("deck",42,false)
        assertEquals(listOf(Kind.OPEN to 42,Kind.CLOSE to 42),f.actions())
    }
    @Test fun canceledPreparationAndRapidSkippingReleaseOnlyTheirOwnIds() {
        val f=Fixture(); f.add("current",51,Role.PRIMARY)
        repeat(8) { n -> f.add("candidate$n",60+n); f.registry.release("candidate$n") }
        assertEquals(51,f.registry.session("current"))
        assertFalse(f.actions().contains(Kind.CLOSE to 51))
        f.registry.closeAll(); f.registry.closeAll()
        assertEquals(1,f.actions().count { it==Kind.CLOSE to 51 })
        for(id in 60..67) assertEquals(listOf(Kind.OPEN to id,Kind.CLOSE to id),f.actions().filter { it.second==id })
    }
    @Test fun unexpectedSharedIdsAreReferenceCountedWithoutForcingPlayersToShare() {
        val f=Fixture(); f.add("a",71); f.add("b",71)
        f.registry.release("a"); assertEquals(listOf(Kind.OPEN to 71),f.actions())
        f.registry.release("b"); assertEquals(listOf(Kind.OPEN to 71,Kind.CLOSE to 71),f.actions())
    }
    @Test fun duplicateCallbacksAndReleasedDeckCallbacksCannotResurrectSessions() {
        val f=Fixture(); f.add("a",81); f.registry.release("a"); f.registry.release("a")
        f.registry.update("a",82,true); f.registry.promote("a")
        assertEquals(listOf(Kind.OPEN to 81,Kind.CLOSE to 81),f.actions())
    }
}
