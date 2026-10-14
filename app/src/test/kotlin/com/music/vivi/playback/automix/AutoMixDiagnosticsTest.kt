package com.music.vivi.playback.automix

import androidx.datastore.preferences.core.*
import androidx.media3.common.C
import androidx.media3.common.Format
import com.music.vivi.constants.*
import com.music.vivi.playback.AudioDiagnostics
import com.music.vivi.ui.component.timelineMarkerX
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AutoMixDiagnosticsTest {
    private val plan=AutoMixPlan(181000,8000,16000,technique=MixTechnique.BEAT_BLEND,phaseConfidence=0.85)
    private val ready=AutoMixStatus(AutoMixPhase.READY,"out","in",216000,plan)
    @Test fun markerUsesRealPairAndTimelineAndHidesInvalidOrFallbackPlans() {
        assertEquals(181f/216,ready.markerFraction("out","in",216000)!!,0.00001f)
        for(state in listOf(ready.copy(phase=AutoMixPhase.REFINING),ready.copy(phase=AutoMixPhase.MIXING),
            ready.copy(plan=null),ready.copy(plan=plan.copy(technique=MixTechnique.SAFE_FADE)),
            ready.copy(outgoingId="old"),ready.copy(incomingId="changed"))) {
            assertNull(state.markerFraction("out","in",216000))
        }
        assertNull(ready.markerFraction("out","in",C.TIME_UNSET))
        assertNull(ready.markerFraction("out","in",0))
        assertNull(ready.markerFraction("out","in",150000))
        assertNull(ready.markerFraction("in","next",216000))
    }
    @Test fun markerTracksRevisionsAndInvalidatesAfterDisableOrMissedDeadline() {
        assertEquals(150f/216,ready.copy(plan=plan.copy(outgoingStartMs=150000)).markerFraction("out","in",216000)!!,0.00001f)
        for(phase in listOf(AutoMixPhase.DISABLED,AutoMixPhase.UNAVAILABLE,AutoMixPhase.WAITING_NEXT)) {
            assertNull(ready.copy(phase=phase).markerFraction("out","in",216000))
        }
    }
    @Test fun sliderGeometryMatchesSlimWavesMaterialAndExpressiveIncludingRtl() {
        assertEquals(250f,timelineMarkerX(0.5f,500f,0f,false),0f)
        assertEquals(181f/216*500,timelineMarkerX(181f/216,500f,0f,false),0.001f)
        assertEquals(2f,timelineMarkerX(0f,500f,2f,false),0f)
        assertEquals(498f,timelineMarkerX(1f,500f,2f,false),0f)
        assertEquals(8f,timelineMarkerX(0f,500f,8f,false),0f)
        assertEquals(492f,timelineMarkerX(1f,500f,8f,false),0f)
        assertEquals(2f,timelineMarkerX(1f,500f,2f,true),0f)
        assertEquals(5f,timelineMarkerX(0.5f,10f,8f,false),0f)
    }
    @Test fun qualityUsesRendererValuesAndNeverInventsDepthBitrateOrLossless() {
        val f=Format.Builder().setSampleMimeType("audio/opus").setSampleRate(48000).setChannelCount(2).setAverageBitrate(128000).build()
        val actual=AudioDiagnostics.from(f)
        assertEquals(listOf("opus","48.0 kHz","128 kbps","2 ch"),actual.fields())
        assertNull(actual.pcmBits)
        assertFalse(actual.fields().joinToString().contains("Lossless"))
        assertTrue(AudioDiagnostics.from(null).fields().isEmpty())
        val unknown=AudioDiagnostics.from(Format.Builder().setSampleMimeType("audio/flac").build())
        assertEquals(listOf("flac"),unknown.fields());assertNull(unknown.pcmBits)
        assertNull(unknown.bitrate);assertNull(unknown.sampleRate)
        val output=actual.copy(outputSampleRate=44100,outputEncoding=C.ENCODING_PCM_16BIT)
        assertEquals(48000,output.sampleRate);assertEquals(44100,output.outputSampleRate)
        assertNull(output.pcmBits) // output PCM depth must not turn Opus into a 16-bit source
    }
    @Test fun appearancePreferencesPersistIndependentlyOfAutoMixAndCrossfade() = runBlocking {
        val empty=emptyPreferences()
        assertFalse(empty[ShowAutoMixTransitionKey] ?: false)
        assertFalse(empty[StatsForNerdsKey] ?: false)
        val prefs=mutablePreferencesOf(ShowAutoMixTransitionKey to true,StatsForNerdsKey to true,
            AutoMixEnabledKey to false,AutoMixPerformanceKey to AutoMixPerformance.EFFICIENT.name,
            CrossfadeEnabledKey to true,CrossfadeDurationKey to 8f)
        val buffer=Buffer();PreferencesSerializer.writeTo(prefs,buffer)
        val restored=PreferencesSerializer.readFrom(buffer)
        assertTrue(restored[StatsForNerdsKey]!!);assertTrue(restored[ShowAutoMixTransitionKey]!!)
        assertFalse(restored[AutoMixEnabledKey]!!);assertTrue(restored[CrossfadeEnabledKey]!!)
        assertEquals(8f,restored[CrossfadeDurationKey]!!,0f)
        assertEquals(AutoMixPerformance.EFFICIENT,readAutoMixSettings(restored).performance)
    }
}
