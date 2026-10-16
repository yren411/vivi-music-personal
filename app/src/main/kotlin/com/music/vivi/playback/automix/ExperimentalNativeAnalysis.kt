// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Internal opt-in result: fallback provenance is explicit; no result is stored under an attempted profile. */
data class ExperimentalAnalysisAttempt(
    val result: AnalysisResult,
    val attemptedProfiles: List<AnalysisProfile>,
    val nativeFailure: AnalysisFailure? = null,
) {
    val effectiveProfile: AnalysisProfile? get() = (result as? AnalysisResult.Completed)?.metadata?.let {
        AnalysisProfile(it.backend, it.preprocessingVersion)
    }
    fun completedCacheKey(): String? = (result as? AnalysisResult.Completed)?.let {
        // Existing feature-cache coordinates are milliseconds. Never truncate a future
        // sub-millisecond request into a colliding key; experimental attempts aren't cached.
        if (it.metadata.requested.startUs % 1000 != 0L || it.metadata.requested.endUs % 1000 != 0L) return null
        effectiveProfile!!.cacheKey(it.metadata.source, it.metadata.requested.startUs / 1000,
            (it.metadata.requested.endUs - it.metadata.requested.startUs) / 1000, it.metadata.detail)
    }
}

/** Sequential work on the caller's EXISTING worker, no executor, nested futures or PCM cache.
 * Experimental attempts deliberately bypass feature-cache insertion: a legacy fallback must
 * never become a native cache hit. A future cache consumer must use completedCacheKey only. */
class ExperimentalNativeAnalysis<S>(
    private val native: WindowAnalysisPipeline<S>,
    private val legacy: WindowAnalysisPipeline<S>,
) {
    init {
        require(native.profile == BitChordNativeAnalysisEngine.PROFILE)
        require(legacy.profile == LegacyKotlinAnalysisEngine.PRODUCTION_PROFILE)
    }
    fun analyze(source: S, request: DecodeRequest, detail: AnalysisDetail, control: AnalysisControl): ExperimentalAnalysisAttempt {
        val result = native.analyze(source, request, detail, control)
        val failure = (result as? AnalysisResult.Failed)?.failure
        // Never retry analytical uncertainty, decoding/input failures, memory pressure, cancellation or timeouts.
        if (failure == null || failure.category !in setOf(AutoMixFailure.NATIVE_UNAVAILABLE,
                AutoMixFailure.NATIVE_FAILED, AutoMixFailure.NATIVE_INVALID_OUTPUT) ||
            failure.code !in setOf("NATIVE_LIBRARY_UNAVAILABLE", "NATIVE_VERSION_MISMATCH",
                "NATIVE_LINKAGE_FAILURE", "NATIVE_NULL_PACKET", "NATIVE_INTERNAL_ERROR", "NATIVE_INVALID_OUTPUT",
                "NATIVE_UNKNOWN_STATUS", "NATIVE_TIMESTAMP_OVERFLOW"))
            return ExperimentalAnalysisAttempt(result, listOf(native.profile))
        try { control.checkpoint() } catch (error: Exception) {
            return ExperimentalAnalysisAttempt(AnalysisResult.Failed(native.profile.backend,
                AnalysisFailure(AnalysisFailureStage.ANALYSIS, AutoMixFailure.of(error), AutoMixFailure.of(error).name)),
                listOf(native.profile), failure)
        }
        // Explicit second DECODE using legacy preprocessing, same authorization/source path.
        return ExperimentalAnalysisAttempt(legacy.analyze(source, request, detail, control),
            listOf(native.profile, legacy.profile), failure)
    }
    fun submit(workers: AnalysisWorkers<ExperimentalAnalysisAttempt>, source: S, request: DecodeRequest,
               detail: AnalysisDetail, control: AnalysisControl): java.util.concurrent.Future<ExperimentalAnalysisAttempt> {
        // Versioned source/profile digest plus exact microseconds: no URL or truncated-time collision.
        val identity = native.profile.cacheKey(request.identity, 0, 0, detail)
        val key = "$identity:experimental-uncached:${request.interval.startUs}:${request.interval.endUs}"
        return workers.submit(key, cacheable = false) { analyze(source, request, detail, control) }
    }
}
