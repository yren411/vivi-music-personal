package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Audible bass + accented drums continue at equal loudness through the file end. */
internal object DjAudioFixture {
    const val rate=22050
    fun pcm(startMs: Long,lengthMs: Long,entryMs: Long=0,introAmplitude: Double=0.0): FloatArray = FloatArray((lengthMs*rate/1000).toInt()) { i ->
        val time=startMs/1000.0+i.toDouble()/rate
        if(time<entryMs/1000.0) (introAmplitude*sin(2*PI*110*time)).toFloat() else {
            val local=time%0.5
            val beat=(time/0.5).toInt()
            val kick=if(local<0.1) sin(2*PI*85*local)*exp(-local*45)*(if(beat%4==0)0.65 else 0.22) else 0.0
            (0.18*sin(2*PI*110*time)+kick).toFloat()
        }
    }
    fun features(start: Long,length: Long,entry: Long=0,detail: AnalysisDetail=AnalysisDetail.DETAIL)=
        TrackFeaturesAnalyzer().analyze(pcm(start,length,entry),rate,start,216000,detail=detail)
    fun outgoing()=mergeTrackFeatures(listOf(features(126000,30000),features(156000,30000),features(186000,30000)),true)!!
    fun incoming()=mergeTrackFeatures(listOf(features(0,32000,18000),features(32000,32000,18000)),false)!!
}
class DjMixAnalysisTest {
    @Test fun activeToActiveMaterialCanSelectAnExtendedMixAndLaterEntry() {
        val outgoing=DjAudioFixture.outgoing();val incoming=DjAudioFixture.incoming()
        val decision=AutoMixPlanner().decide(outgoing,incoming,216000,216000)
        println("DJ decision ${decision.plan} rejected ${decision.rejections}")
        val plan=decision.plan!!
        assertEquals(AutoMixTier.A,plan.tier)
        assertEquals(64,plan.beats)
        assertTrue(plan.durationMs>=30000)
        assertTrue(plan.outgoingStartMs<186000)
        assertTrue(plan.incomingCueMs in 17500..18500)
        assertTrue(outgoing.energy.filter { it.positionMs>=plan.outgoingStartMs }.map { it.rms }.average()>0.1)
    }
    @Test fun importantIntroAndVocalCollisionAreNotSkippedToManufactureDjMix() {
        val out=DjAudioFixture.outgoing();val incoming=DjAudioFixture.incoming()
        val protected=incoming.copy(energy=incoming.energy.map { if(it.positionMs<18000) it.copy(rms=0.15,vocalRisk=0.9) else it })
        val plan=AutoMixPlanner().decide(out,protected,216000,216000).plan
        assertTrue(plan==null || plan.incomingCueMs<=2000)
        val vocals=out.copy(energy=out.energy.map { it.copy(vocalRisk=0.95) })
        assertTrue(AutoMixPlanner().decide(vocals,incoming,216000,216000).plan?.tier!=AutoMixTier.A)
    }
    @Test fun shorterMeasuredCoverageSelects32And16BeatsInsteadOfPretending64Fit() {
        val incoming=DjAudioFixture.incoming()
        for((start,length,beats) in listOf(Triple(196000L,20000L,32),Triple(204000L,12000L,16))) {
            val out=DjAudioFixture.features(start,length)
            val decision=AutoMixPlanner().decide(out,incoming,216000,216000,requireDetailed=true)
            assertEquals("${decision.plan}",beats,decision.plan!!.beats)
            assertEquals(beats*500L,decision.plan!!.durationMs)
            assertTrue(decision.plan!!.outgoingStartMs>=start)
        }
    }

    @Test fun djAutomationUsesDistinctSmoothCurvesAndChangesHighFrequencyPcm() {
        val p=AutoMixPlan(100000,18000,32000,technique=MixTechnique.BASS_HANDOFF,tier=AutoMixTier.A,beats=64)
        assertNotEquals(mixGains(0.25).incoming,djAutomation(p,0.25).incoming.gain,0.01)
        val shape=djAutomation(p,0.9).outgoing
        val wet=AutoMixPcm(48000,1);val dry=AutoMixPcm(48000,1)
        var a=0.0;var b=0.0
        for(i in 0 until 48000) {
            val x=(0.5*sin(2*PI*7000*i/48000)).toFloat()
            val v=wet.sample(x,0,1.0,shape.bassCut,shape.trebleCut)
            val d=dry.sample(x,0,1.0,0.0,0.0)
            if(i>4800) { a+=v*v;b+=d*d }
        }
        assertTrue(a<b*0.85)
    }

}
