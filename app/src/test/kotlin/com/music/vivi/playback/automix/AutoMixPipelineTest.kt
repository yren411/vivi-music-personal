package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Generated PCM enters the actual analyzer, coarse/refined merger, planner and controller.
 * No preconstructed musical features. Fake decks replace Android output only. */
class AutoMixPipelineTest {
    private class Deck(override val trackId: String) : MixDeck {
        override var positionMs=1000L
        override var playing=false
        override var ready=true
        override val ended get()=released
        override var rate=1f
        var released=false
        var gain=1.0
        var bass=0.0
        var treble=0.0
        val kernel=AutoMixPcm(22050,1)
        override fun play() { playing=true }
        override fun pause() { playing=false }
        override fun process(gain: Double,bassCut: Double,trebleCut: Double) { this.gain=gain; bass=bassCut;treble=trebleCut }
        override fun release() { released=true;playing=false }
    }
    private class Host(val stallRefinement: Boolean=false, val unavailable: Boolean=false, val refinementFailure: Boolean=false,
                             val skipFirstWindow: Boolean=false, val nextBpm: Double=120.0,
                             val preparationFails: Boolean=false) : AutoMixHost {
        override val allowed=true
        val clock=AtomicLong(0)
        val a=Deck("out").apply { playing=true }
        val b=Deck("in")
        var active=a
        var epoch=0
        var nextId="in"
        var resolved=true
        var promotions=0
        val refineEntered=CountDownLatch(1)
        val refineGate=CountDownLatch(if(stallRefinement) 1 else 0)
        val events=java.util.Collections.synchronizedList(mutableListOf<String>())
        var selected: AutoMixPlan?=null
        override fun diagnostic(message: String,verbose: Boolean) { events+=message }
        override fun snapshot()=MixSnapshot(MixTrack(active.trackId,216000,active.trackId),
            MixTrack(nextId,216000,nextId),"epoch-$epoch",active.positionMs,active.playing,true)
        override fun sourcesReady(snapshot: MixSnapshot)=resolved
        fun pcm(id: String,start: Long,length: Long): FloatArray {
            val rate=22050
            return FloatArray((length*rate/1000).toInt()) { i ->
                val t=start/1000.0+i.toDouble()/rate
                if(id=="in" && t<8) 0f else {
                    val local=t%(if(id=="in") 60.0/nextBpm else 0.5)
                    val pulse=if(local<0.09) sin(2*PI*85*local)*exp(-local*45) else 0.0
                    // Clear 35-second instrumental outro; section drop is measured from audio.
                    val amplitude=if(id=="out" && t<181)0.8 else 0.12
                    (pulse*amplitude).toFloat()
                }
            }
        }
        private fun features(track: MixTrack,start: Long,length: Long,detail: AnalysisDetail): TrackFeatures {
            if(unavailable) throw AnalysisUnavailable("source-not-range-seekable")
            return TrackFeaturesAnalyzer().analyze(pcm(track.id,start,length),22050,start,216000,detail=detail)
        }
        override fun scan(track: MixTrack,startMs: Long,lengthMs: Long,deadlineNanos: Long): TrackFeatures {
            Thread.sleep(15) // queued work is asynchronous, not immediately completed mocks
            if(skipFirstWindow && track.id=="out" && startMs==126000L) throw AnalysisUnavailable("range-unavailable")
            val result=features(track,startMs,lengthMs,AnalysisDetail.SCAN)
            clock.set(35_000_000_000L) // scan consumed most of the OLD shared 40-second budget
            return result
        }
        override fun analyze(track: MixTrack,startMs: Long,lengthMs: Long,deadlineNanos: Long): TrackFeatures {
            refineEntered.countDown()
            check(refineGate.await(5,TimeUnit.SECONDS))
            clock.set(if(refinementFailure) 100_000_000_000L else 42_000_000_000L)
            if(deadlineNanos<=clock.get()) throw java.util.concurrent.CancellationException("deadline")
            return features(track,startMs,lengthMs,AnalysisDetail.DETAIL)
        }
        override fun currentDeck()=a
        override fun prepareIncoming(snapshot: MixSnapshot,plan: AutoMixPlan): MixDeck? {
            if(preparationFails) return null
            selected=plan;b.positionMs=plan.incomingCueMs; return b
        }
        override fun promote(incoming: MixDeck) { promotions++;active=b }
        override fun restoreNormalAudio() {}
        fun advance(ms: Long) { if(a.playing)a.positionMs+=ms;if(b.playing)b.positionMs+=(ms*b.rate).toLong() }
    }
    private fun await(c: AutoMixController,condition: () -> Boolean) {
        val deadline=System.nanoTime()+12_000_000_000L
        while(!condition() && System.nanoTime()<deadline) { c.tick();Thread.sleep(5) }
        assertTrue("State ${c.status.value}",condition())
    }
    @Test fun realAudioPipelineSelectsAndExecutesEarlierOutroInEveryWorkerMode() {
        for(mode in AutoMixPerformance.entries) {
            val h=Host()
            AutoMixController(h,AnalysisWorkers(mode),nanoTime=h.clock::get).use { c ->
                c.configure(true,mode);await(c) { c.plannedTransition!=null }
                val p=c.plannedTransition!!
                assertTrue("$mode $p",p.technique in setOf(MixTechnique.BEAT_BLEND,MixTechnique.BASS_HANDOFF))
                assertTrue("$p ${h.events}",p.outgoingStartMs in 179000..183000)
                assertTrue(p.incomingCueMs in 7900..8500)
                assertTrue(p.durationMs>=6000)
                h.a.positionMs=p.outgoingStartMs-20000;c.tick()
                assertEquals(AutoMixPhase.READY,c.status.value.phase)
                h.a.positionMs=p.outgoingStartMs;c.tick()
                assertEquals(p.outgoingStartMs,c.status.value.actualStartMs)
                assertEquals(AutoMixPhase.MIXING,c.status.value.phase)
                h.advance(p.durationMs/2);c.tick()
                assertTrue(h.a.gain>0 && h.b.gain>0)
                val out=h.pcm("out",h.a.positionMs,1000)
                val incoming=h.pcm("in",h.b.positionMs,1000)
                val mixed=out.indices.map { h.a.kernel.sample(out[it],0,1.0,h.a.bass,h.a.treble)*h.a.gain+
                    h.b.kernel.sample(incoming[it],0,1.0,h.b.bass,h.b.treble)*h.b.gain }
                assertTrue(mixed.maxOf { abs(it) }<0.94)
                assertTrue(out.any { abs(it*h.a.gain)>0.001 } && incoming.any { abs(it*h.b.gain)>0.001 })
                h.advance(p.durationMs/2+50);c.tick()
                assertTrue(h.a.released);assertTrue(h.a.positionMs<216000-10000)
                assertEquals(1,h.promotions);assertTrue(h.b.positionMs>p.incomingCueMs)
                h.advance(3100);c.tick();assertFalse(c.mixing)
                assertEquals(1,h.promotions)
                println("$mode generated PCM plan: $p")
            }
        }
    }
    @Test fun unfinishedRefinementIsNotCanceledFiveSecondsBeforeBoundary() {
        val h=Host(stallRefinement=true)
        AutoMixController(h,AnalysisWorkers(),nanoTime=h.clock::get).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED)
            await(c) { h.refineEntered.count==0L }
            h.a.positionMs=177000 // four seconds before the measured outro; old controller canceled here
            repeat(5) { c.tick();Thread.sleep(10) }
            assertEquals(AutoMixPhase.REFINING,c.status.value.phase)
            h.refineGate.countDown()
            await(c) { c.plannedTransition!=null }
            assertNotEquals(MixTechnique.SAFE_FADE,c.plannedTransition!!.technique)
        }
    }
    @Test fun futureQueueAppendDoesNotRestartAnUnchangedPairButNewNextTrackInvalidates() {
        val h=Host(stallRefinement=true)
        AutoMixController(h,AnalysisWorkers(),nanoTime=h.clock::get).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED);await(c) { h.refineEntered.count==0L }
            h.epoch++;c.playlistChanged("out")
            assertEquals(AutoMixPhase.REFINING,c.status.value.phase)
            h.refineGate.countDown();await(c) { c.plannedTransition!=null }
            h.nextId="different";c.playlistChanged("out")
            assertNull(c.status.value.plan);assertNull(c.plannedTransition)
        }
    }
    @Test fun unresolvedSourceWaitsWithoutAnalysisAndFailureIsLabeledFallback() {
        val h=Host(unavailable=true).apply { resolved=false }
        AutoMixController(h,AnalysisWorkers(),nanoTime=h.clock::get).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED);c.tick()
            assertEquals(AutoMixPhase.WAITING_SOURCE,c.status.value.phase)
            h.resolved=true;await(c) { c.plannedTransition!=null }
            assertEquals(AutoMixPhase.FALLBACK_PLANNED,c.status.value.phase)
            assertEquals("source-not-range-seekable",c.status.value.reason)
            assertNull(c.status.value.markerFraction("out","in",216000))
        }
    }
    @Test fun failedRefinementAndMissedStartHaveDifferentHonestFallbackReasons() {
        val failed=Host(refinementFailure=true)
        AutoMixController(failed,AnalysisWorkers(),nanoTime=failed.clock::get).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED);await(c) { c.plannedTransition!=null }
            assertEquals(AutoMixTier.B,c.plannedTransition!!.tier)
            assertEquals("coarse-analysis-recovery",c.status.value.reason)
            assertTrue(c.plannedTransition!!.durationMs<=16000)
        }
        val h=Host()
        AutoMixController(h,AnalysisWorkers(),nanoTime=h.clock::get).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED);await(c) { c.plannedTransition!=null }
            val p=c.plannedTransition!!
            h.a.positionMs=p.outgoingStartMs-10000;c.tick()
            h.a.positionMs=p.outgoingStartMs+1000;c.tick()
            assertEquals(AutoMixPhase.FALLBACK_PLANNED,c.status.value.phase)
            assertEquals("missed-intelligent-boundary",c.status.value.reason)
            assertTrue(h.b.released);assertEquals(0,h.promotions)
            assertNull(c.status.value.markerFraction("out","in",216000))
        }
    }
    @Test fun incompleteScanRetainsAUsableOutroAndIncompatibleRhythmDoesNotForceEarlyMix() {
        for(h in listOf(Host(skipFirstWindow=true),Host(nextBpm=90.0))) {
            AutoMixController(h,AnalysisWorkers(),nanoTime=h.clock::get).use { c ->
                c.configure(true,AutoMixPerformance.BALANCED);await(c) { c.plannedTransition!=null }
                val p=c.plannedTransition!!
                if(h.skipFirstWindow) assertTrue("$p",p.outgoingStartMs<190000)
                else { assertTrue("$p",p.outgoingStartMs>=206000);assertNotEquals("measured-sustained-outro",p.reason) }
            }
        }
    }
    @Test fun decoderPreparationFailureHidesIntelligentMarkerAndCanContinueNormalPlayback() {
        val h=Host(preparationFails=true)
        AutoMixController(h,AnalysisWorkers(),nanoTime=h.clock::get).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED);await(c) { c.plannedTransition!=null }
            h.a.positionMs=c.plannedTransition!!.outgoingStartMs-20000;c.tick()
            assertEquals(AutoMixPhase.FALLBACK_PLANNED,c.status.value.phase)
            assertEquals("deck-preparation-failed",c.status.value.reason)
            assertNull(c.status.value.markerFraction("out","in",216000))
            h.a.positionMs=210000;c.tick()
            assertEquals(AutoMixPhase.UNAVAILABLE,c.status.value.phase)
            assertNull(c.status.value.plan);assertEquals(0,h.promotions)
            assertTrue(h.a.playing);assertFalse(h.a.released)
        }
    }

}
