package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

internal fun outroFeatures(duration: Long=180000, change: Long=145000, afterVoice: Double=0.1,
                           bpm: Double=120.0, phase: Double=0.95): TrackFeatures {
    val start=(duration-90000).coerceAtLeast(0)
    val energy=(start..duration step 100).map { t -> EnergyPoint(t,if(t<change)0.22 else 0.025,if(t<change)0.85 else afterVoice) }
    return TrackFeatures(duration,start,duration-start,bpm,0.95,(start..duration step (60000/bpm).toLong()).toList(),
        0.12,0.7,MusicalKey(0,false,0.8),energy,phase,0.95,
        listOf(RhythmSection(start,duration,bpm,0.95,phase,0.95,true)),detectSectionBoundaries(energy,0.95))
}
internal fun introFeatures(silence: Long=8000, voice: Double=0.1, bpm: Double=120.0): TrackFeatures {
    val energy=(0L..32000 step 100).map { t -> EnergyPoint(t,if(t<silence)0.001 else 0.07,voice) }
    return TrackFeatures(180000,0,32000,bpm,0.95,(0L..32000 step (60000/bpm).toLong()).toList(),
        0.06,0.5,MusicalKey(0,false,0.8),energy,0.95,0.95,
        listOf(RhythmSection(0,32000,bpm,0.95,0.95,0.95,true)),detectSectionBoundaries(energy,0.95))
}
class AutoMixQualityTest {
    private val planner=AutoMixPlanner()
    @Test fun sustainedInstrumentalOutroCanFinishTheMixWellBeforeTheFileEnds() {
        val p=planner.plan(outroFeatures(),introFeatures(),180000,180000)!!
        assertTrue(p.outgoingStartMs<=150000)
        assertEquals(8000L,p.incomingCueMs)
        assertTrue(p.durationMs>=8000)
        assertTrue(p.outgoingStartMs+p.durationMs<180000-10000)
        assertEquals("measured-sustained-outro",p.reason)
        assertEquals(MixTechnique.BEAT_BLEND,p.technique) // the measured quiet outro does not need bass carving
    }
    @Test fun laterCueDoesNotSkipImportantVocalIntro() {
        val p=planner.plan(outroFeatures(),introFeatures(silence=0,voice=0.9),180000,180000)!!
        assertEquals(0L,p.incomingCueMs)
        assertTrue(p.outgoingStartMs>=168000)
    }
    @Test fun quietLeadInAndStrongDrumEntryCanChooseALaterCue() {
        val p=planner.plan(outroFeatures(),introFeatures(silence=10000),180000,180000)!!
        assertEquals(10000L,p.incomingCueMs)
    }
    @Test fun vocalEndingAndMusicalBuildupPreventEarlyCuts() {
        for(out in listOf(outroFeatures(afterVoice=0.9),outroFeatures().let { f ->
            f.copy(energy=f.energy.map { if(it.positionMs>155000) it.copy(rms=0.2) else it })
        })) {
            val p=planner.plan(out,introFeatures(0),180000,180000)!!
            assertTrue(p.outgoingStartMs>=168000)
        }
    }
    @Test fun evenABriefLaterVocalReturnPreventsCuttingTheRemainingOutro() {
        val out=outroFeatures().let { f -> f.copy(energy=f.energy.map {
            if(it.positionMs in 174000..176000) it.copy(vocalRisk=0.9) else it
        }) }
        assertTrue(planner.plan(out,introFeatures(0),180000,180000)!!.outgoingStartMs>=168000)
    }
    @Test fun uniformRepetitiveInstrumentalMaterialAloneDoesNotAuthorizeAnEarlyCut() {
        val out=outroFeatures().let { f -> f.copy(energy=f.energy.map { it.copy(rms=0.1,vocalRisk=0.1) },boundaries=emptyList()) }
        assertTrue(planner.plan(out,introFeatures(0),180000,180000)!!.outgoingStartMs>=168000)
    }
    @Test fun incompatibleKeyOrTempoDoesNotForceAnEarlyBeatMix() {
        val candidates=listOf(introFeatures(0).copy(key=MusicalKey(6,false,0.9)),introFeatures(0,bpm=80.0))
        for(incoming in candidates) {
            val p=planner.plan(outroFeatures(),incoming,180000,180000)!!
            assertTrue(p.outgoingStartMs>=168000); assertEquals(1f,p.incomingRate,0f)
        }
    }
    @Test fun lowPhaseConfidenceAndTempoChangesAcrossOverlapRejectEarlyMixing() {
        val varying=outroFeatures().copy(sections=listOf(
            RhythmSection(90000,150000,120.0,0.95,0.95,0.95,true),
            RhythmSection(150000,180000,134.0,0.95,0.95,0.95,true)))
        for(out in listOf(outroFeatures(phase=0.1),varying))
            assertTrue(planner.plan(out,introFeatures(0),180000,180000)!!.outgoingStartMs>=168000)
    }
    @Test fun differentEvidenceProducesDifferentOverlapLengths() {
        val early=planner.plan(outroFeatures(),introFeatures(0),180000,180000)!!
        val vocal=planner.plan(features(voice=0.9),features(voice=0.9),60000,60000)!!
        assertTrue(early.durationMs>=8000); assertEquals(1500L,vocal.durationMs)
        assertNull(planner.plan(features(),features(),10000,10000))
    }
    @Test fun partialWindowsRetainTheirLocalTempoInsteadOfRejectingTheEntireTrack() {
        val a=outroFeatures(bpm=80.0).copy(startMs=90000,lengthMs=30000,
            sections=listOf(RhythmSection(90000,120000,80.0,0.8,0.8,0.8,false)))
        val b=outroFeatures().copy(startMs=150000,lengthMs=30000,
            sections=listOf(RhythmSection(150000,180000,120.0,0.95,0.95,0.95,true)))
        val merged=mergeTrackFeatures(listOf(a,b),true)!!
        assertEquals(2,merged.sections.size)
        assertTrue(merged.sections.any { it.bpm==120.0 && it.confidence>=0.9 })
        assertEquals(1,mergeTrackFeatures(listOf(b),true)!!.sections.size)
    }
    @Test fun staleOrUnrefinedEarlyCandidatesAreRejected() {
        val out=outroFeatures().let { it.copy(sections=it.sections.map { s -> s.copy(detailed=false) }) }
        val p=planner.plan(out,introFeatures(),180000,180000,requireDetailed=true)!!
        assertEquals(MixTechnique.SAFE_FADE,p.technique)
        assertTrue(planner.lastRejections.containsKey("selected-region-not-refined"))
        val late=planner.plan(outroFeatures(),introFeatures(),180000,180000,earliestStartMs=160000)!!
        assertTrue(late.outgoingStartMs>=168000)
    }
    @Test fun candidateScoringChoosesEvidenceRatherThanLastTimestamp() {
        val out=outroFeatures().let { f -> f.copy(boundaries=f.boundaries+SectionBoundary(160000,0.71,0.04,0.025,0.3,0.1)) }
        val p=planner.plan(out,introFeatures(0),180000,180000)!!
        assertTrue(p.outgoingStartMs<155000); assertTrue(p.score>0.7)
    }
}
