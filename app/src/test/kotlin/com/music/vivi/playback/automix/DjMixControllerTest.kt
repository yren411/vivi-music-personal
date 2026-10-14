package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

class DjMixControllerTest {
    private class Deck(override val trackId: String): MixDeck {
        override var positionMs=1000L
        override var playing=false
        override var ready=true
        override var failed=false
        override val ended get()=released
        override var rate=1f
        var released=false
        var automation=DeckAutomation(1.0)
        val pcm=AutoMixPcm(DjAudioFixture.rate,1)
        override fun play() { playing=true }
        override fun pause() { playing=false }
        override fun process(gain: Double,bassCut: Double,trebleCut: Double) { automation=DeckAutomation(gain,bassCut,trebleCut) }
        override fun release() { playing=false;released=true }
        fun render(samples: FloatArray)=FloatArray(samples.size) { i -> (pcm.sample(samples[i],0,1.0,automation.bassCut,automation.trebleCut)*automation.gain).toFloat() }
    }
    private class Host(val failRefinement: Boolean=false,val failScanOnce: Boolean=false): AutoMixHost {
        override val allowed=true
        val a=Deck("private://out?token=PRIVATE_SECRET").apply { playing=true }
        val b=Deck("private://in?cookie=PRIVATE_SECRET")
        var active=a
        var promotions=0
        var retryInjected=false
        var preparedPlan: AutoMixPlan?=null
        override fun snapshot()=MixSnapshot(MixTrack(active.trackId,216000,active.trackId),MixTrack(b.trackId,216000,b.trackId),active.trackId,active.positionMs,active.playing,true)
        private fun analyzePcm(track: MixTrack,start: Long,length: Long,detail: AnalysisDetail)=
            TrackFeaturesAnalyzer().analyze(DjAudioFixture.pcm(start,length,if(track.id==b.trackId)18000 else 0,
                if(track.id==b.trackId)0.03 else 0.0),DjAudioFixture.rate,start,216000,detail=detail)
        override fun scan(track: MixTrack,startMs: Long,lengthMs: Long,deadlineNanos: Long): TrackFeatures {
            if(failScanOnce && track.id==a.trackId && startMs==126000L && !retryInjected) {
                retryInjected=true;throw AnalysisUnavailable("analysis-byte-budget-exhausted")
            }
            return analyzePcm(track,startMs,lengthMs,AnalysisDetail.SCAN)
        }
        override fun analyze(track: MixTrack,startMs: Long,lengthMs: Long,deadlineNanos: Long): TrackFeatures {
            if(failRefinement) throw AnalysisUnavailable("DECODER_FAILED")
            return analyzePcm(track,startMs,lengthMs,AnalysisDetail.DETAIL)
        }
        override fun currentDeck()=a
        override fun prepareIncoming(snapshot: MixSnapshot,plan: AutoMixPlan): MixDeck { preparedPlan=plan;b.positionMs=plan.incomingCueMs;return b }
        override fun promote(incoming: MixDeck) { active=b;promotions++ }
        override fun restoreNormalAudio() {}
        fun advance(ms: Long) { if(a.playing)a.positionMs+=ms;if(b.playing)b.positionMs+=(ms*b.rate).toLong() }
    }
    private fun waitForPlan(c: AutoMixController) {
        val end=System.nanoTime()+15_000_000_000L
        while(c.plannedTransition==null && System.nanoTime()<end) { c.tick();Thread.sleep(5) }
        assertNotNull("${c.status.value}",c.plannedTransition)
    }
    @Test fun actualActiveDjPipelineExecutesExtendedBassBlendAndLateCueInEveryMode() {
        for(mode in AutoMixPerformance.entries) {
            val h=Host();val recorder=AutoMixRecorder()
            AutoMixController(h,AnalysisWorkers(mode),recorder=recorder).use { c ->
                c.configure(true,mode);waitForPlan(c)
                val plan=c.plannedTransition!!
                println("DJ $mode $plan")
                assertEquals(AutoMixTier.A,plan.tier);assertEquals(64,plan.beats)
                assertEquals(32000L,plan.durationMs)
                assertTrue(plan.incomingCueMs in 17500..18500)
                assertEquals(CueReason.RHYTHMIC_ENTRY,plan.cueReason)
                h.a.positionMs=plan.outgoingStartMs-10000;c.tick()
                assertEquals(AutoMixPhase.READY,c.status.value.phase)
                assertEquals(plan.incomingCueMs,h.b.positionMs)
                h.a.positionMs=plan.outgoingStartMs;c.tick()
                assertEquals(AutoMixPhase.MIXING,c.status.value.phase);assertEquals(1,h.promotions)
                var peak=0f;var rendered=0L
                repeat((plan.durationMs/40).toInt()) { tick ->
                    c.tick()
                    val outRaw=DjAudioFixture.pcm(h.a.positionMs,40)
                    val inRaw=DjAudioFixture.pcm(h.b.positionMs,40,18000)
                    val out=h.a.render(outRaw);val incoming=h.b.render(inRaw)
                    rendered+=out.size
                    for(i in out.indices) peak=maxOf(peak,kotlin.math.abs(out[i]+incoming[i]))
                    if(tick==200) {
                        assertTrue(out.any { kotlin.math.abs(it)>0.001f })
                        assertTrue(incoming.any { kotlin.math.abs(it)>0.001f })
                        assertTrue(h.b.automation.bassCut>0)
                        val wet=incoming.sumOf { it.toDouble()*it }
                        val dry=inRaw.sumOf { it.toDouble()*it }*h.b.automation.gain*h.b.automation.gain
                        assertTrue(wet<dry*0.8) // bass processing actually changes PCM, not just a call
                    }
                    if(tick==600) assertTrue(h.a.automation.bassCut>0 && h.a.automation.trebleCut>0)
                    h.advance(40)
                }
                c.tick()
                assertEquals(plan.durationMs*DjAudioFixture.rate/1000,rendered)
                assertTrue(peak<0.94f);assertTrue(h.a.released)
                assertTrue(h.a.positionMs<216000);assertTrue(h.b.positionMs>plan.incomingCueMs)
                h.advance(3100);c.tick();assertFalse(c.mixing);assertEquals(1,h.promotions)
                assertEquals(AutoMixPhase.COMPLETED,c.status.value.phase)
                val log=AutoMixRecorder.render(recorder.snapshot(),MixReportHeader("test",80,"16","fixture",true,mode))
                assertTrue(log.contains("tier=A"));assertTrue(log.contains("ACTUAL_START_MS"));assertTrue(log.contains("COMPLETE"))
                assertFalse(log.contains("PRIVATE_SECRET"));assertFalse(log.contains("private://"))
            }
        }
    }
    @Test fun failedRefinementRecoversToAnHonestModerateMix() {
        val h=Host(failRefinement=true);val recorder=AutoMixRecorder()
        AutoMixController(h,AnalysisWorkers(),recorder=recorder).use { c ->
            c.configure(true,AutoMixPerformance.BALANCED);waitForPlan(c)
            assertEquals(AutoMixTier.B,c.plannedTransition!!.tier)
            assertEquals("coarse-analysis-recovery",c.status.value.reason)
            assertTrue(c.plannedTransition!!.durationMs<=16000)
            val records=recorder.snapshot().attempts.flatMap { it.records }
            assertTrue(records.any { it.event==MixEvent.RECOVERED && it.failure==AutoMixFailure.REFINEMENT_FAILED })
            assertTrue(records.any { it.failure==AutoMixFailure.DECODER_FAILED })
        }
    }
    @Test fun oneConstrainedScanUsesOneSmallerRetryWithoutDiscardingRemainingWindows() {
        val h=Host(failScanOnce=true);val recorder=AutoMixRecorder()
        AutoMixController(h,AnalysisWorkers(),recorder=recorder).use { c ->
            c.configure(true,AutoMixPerformance.EFFICIENT);waitForPlan(c)
            assertEquals(AutoMixTier.A,c.plannedTransition!!.tier)
            val records=recorder.snapshot().attempts.flatMap { it.records }
            assertEquals(1,records.count { it.event==MixEvent.RETRY })
            assertTrue(records.any { it.event==MixEvent.RECOVERED && it.failure==AutoMixFailure.ANALYSIS_BYTE_BUDGET })
        }
    }
}
