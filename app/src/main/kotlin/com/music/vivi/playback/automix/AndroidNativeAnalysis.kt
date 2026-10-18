// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Explicit native entry points. MusicService uses planning analysis only after experimental opt-in. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AndroidNativeAnalysis {
    /** Shared Phase 3.1/3.3 explicit planning entry point. Does not create workers or start background work. */
    fun createPlanningAnalysis() = NativePlanningAnalysis(AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED))

    fun create() = ExperimentalNativeAnalysis(
        WindowAnalysisPipeline(AndroidWindowDecoder(AnalysisPreprocessing.BANDLIMITED),
            BitChordNativeAnalysisEngine(), PcmPreprocessing.BANDLIMITED),
        WindowAnalysisPipeline(AndroidWindowDecoder(AnalysisPreprocessing.LEGACY),
            LegacyKotlinAnalysisEngine(), PcmPreprocessing.LEGACY),
    )
}
