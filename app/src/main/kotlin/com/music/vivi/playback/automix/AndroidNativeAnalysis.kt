// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Deliberate internal/test entry point only. MusicService does NOT construct this class. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AndroidNativeAnalysis {
    /** Separate explicit Phase 3.1 entry point. Does not create workers or start background work. */
    fun createPlanningAnalysis() = NativePlanningAnalysis(AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED))

    fun create() = ExperimentalNativeAnalysis(
        WindowAnalysisPipeline(AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED),
            BitChordNativeAnalysisEngine(), PcmPreprocessing.BANDLIMITED),
        WindowAnalysisPipeline(AndroidWindowDecoder(AnalysisPreprocessing.LEGACY),
            LegacyKotlinAnalysisEngine(), PcmPreprocessing.LEGACY),
    )
}
