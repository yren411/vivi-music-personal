package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test

internal fun features(bpm: Double = 120.0, start: Long = 0, confidence: Double = 0.9,
                      voice: Double = 0.1, key: MusicalKey? = MusicalKey(0,false,0.8), rms: Double = 0.1) =
    TrackFeatures(60000,start,32000,bpm,confidence,(0L..60000 step (60000 / bpm).toLong()).toList(),
        rms,0.6,key,(0L..60000 step 100).map { EnergyPoint(it,rms,voice) })

class AutoMixPlannerTest {
    private val planner = AutoMixPlanner()
    @Test fun compatibleTemposUseMeasuredEightBeatOverlap() {
        val p = planner.plan(features(),features(124.0),60000,60000)!!
        assertEquals(MixTechnique.BASS_HANDOFF,p.technique)
        assertEquals(120f/124,p.incomingRate,0.001f)
        assertEquals(4000L,p.durationMs)
        assertEquals(56000L,p.outgoingStartMs)
    }
    @Test fun slowerTempoHasDifferentTransitionLength() {
        assertEquals(8000L,planner.plan(features(60.0),features(60.0),60000,60000)!!.durationMs)
    }
    @Test fun incompatibleTemposDoNotStretch() {
        val p=planner.plan(features(80.0),features(150.0),60000,60000)!!
        assertEquals(1f,p.incomingRate,0f)
        assertEquals(MixTechnique.ENERGY_FADE,p.technique)
    }
    @Test fun conflictingKeysShortenAndFilterTheBlend() {
        val p=planner.plan(features(),features(key=MusicalKey(6,false,0.9)),60000,60000)!!
        assertEquals(MixTechnique.GENTLE_FILTER,p.technique)
        assertEquals(2000L,p.durationMs)
        assertEquals(1f,p.incomingRate,0f)
    }
    @Test fun relativeKeysAndFifthsAreCompatible() {
        val c=MusicalKey(0,false,0.8)
        assertTrue(planner.compatibleKeys(c,MusicalKey(9,true,0.8)))
        assertTrue(planner.compatibleKeys(c,MusicalKey(7,false,0.8)))
        assertFalse(planner.compatibleKeys(c,MusicalKey(6,false,0.8)))
    }
    @Test fun overlappingVocalRiskAvoidsLongBeatBlend() {
        val p=planner.plan(features(voice=0.9),features(voice=0.9),60000,60000)!!
        assertEquals(1500L,p.durationMs)
        assertEquals(MixTechnique.ENERGY_FADE,p.technique)
    }
    @Test fun lowConfidenceNeverAuthorizesTempoMatching() {
        val p=planner.plan(features(confidence=0.1),features(confidence=0.1),60000,60000)!!
        assertEquals(1f,p.incomingRate,0f)
        assertEquals(MixTechnique.ENERGY_FADE,p.technique)
    }
    @Test fun missingInformationUsesItsOwnSafeFadeOrNormalPlayback() {
        assertEquals(MixTechnique.SAFE_FADE,planner.plan(null,null,60000,60000)!!.technique)
        assertNull(planner.plan(null,null,0,60000))
        assertNull(planner.plan(null,null,60000,0))
        assertNull(planner.plan(features(),features(),10000,60000))
    }
    @Test fun loudnessIsAttenuatedNeverBoosted() {
        val p=planner.plan(features(rms=0.1),features(rms=0.2),60000,60000)!!
        assertEquals(1.0,p.outgoingLevel,0.0)
        assertEquals(0.5,p.incomingLevel,0.0)
    }
    @Test fun silenceDoesNotForceAMix() {
        assertNull(planner.plan(features().copy(peak=0.0),features(),60000,60000))
    }
    @Test fun storedCrossfadeChoiceIsMaskedNotLost() {
        assertEquals(MixingPolicy(false,true,false),mixingPolicy(false,true,true))
        assertEquals(MixingPolicy(true,false,false),mixingPolicy(true,true,true))
        assertEquals(MixingPolicy(false,true,false),mixingPolicy(false,true,true))
        assertEquals(MixingPolicy(false,false,false),mixingPolicy(true,true,true,true))
        assertEquals(MixingPolicy(false,false,true),mixingPolicy(false,false,true))
    }
}
