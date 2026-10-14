package com.music.vivi.playback.automix

import androidx.datastore.preferences.core.Preferences
import com.music.vivi.constants.AutoMixEnabledKey
import com.music.vivi.constants.AutoMixPerformanceKey

data class AutoMixSettings(val enabled: Boolean = false, val performance: AutoMixPerformance = AutoMixPerformance.BALANCED) {
    val showPerformance get() = enabled
    val crossfadeEditable get() = !enabled
}

fun readAutoMixSettings(preferences: Preferences) = AutoMixSettings(
    preferences[AutoMixEnabledKey] ?: false,
    preferences[AutoMixPerformanceKey]?.let { runCatching { AutoMixPerformance.valueOf(it) }.getOrNull() }
        ?: AutoMixPerformance.BALANCED,
)
