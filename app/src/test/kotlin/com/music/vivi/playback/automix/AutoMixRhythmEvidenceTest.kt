package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AutoMixRhythmEvidenceTest {
    private fun drums(accented: Boolean, compressed: Boolean): FloatArray {
        val rate=22050
        return FloatArray(rate*16) { i ->
            val time=i.toDouble()/rate
            val beat=(time/0.5).toInt()
            val local=time%0.5
            val kick=if(local<0.08) sin(2*PI*85*local)*exp(-local*45)*(if(accented && beat%4==0)0.8 else 0.35) else 0.0
            ((if(compressed)0.45*sin(2*PI*440*time) else 0.0)+kick*(if(compressed)0.25 else 1.0)).toFloat()
        }
    }
    @Test fun compressedDrumsWithSmallRmsVariationStillHaveTransientEvidence() {
        val f=TrackFeaturesAnalyzer().analyze(drums(false,true),22050,0,60000)
        assertEquals(120.0,f.bpm,3.0)
        assertTrue(f.beatConfidence>=0.6)
        assertTrue(f.phaseConfidence>=0.4)
    }
    @Test fun uniformBeatsDoNotInventBarOrPhraseEvidence() {
        val f=TrackFeaturesAnalyzer().analyze(drums(false,false),22050,0,60000)
        assertTrue(f.sections.single().downbeatsMs.isEmpty())
        assertEquals(0.0,f.sections.single().downbeatConfidence,0.0)
    }
    @Test fun repeatedBassAccentsProvideMeasuredBarEvidence() {
        val f=TrackFeaturesAnalyzer().analyze(drums(true,false),22050,0,60000)
        val section=f.sections.single()
        assertTrue(section.downbeatConfidence>=0.25)
        assertTrue(section.downbeatsMs.size>=4)
        assertTrue(section.downbeatsMs.zipWithNext().all { abs((it.second-it.first)-2000)<=50 })
    }
    @Test fun coarseAndDetailedPassesKeepSameTempoAndPhaseQuality() {
        val pcm=drums(false,false)
        val a=TrackFeaturesAnalyzer().analyze(pcm,22050,10000,60000,detail=AnalysisDetail.SCAN)
        val b=TrackFeaturesAnalyzer().analyze(pcm,22050,10000,60000,detail=AnalysisDetail.DETAIL)
        assertEquals(a.bpm,b.bpm,0.0); assertEquals(a.beatsMs,b.beatsMs)
        assertEquals(a.phaseConfidence,b.phaseConfidence,0.0)
        assertTrue(a.energy.size<b.energy.size)
    }
}
