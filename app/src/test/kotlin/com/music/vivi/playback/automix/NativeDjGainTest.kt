// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NativeDjGainTest {
    private val p get()=mathematicalExecutionPlan()
    private fun level(out:Boolean,rms:Double,fraction:Double=1.0,peak:Double=max(rms,.5)):NativeDjLevel {
        val q=p;val t=if(out)q.outgoing else q.incoming;val span=if(out)q.outgoingAnalyzedInterval else q.incomingAnalyzedInterval
        return NativeDjLevel(t.source,PcmPreprocessing.BANDLIMITED,span,rms,fraction,peak)
    }
    @Test fun quietTailCannotReduceIncomingToLegacyRatio(){
        val legacyRatio=.005/.2
        assertEquals(.025,legacyRatio,1e-15)
        val c=NativeDjGain.content(p,level(true,.0001),level(false,.2))
        assertEquals(1.0,c.incoming,0.0)
        assertTrue(NativeDjGain.gains(.5,NativeDjCurve.RESTRAINED_DJ,c).incoming>.49)
    }
    @Test fun loudIncomingCorrectionIsNeverMoreThanThreeDb(){val c=NativeDjGain.content(p,level(true,.02),level(false,.8,peak=.9));assertEquals(.707945784,c.incoming,1e-9);assertEquals(1.0,c.outgoing,0.0)}
    @Test fun quietIncomingIsNotBoostedOrPermanentlyNormalized(){val c=NativeDjGain.content(p,level(true,.8,peak=.9),level(false,.02));assertEquals(1.0,c.incoming,0.0);assertEquals(1.0,NativeDjGain.gains(1.0,NativeDjCurve.RESTRAINED_DJ,c).incoming,1e-12)}
    @Test fun silenceAndUnknownLevelsUseUnity(){for(v in listOf(0.0,Double.NaN,Double.POSITIVE_INFINITY,-1.0)){assertEquals(NativeDjContentGains(),NativeDjGain.content(p,level(true,v),level(false,.1)))};assertEquals(NativeDjContentGains(),NativeDjGain.content(p,null,null))}
    @Test fun mismatchedIdentityOrCoverageCannotAdjustGain(){val l=level(true,.1);for(bad in listOf(l.copy(source=AnalysisSourceIdentity("other","other")),l.copy(preprocessing=PcmPreprocessing.LEGACY),l.copy(interval=AudioIntervalUs(0,1)),l.copy(activeFraction=.1))){assertEquals(NativeDjContentGains(),NativeDjGain.content(p,bad,level(false,.5)))}}
    @Test fun allCurvesAreFiniteMonotonicContinuousAtEndpointsAndPeakBounded(){
        for(curve in NativeDjCurve.entries){var previous=NativeDjGain.gains(0.0,curve)
            assertEquals(1.0,previous.outgoing,1e-12);assertEquals(0.0,previous.incoming,1e-12)
            for(i in 1..10000){val next=NativeDjGain.gains(i/10000.0,curve)
                assertTrue(next.outgoing.isFinite() && next.incoming.isFinite());assertTrue(next.outgoing+next.incoming<=1+1e-12)
                assertTrue(next.outgoing<=previous.outgoing+1e-12);assertTrue(next.incoming>=previous.incoming-1e-12)
                assertTrue(abs(next.incoming-previous.incoming)<.001);previous=next}
            assertEquals(1.0,previous.incoming,1e-12);assertEquals(0.0,previous.outgoing,1e-12)
        }
    }
    @Test fun bothDecksRemainSignificantAcrossMiddleThird(){for(i in 34..66){val g=NativeDjGain.gains(i/100.0,NativeDjCurve.RESTRAINED_DJ);assertTrue(g.incoming>.4 && g.outgoing>.4)}}
    @Test fun contentCorrectionRampsOutBeforePromotion(){val c=NativeDjContentGains(.708,.708);val end=NativeDjGain.gains(1.0,NativeDjCurve.RESTRAINED_DJ,c);val near=NativeDjGain.gains(.99999,NativeDjCurve.RESTRAINED_DJ,c);assertEquals(1.0,end.incoming,0.0);assertTrue(abs(end.incoming-near.incoming)<1e-6)}
    @Test fun invalidNumbersCannotBecomePlayerVolume(){for(x in listOf(Double.NaN,Double.POSITIVE_INFINITY)){assertThrows(IllegalArgumentException::class.java){NativeDjGain.gains(x,NativeDjCurve.LINEAR)}}}
    @Test fun activeMedianExcludesLongLeadingSilenceAndQuietTail(){
        val pcm=FloatArray(22050*20){i ->if(i in 22050*8 until 22050*16)(.2*sin(2*PI*440*i/22050)).toFloat() else 0f}
        val w=NativePlanningFixtures.realWindow(pcm,0,"in")
        val l=NativeDjLevel.measure(w)
        assertEquals(.2/sqrt(2.0),l.activeRms,.0001);assertEquals(.4,l.activeFraction,.001)
    }
    @Test fun levelMeasurementChecksCancellationAndBadPcm(){val w=NativePlanningFixtures.realWindow(FloatArray(22050){.1f},0,"in");assertThrows(java.util.concurrent.CancellationException::class.java){NativeDjLevel.measure(w,AnalysisControl(cancellation=AnalysisCancellation{throw java.util.concurrent.CancellationException()}))};assertThrows(IllegalArgumentException::class.java){NativeDjLevel.measure(w.copy(samples=FloatArray(22050){Float.NaN}))}}
    @Test fun partialOrLegacyPcmCannotProvideLevelEvidence(){val w=NativePlanningFixtures.realWindow(FloatArray(22050){.1f},0,"in");assertThrows(IllegalArgumentException::class.java){NativeDjLevel.measure(w.copy(coverage=AudioCoverage(emptyList(),0)))};assertThrows(IllegalArgumentException::class.java){NativeDjLevel.measure(w.copy(preprocessingVersion=PcmPreprocessing.LEGACY))}}
    @Test fun pcmCorpusCorrelatedUncorrelatedQuietOffsetAndTransientSignalsStayBounded(){
        for(seconds in listOf(1,16,30))for(kind in listOf("correlated","uncorrelated","quietTail","quietIncoming","offset","sudden","silence")){
            var peak=0.0;var sum=0.0;var count=0
            for(i in 0 until 22050*seconds){val t=i/22050.0;val p=i.toDouble()/(22050*seconds-1)
                val a=if(kind=="silence")0.0 else sin(2*PI*220*t)*(if(kind=="quietTail") .001 else .8)
                val b=if(kind=="silence")0.0 else sin(2*PI*(if(kind=="uncorrelated")337 else 220)*t+(if(kind=="offset")1.3 else 0.0))*(if(kind=="quietIncoming") .001 else if(kind=="sudden" && p<.5).1 else .9)
                val g=NativeDjGain.gains(p,NativeDjCurve.RESTRAINED_DJ);val y=a*g.outgoing+b*g.incoming
                assertTrue(y.isFinite());peak=max(peak,abs(y));sum+=y*y;count++
            }
            assertTrue("$kind/$seconds peak=$peak",peak<=1.0);assertTrue(sqrt(sum/count)<=1.0)
        }
    }
}
