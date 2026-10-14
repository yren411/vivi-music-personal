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
        var failPromotion=false
        var normalSpeed=true
        var duration=60000L
        var sourceRevision="1"
        val requests=java.util.concurrent.ConcurrentLinkedQueue<Triple<String,Long,Long>>()
        override fun snapshot()=MixSnapshot(MixTrack(active.trackId,duration,"${active.trackId}:$sourceRevision"),
            MixTrack(if(active===a) "b" else "c",duration,"${if(active===a) "b" else "c"}:$sourceRevision"),
            "$queue:${active.trackId}",active.positionMs,active.playing,normalSpeed && abs(active.rate-1f)<0.001f)
        override fun analyze(track:MixTrack,startMs:Long,lengthMs:Long,deadlineNanos:Long):TrackFeatures {
            requests.add(Triple(track.id,startMs,lengthMs))
            if(analysisFailures) throw java.io.IOException("Unsupported source")
            return features(start=startMs)
        }
        override fun currentDeck()=active
        override fun prepareIncoming(snapshot:MixSnapshot,plan:AutoMixPlan):MixDeck {
            prepares++; b.positionMs=plan.incomingCueMs; return b
        }
        override fun promote(incoming:MixDeck) {
            if(failPromotion) error("Session unavailable")
            promotions++; active=incoming as Deck
        }
        override fun restoreNormalAudio() { restored++ }
        fun advance(ms:Long) {
            if(a.playing) a.positionMs+=ms
            if(b.playing) b.positionMs+=(ms*b.rate).toLong()
        }
    }
    private fun start(host:Host,controller:AutoMixController) {
        controller.configure(true,AutoMixPerformance.BALANCED)
        host.a.positionMs=1000
        val planningDeadline=System.nanoTime()+5_000_000_000L
        while(controller.plannedTransition==null && System.nanoTime()<planningDeadline) { controller.tick(); Thread.sleep(5) }
        assertNotNull(controller.plannedTransition) // finish async analysis before advancing the fake clock by 46s
        assertEquals(56000L,controller.plannedTransition!!.outgoingStartMs)
        host.a.positionMs=47000
        val deadline=System.nanoTime()+5_000_000_000L
        while(host.prepares==0 && System.nanoTime()<deadline) { controller.tick(); Thread.sleep(5) }
        assertEquals(1,host.prepares)
        assertSame(host.a,host.active) // paused standby must not publish metadata
        host.a.positionMs=56000
        controller.tick()
        assertEquals(1,host.promotions)
        assertSame(host.b,host.active)
    }
    @Test fun realPipelineSchedulesFourDisjointEqualQualityWindows() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            c.configure(true,AutoMixPerformance.PERFORMANCE); h.a.positionMs=1000; c.tick()
            repeat(100) { if(h.requests.size<4) Thread.sleep(5) }
            assertTrue(h.requests.toSet().containsAll(setOf(Triple("a",28000L,16000L),Triple("a",44000L,16000L),
                Triple("b",0L,16000L),Triple("b",16000L,16000L))))
            assertTrue(h.requests.size<=6) // coarse four, optionally followed by two shortlisted refinements
        }
    }
    @Test fun unsupportedRoutesSpeedAndShortTracksDoNoAnalysis() {
        for(h in listOf(Host().apply { allowed=false },Host().apply { normalSpeed=false },Host().apply { duration=10000 })) {
            AutoMixController(h,AnalysisWorkers()).use { c ->
                c.configure(true,AutoMixPerformance.BALANCED); repeat(5) { c.tick() }
                assertTrue(h.requests.isEmpty()); assertEquals(0,h.prepares)
            }
        }
    }
    @Test fun changedSourceReleasesPreparedDecoderWithoutPromotingIt() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED); h.a.positionMs=1000
            repeat(20) { c.tick(); Thread.sleep(2) }; h.a.positionMs=47000
            repeat(100) { c.tick(); if(h.prepares==0) Thread.sleep(5) }
            assertEquals(1,h.prepares)
            h.sourceRevision="changed"; c.tick()
            assertTrue(h.b.released); assertEquals(0,h.promotions); assertFalse(h.a.released)
        }
    }
    @Test fun failedPromotionPreservesCurrentAudibleDecoder() {
        val h=Host().apply { failPromotion=true }
        AutoMixController(h,AnalysisWorkers()).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED); h.a.positionMs=1000
            repeat(20) { c.tick(); Thread.sleep(2) }; h.a.positionMs=47000
            repeat(100) { c.tick(); if(h.prepares==0) Thread.sleep(5) }
            h.a.positionMs=56000; c.tick()
            assertSame(h.a,h.active); assertFalse(h.a.released); assertTrue(h.a.playing)
            assertEquals(1.0,h.a.gain,0.0); assertTrue(h.b.released); assertFalse(c.mixing)
        }
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
    @Test fun livePerformanceChangeDoesNotCutAnAudibleBlend() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c); c.configure(true,AutoMixPerformance.PERFORMANCE)
            assertTrue(c.mixing); assertFalse(h.a.released)
            h.advance(1000); c.tick(); assertTrue(h.a.gain>0 && h.b.gain>0)
        }
    }
    @Test fun automaticQueueExpansionDoesNotCutTheAlreadyAudiblePair() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c); h.queue="a,b,c,d,e"
            c.playlistChanged(h.active.trackId); h.advance(1000); c.tick()
            assertTrue(c.mixing); assertFalse(h.a.released); assertTrue(h.a.gain>0 && h.b.gain>0)
            c.playlistChanged("removed or skipped song")
            assertFalse(c.mixing); assertTrue(h.a.released)
        }
    }
    @Test fun changedUserSpeedIsPreservedWhenMixIsCanceled() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            start(h,c); h.b.rate=0.8f; c.tick()
            assertFalse(c.mixing); assertEquals(0.8f,h.b.rate,0f)
        }
    }
    @Test fun tempoMatchingAdjustsActualIncomingDeckAndReturnsToNormal() {
        val h=object: AutoMixHost {
            override val allowed=true
            val a=Deck("a").apply { positionMs=1000 }
            val b=Deck("b").apply { playing=false }
            var active=a
            var prepared=false
            override fun snapshot()=MixSnapshot(MixTrack(active.trackId,60000,active.trackId),MixTrack("b",60000,"b"),
                active.trackId,active.positionMs,active.playing,true)
            override fun analyze(track:MixTrack,startMs:Long,lengthMs:Long,deadlineNanos:Long)=features(if(track.id=="a")120.0 else 124.0,startMs)
            override fun currentDeck()=active
            override fun prepareIncoming(snapshot:MixSnapshot,plan:AutoMixPlan):MixDeck { prepared=true; b.positionMs=plan.incomingCueMs; return b }
            override fun promote(incoming:MixDeck) { active=b }
            override fun restoreNormalAudio() { }
        }
        AutoMixController(h,AnalysisWorkers()).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED)
            val planningDeadline=System.nanoTime()+5_000_000_000L
            while(c.plannedTransition==null && System.nanoTime()<planningDeadline) { c.tick(); Thread.sleep(5) }
            assertNotNull(c.plannedTransition)
            h.a.positionMs=47000
            val deadline=System.nanoTime()+2_000_000_000L
            while(!h.prepared && System.nanoTime()<deadline) { c.tick(); Thread.sleep(5) }
            assertTrue(h.prepared)
            h.a.positionMs=56000; c.tick()
            assertEquals(120f/124,h.b.rate,0.001f)
            repeat(145) {
                if(h.a.playing) h.a.positionMs+=50
                if(h.b.playing) h.b.positionMs+=(50*h.b.rate).toLong()
                c.tick()
            }
            assertTrue(h.a.released); assertEquals(1f,h.b.rate,0.001f)
        }
    }
}
