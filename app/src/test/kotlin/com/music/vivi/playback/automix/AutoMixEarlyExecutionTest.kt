package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AutoMixEarlyExecutionTest {
    private class Deck(override val trackId: String): MixDeck {
        override var positionMs=1000L
        override var ready=true
        override var playing=false
        override var rate=1f
        override val ended get()=released
        var released=false
        var gain=1.0
        var bass=0.0
        override fun play() { playing=true }
        override fun pause() { playing=false }
        override fun process(gain: Double,bassCut: Double,trebleCut: Double) { this.gain=gain; bass=bassCut }
        override fun release() { released=true; playing=false }
    }
    private class Host(private val failOneScan: Boolean=false): AutoMixHost {
        override val allowed=true
        val a=Deck("a").apply { playing=true }
        var b=Deck("b")
        var active=a
        var plan: AutoMixPlan?=null
        var promotions=0
        val diagnostics=java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun diagnostic(message: String, verbose: Boolean) { diagnostics+=message }
        override fun snapshot()=MixSnapshot(MixTrack(active.trackId,180000,active.trackId),MixTrack("b",180000,"b"),
            active.trackId,active.positionMs,active.playing,true)
        private fun window(track: MixTrack,start: Long,length: Long,detail: Boolean): TrackFeatures {
            val base=if(track.id=="a") outroFeatures() else introFeatures()
            val end=min(180000,start+length)
            val energy=(start..end step 100).map { t ->
                if(track.id=="a") EnergyPoint(t,if(t<145000)0.22 else 0.025,if(t<145000)0.85 else 0.1)
                else EnergyPoint(t,if(t<8000)0.001 else 0.07,0.1)
            }
            return base.copy(startMs=start,lengthMs=end-start,energy=energy,
                beatsMs=(start..end step 500).toList(),sections=listOf(RhythmSection(start,end,120.0,0.95,0.95,0.95,detail)),
                boundaries=detectSectionBoundaries(energy,0.95))
        }
        override fun scan(track: MixTrack,startMs: Long,lengthMs: Long,deadlineNanos: Long): TrackFeatures {
            if(failOneScan && track.id=="a" && startMs==90000L) throw java.io.IOException("one unavailable range")
            return window(track,startMs,lengthMs,false)
        }
        override fun analyze(track: MixTrack,startMs: Long,lengthMs: Long,deadlineNanos: Long)=window(track,startMs,lengthMs,true)
        override fun currentDeck()=a
        override fun prepareIncoming(snapshot: MixSnapshot,plan: AutoMixPlan): MixDeck {
            this.plan=plan; b=Deck("b").apply { positionMs=plan.incomingCueMs }; return b
        }
        override fun promote(incoming: MixDeck) { promotions++; active=b }
        override fun restoreNormalAudio() {}
        fun advance(ms: Long) { if(a.playing) a.positionMs+=ms; if(b.playing) b.positionMs+=(ms*b.rate).toLong() }
    }
    private fun prepare(host: Host,c: AutoMixController) {
        c.configure(true,AutoMixPerformance.BALANCED)
        c.tick() // capture the original playback position before the worker jobs finish
        host.a.positionMs=135000
        val deadline=System.nanoTime()+5_000_000_000L
        while(host.plan==null && System.nanoTime()<deadline) { c.tick(); Thread.sleep(10) }
        assertNotNull(host.plan)
        assertTrue(host.plan!!.outgoingStartMs<160000)
        assertEquals(8000L,host.plan!!.incomingCueMs)
    }
    @Test fun earlyPlanExecutesBothAudioDecksAndReleasesOutgoingBeforeItsNaturalEnd() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            prepare(h,c); val p=h.plan!!
            assertFalse(h.b.playing); assertSame(h.a,h.active)
            h.a.positionMs=p.outgoingStartMs; c.tick(); assertEquals(1,h.promotions)
            h.advance(p.durationMs/2); c.tick()
            assertTrue(h.a.playing && h.b.playing)
            assertTrue(h.a.gain>0 && h.b.gain>0)
            val samples=FloatArray(48000) { i -> (0.2*sin(2*PI*440*i/48000)*h.a.gain+0.2*sin(2*PI*660*i/48000)*h.b.gain).toFloat() }
            for(hz in listOf(440,660)) {
                val strength=abs(samples.indices.sumOf { samples[it]*sin(2*PI*hz*it/48000) })/samples.size
                assertTrue(strength>0.005)
            }
            assertTrue(samples.maxOf { abs(it) }<0.94)
            h.advance(p.durationMs/2+50); c.tick()
            assertTrue(h.a.released); assertTrue(h.a.positionMs<180000-10000)
            assertSame(h.b,h.active); assertEquals(1,h.promotions)
            assertTrue(h.b.positionMs>p.incomingCueMs)
            h.advance(3100); c.tick(); assertFalse(c.mixing)
            assertEquals(1f,h.b.rate,0.001f)
        }
    }
    @Test fun oneMissingCoarseRangeDoesNotEraseAUsableOutro() {
        val h=Host(true)
        AutoMixController(h,AnalysisWorkers()).use { c -> prepare(h,c) }
    }
    @Test fun missedEarlyBoundaryUsesOwnLaterFallbackWithoutStartingAnOffBeatMix() {
        val h=Host()
        AutoMixController(h,AnalysisWorkers()).use { c ->
            prepare(h,c); val early=h.plan!!
            h.a.positionMs=early.outgoingStartMs+1000; c.tick()
            assertEquals(0,h.promotions); assertTrue(h.b.released)
            h.a.positionMs=160000; c.tick() // wait until the fallback's actual preparation window
            assertEquals(MixTechnique.SAFE_FADE,h.plan!!.technique)
            h.a.positionMs=177500; c.tick(); assertEquals(1,h.promotions)
            assertTrue(h.diagnostics.any { it.contains("event=start-missed") })
        }
    }
}
