package com.music.vivi.playback.automix

import androidx.datastore.preferences.core.*
import com.music.vivi.constants.*
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AutoMixPreferencesTest {
    @Test fun existingInstallDefaultsToOffAndBalanced() {
        val state=readAutoMixSettings(emptyPreferences())
        assertFalse(state.enabled)
        assertEquals(AutoMixPerformance.BALANCED,state.performance)
        assertFalse(state.showPerformance)
        assertTrue(state.crossfadeEditable)
    }
    @Test fun toggleMasksControlsWithoutResettingStoredCrossfade() {
        val prefs=mutablePreferencesOf(CrossfadeEnabledKey to true,CrossfadeDurationKey to 7f)
        prefs[AutoMixEnabledKey]=true
        val enabled=readAutoMixSettings(prefs)
        assertTrue(enabled.showPerformance)
        assertFalse(enabled.crossfadeEditable)
        assertFalse(mixingPolicy(enabled.enabled,prefs[CrossfadeEnabledKey]!!,true).crossfade)
        assertTrue(prefs[CrossfadeEnabledKey]!!)
        assertEquals(7f,prefs[CrossfadeDurationKey]!!,0f)
        prefs[AutoMixEnabledKey]=false
        assertTrue(readAutoMixSettings(prefs).crossfadeEditable)
        assertTrue(mixingPolicy(false,prefs[CrossfadeEnabledKey]!!,false).crossfade)
    }
    @Test fun modeAndPreferencesSurviveTheActualDataStoreSerializationFormat() = runBlocking {
        for(mode in AutoMixPerformance.entries) {
            val prefs=mutablePreferencesOf(AutoMixEnabledKey to true,AutoMixPerformanceKey to mode.name,
                CrossfadeEnabledKey to true,AudioOffload to true,AudioNormalizationKey to true)
            val buffer=Buffer()
            PreferencesSerializer.writeTo(prefs,buffer)
            val restored=PreferencesSerializer.readFrom(buffer)
            assertEquals(AutoMixSettings(true,mode),readAutoMixSettings(restored))
            assertTrue(restored[CrossfadeEnabledKey]!!)
            assertTrue(restored[AudioOffload]!!)
            assertTrue(restored[AudioNormalizationKey]!!)
        }
    }
    @Test fun unknownModeUsesBalancedAndNeverEnablesAutoMix() {
        val prefs=mutablePreferencesOf(AutoMixPerformanceKey to "obsolete")
        assertEquals(AutoMixSettings(),readAutoMixSettings(prefs))
    }
}
