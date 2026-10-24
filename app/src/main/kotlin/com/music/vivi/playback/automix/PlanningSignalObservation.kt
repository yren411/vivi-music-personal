// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Optional corroboration of BitChord's grid, NOT another tempo detector or vocal classifier.
 * Absolute 20 ms RMS-rise event timestamps, plus measured below--60 dBFS leading material.
 * No raw PCM survives. The native confidence is neither modified nor calibrated here. */
data class PlanningSignalObservation(
    val source: AnalysisSourceIdentity,
    val profile: AnalysisProfile,
    val interval: AudioIntervalUs,
    val version: String,
    val leadingQuietEndUs: Long?,
    val riseEventsUs: List<Long>,
    val complete: Boolean,
    val windowRms: Double,
    val windowPeak: Double,
) {
    companion object {
        const val VERSION = "pcm-rms20ms-rise25pct-quiet60db-v1"
        fun measure(window: DecodedWindow, control: AnalysisControl): PlanningSignalObservation {
            require(window.invalidReason() == null && window.format == PcmFormat(22050, 1, PcmEncoding.FLOAT32))
            require(window.samples.size <= 22050 * 32 && window.preprocessingVersion == PcmPreprocessing.BANDLIMITED)
            val span = requireNotNull(window.sampleInterval)
            require(window.coverage.status(span) == CoverageStatus.FULL)
            var squares = 0.0
            var peak = 0.0
            window.samples.forEachIndexed { index, f ->
                if (index % 4096 == 0) control.checkpoint()
                require(f.isFinite())
                val value = f.toDouble()
                squares += value * value
                peak = max(peak, abs(value))
            }
            val rms = sqrt(squares / window.samples.size)
            val events = ArrayList<Long>()
            var previous = 0.0
            var quietEnd = span.startUs
            var stillQuiet = true
            var complete = true
            val blockFrames = 441 // exactly 20 ms at canonical 22050 Hz
            var frame = 0
            while (frame < window.samples.size) {
                control.checkpoint()
                val end = minOf(frame + blockFrames, window.samples.size)
                var sum = 0.0
                var blockPeak = 0.0
                for (index in frame until end) {
                    val value = window.samples[index].toDouble()
                    sum += value * value
                    blockPeak = max(blockPeak, abs(value))
                }
                val level = sqrt(sum / (end - frame))
                val position = Math.addExact(span.startUs, frame.toLong() * 1_000_000 / 22050)
                if (level - previous >= max(0.001, rms * 0.25)) {
                    if (events.size < 512) events.add(position) else complete = false
                }
                if (stillQuiet && blockPeak < 0.001) {
                    quietEnd = Math.addExact(span.startUs, end.toLong() * 1_000_000 / 22050)
                } else stillQuiet = false
                previous = level
                frame = end
            }
            control.checkpoint()
            return PlanningSignalObservation(window.identity, BitChordNativeAnalysisEngine.PROFILE,
                span, VERSION, if (span.startUs == 0L) quietEnd else null, events.toList(), complete, rms, peak)
        }
    }
}

/** Captures compact observations only in an explicitly invoked experimental job. */
class NativePlanningAnalysis<S>(
    private val decoder: AnalysisWindowDecoder<S>,
    private val engine: AnalysisEngine = BitChordNativeAnalysisEngine(),
    val adaptive:Boolean=false,
) {
    private val executedDecodes=java.util.concurrent.atomic.AtomicLong()
    private val decodedSourceFrames=java.util.concurrent.atomic.AtomicLong()
    val actualDecoderCalls get()=executedDecodes.get()
    val measuredDecodedSourceFrames get()=decodedSourceFrames.get()
    init { require(engine.backend == BitChordNativeAnalysisEngine.BACKEND) }
    fun analyze(source: S, request: DecodeRequest, detail: AnalysisDetail, control: AnalysisControl): PlanningWindowEvidence {
        var observation: PlanningSignalObservation? = null
        var level:NativeDjLevel?=null
        val began=System.nanoTime()
        var selected=request
        var decodes=0
        var frames=0L
        val observedDecoder = AnalysisWindowDecoder<S> { input, wanted, jobControl ->
            fun decode(r:DecodeRequest):DecodeResult {
                jobControl.checkpoint();decodes++;executedDecodes.incrementAndGet()
                return decoder.decode(input,r,jobControl).also {v ->
                    val measured=when(v) {is DecodeResult.Decoded->v.window.decoderDiagnostic?.codecOutputFrames ?: v.window.coverage.acceptedDecodedFrames ?: 0L
                        is DecodeResult.Failed->v.diagnostic?.codecOutputFrames ?: 0L}
                    frames+=measured;decodedSourceFrames.addAndGet(measured)
                }
            }
            var decoded=decode(wanted)
            if(adaptive && decoded is DecodeResult.Decoded) {
                val window=decoded.window
                // Discovery is not permission to relabel partial PCM. Verify a new request.
                if(window.identity==wanted.identity && window.requested==wanted.interval) {
                    AdaptiveNativeRegions.supportedInset(window)?.let {span ->
                        selected=wanted.copy(interval=span);decoded=decode(selected)
                    }
                }
            }
            if(adaptive && decoded is DecodeResult.Decoded && decoded.window.coverage.status(selected.interval)!=CoverageStatus.FULL)
                decoded=DecodeResult.Failed(AnalysisFailure(AnalysisFailureStage.INPUT_VALIDATION,AutoMixFailure.INSUFFICIENT_AUDIO_DATA,"PARTIAL_SOURCE_COVERAGE"))
            decoded.also { result ->
                if (result is DecodeResult.Decoded && result.window.invalidReason() == null &&
                    result.window.preprocessingVersion == PcmPreprocessing.BANDLIMITED &&
                    result.window.coverage.status(result.window.decodeInterval) == CoverageStatus.FULL) {
                    observation = PlanningSignalObservation.measure(result.window, jobControl)
                    level=try {NativeDjLevel.measure(result.window,jobControl)}catch(_:IllegalArgumentException){null}
                }
            }
        }
        val decoded=try {observedDecoder.decode(source,request,control)} catch(error:Exception) {
            DecodeResult.Failed(AnalysisFailure(AnalysisFailureStage.DECODING,AutoMixFailure.of(error),
                if(error is AnalysisUnavailable)error.reason else AutoMixFailure.of(error).name,error))
        }
        val result = WindowAnalysisPipeline(AnalysisWindowDecoder<S>{_,_,_->decoded}, engine, PcmPreprocessing.BANDLIMITED)
            .analyze(source, selected, detail, control)
        return PlanningWindowEvidence(result, if (result is AnalysisResult.Completed) observation else null,
            if(result is AnalysisResult.Completed)level else null,
            if(adaptive)NativeRegionAudit(AdaptiveNativeRegions.VERSION,request.interval,selected.interval,decodes,
                (System.nanoTime()-began)/1000,frames) else null)
    }
}
