package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AutoMixControllerTest {
    private class Deck(override var trackId: String) : MixDeck {
        override var positionMs=0L
        override var ready=true
        override var playing=true
        override var ended=false
        override var rate=1f
        var gain=1.0
        var bass=0.0
        var released=false
        var releaseCount=0
        override fun play() { if (!released) playing=true }
        override fun pause() { playing=false }
        override fun process(gain:Double,bassCut:Double,trebleCut:Double) { this.gain=gain; bass=bassCut }
        override fun release() { if (!released) { released=true; releaseCount++; playing=false; ready=false } }
    }
    private class Host : AutoMixHost {
        override var allowed=true
        val a=Deck("a")
        val b=Deck("b").apply { playing=false; gain=0.0 }
        var active=a
        var queue="a,b,c"
        var prepares=0
        var promotions=0
        var restored=0
        var analysisFailures=false
        override fun snapshot()=MixSnapshot(MixTrack(active.trackId,60000,active.trackId),
            MixTrack(if(active===a) "b" else "c",60000,if(active===a) "b" else "c"),
            "$queue:${active.trackId}",active.positionMs,active.playing,abs(active.rate-1f)<0.001f)
        override fun analyze(track:MixTrack,startMs:Long,lengthMs:Long,deadlineNanos:Long):TrackFeatures {
            if(analysisFailures) throw java.io.IOException("Unsupported source")
            return features(start=startMs)
        }
        override fun currentDeck()=active
        override fun prepareIncoming(snapshot:MixSnapshot,plan:AutoMixPlan):MixDeck {
            prepares++; b.positionMs=plan.incomingCueMs; return b
        }
        override fun promote(incoming:MixDeck) { promotions++; active=incoming as Deck }
        override fun restoreNormalAudio() { restored++ }
        fun advance(ms:Long) {
            if(a.playing) a.positionMs+=ms
            if(b.playing) b.positionMs+=(ms*b.rate).toLong()
        }
    }
    private fun start(host:Host,controller:AutoMixController) {
        controller.configure(true,AutoMixPerformance.BALANCED)
        host.a.positionMs=1000
        repeat(20) { controller.tick(); Thread.sleep(2) } // advance analysis well before the transition deadline
        host.a.positionMs=47000
        repeat(100) { controller.tick(); if(host.prepares==0) Thread.sleep(5) }
        assertEquals(1,host.prepares)
        assertSame(host.a,host.active) // paused standby must not publish metadata
        host.a.positionMs=56000
        controller.tick()
        assertEquals(1,host.promotions)
        assertSame(host.b,host.active)
    }
    @Test fun twoDecodersOverlapThenAdvanceExactlyOnceWithoutRestartingIncoming() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c)
            h.advance(1000); c.tick()
            assertTrue(h.a.playing && h.b.playing)
            assertTrue(h.a.gain>0 && h.b.gain>0)
            assertEquals("b",h.active.trackId)
            repeat(150) { h.advance(50); c.tick() }
            assertTrue(h.a.released)
            assertEquals(1,h.a.releaseCount)
            assertEquals(1,h.promotions)
            assertTrue(h.b.positionMs>7000) // incoming continues, never restarts at zero
            assertEquals(1f,h.b.rate,0.001f)
        }
    }
    @Test fun plannedPlaybackProducesBothFrequenciesNotJustCallbacks() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c); h.advance(2000); c.tick()
            val a=AutoMixPcm(48000,1); val b=AutoMixPcm(48000,1)
            val pcm=FloatArray(48000) { i ->
                val x=a.sample((0.7*sin(2*PI*440*i/48000)).toFloat(),0,1.0,h.a.bass,0.0)
                val y=b.sample((0.7*sin(2*PI*660*i/48000)).toFloat(),0,1.0,h.b.bass,0.0)
                (x*h.a.gain+y*h.b.gain).toFloat() // AudioTrack deck gains over filtered PCM
            }
            fun component(hz:Int)=abs(pcm.indices.sumOf { pcm[it]*sin(2*PI*hz*it/48000) }/pcm.size)
            assertTrue(component(440)>0.08 && component(660)>0.08)
            assertTrue(pcm.maxOf { abs(it) }<1f)
        }
    }
    @Test fun pauseFreezesBothDecksAndResumeContinues() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c); h.advance(500); c.tick()
            h.b.pause(); c.syncPlaybackState()
            val before=h.a.positionMs; h.advance(2000); c.tick()
            assertFalse(h.a.playing); assertEquals(before,h.a.positionMs)
            h.b.play(); c.syncPlaybackState(); h.advance(500); c.tick()
            assertTrue(h.a.playing); assertFalse(h.a.released)
        }
    }
    @Test fun seekOrManualSkipCancelsOutgoingWithoutReplacingActiveDeck() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c); c.invalidate()
            assertTrue(h.a.released); assertSame(h.b,h.active)
            assertEquals(1.0,h.b.gain,0.0); assertFalse(c.mixing)
        }
    }
    @Test fun queueEditCancelsArmedStandby() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED); h.a.positionMs=1000
            repeat(20) { c.tick(); Thread.sleep(2) }
            h.a.positionMs=47000
            repeat(100) { c.tick(); if(h.prepares==0) Thread.sleep(5) }
            h.queue="a,c,b"; c.tick()
            assertTrue(h.b.released); assertEquals(0,h.promotions)
        }
    }
    @Test fun queueShuffleOrRepeatChangeDuringOverlapInvalidatesMix() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c); h.queue="shuffle:c,b,a"; c.tick()
            assertFalse(c.mixing); assertTrue(h.a.released)
        }
    }
    @Test fun failedIncomingDecoderDoesNotLeaveTwoSongsPlaying() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c); h.b.ready=false; c.tick()
            assertTrue(h.a.released); assertFalse(c.mixing)
        }
    }
    @Test fun missedDeadlineNeverStartsALateMix() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED); h.a.positionMs=59000
            repeat(100) { c.tick(); Thread.sleep(1) }
            assertEquals(0,h.promotions)
        }
    }
    @Test fun disablingAutoMixReleasesStandbyAndDisabledControllerDoesNoAnalysis() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            h.a.positionMs=47000; repeat(20) { c.tick() }; assertEquals(0,h.prepares)
            start(h,c); c.configure(false,AutoMixPerformance.BALANCED)
            assertTrue(h.a.released); assertFalse(c.mixing); assertEquals(1.0,h.b.gain,0.0)
        }
    }
    @Test fun unknownAnalysisUsesAutoMixOwnFallback() {
        val h=Host().apply { analysisFailures=true }
        AutoMixController(h,AnalysisWorkers()).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED); h.a.positionMs=50000
            repeat(100) { c.tick(); if(h.prepares==0) Thread.sleep(5) }
            h.a.positionMs=57500; c.tick()
            assertEquals(1,h.promotions)
            assertEquals(1f,h.b.rate,0f)
        }
    }
}
