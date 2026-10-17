// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlin.math.abs
import kotlin.math.roundToLong

internal class PlanningEvidenceRejected(val reason: PlanningRejection, val analysisFailure: AnalysisFailure? = null) : Exception()

internal data class NativePlanningObservations(
    val track: PlanningTrack,
    val profile: AnalysisProfile,
    val sampleInterval: AudioIntervalUs,
    val bpm: Double,
    val medianPeriodUs: Long,
    val beats: List<Long>,
    val heuristicDownbeats: Set<Long>,
    val rawNativeConfidence: Double,
    val signal: PlanningSignalObservation?,
)

internal object NativePlanningEvidence {
    fun validate(track: PlanningTrack, window: PlanningWindowEvidence, control: AnalysisControl): NativePlanningObservations {
        fun reject(reason: PlanningRejection): Nothing = throw PlanningEvidenceRejected(reason)
        control.checkpoint()
        val result = window.analysis
        if (result is AnalysisResult.Failed) throw PlanningEvidenceRejected(when(result.failure.category) {
            AutoMixFailure.ANALYSIS_CANCELED -> PlanningRejection.CANCELED
            AutoMixFailure.ANALYSIS_TIMEOUT -> PlanningRejection.DEADLINE_EXCEEDED
            AutoMixFailure.SOURCE_CHANGED -> PlanningRejection.SOURCE_MISMATCH
            else -> PlanningRejection.ANALYSIS_FAILED
        }, result.failure)
        result as AnalysisResult.Completed
        val m = result.metadata
        if (m.source != track.source) reject(PlanningRejection.SOURCE_MISMATCH)
        if (m.backend != BitChordNativeAnalysisEngine.BACKEND) reject(PlanningRejection.UNSUPPORTED_BACKEND)
        if (m.preprocessingVersion != PcmPreprocessing.BANDLIMITED) reject(PlanningRejection.UNSUPPORTED_PREPROCESSING)
        val span = m.sampleInterval?.takeIf { it.valid } ?: reject(PlanningRejection.INVALID_EVIDENCE)
        if (!m.requested.valid || !m.requested.contains(span) || span.endUs > track.durationUs ||
            span.endUs - span.startUs > 32_000_000) reject(PlanningRejection.INVALID_EVIDENCE)
        if (m.duration.durationUs?.let { it != track.durationUs } == true) reject(PlanningRejection.DURATION_MISMATCH)
        val spans = m.coverage.intervals ?: reject(PlanningRejection.UNKNOWN_COVERAGE)
        if (spans.isEmpty() || spans.size > 64 || spans.any { !it.valid || !m.requested.contains(it) } ||
            spans.zipWithNext().any { (a,b) -> a.endUs > b.startUs }) reject(PlanningRejection.INVALID_EVIDENCE)
        if (m.coverageStatus != m.coverage.status(m.requested)) reject(PlanningRejection.INVALID_EVIDENCE)
        if (m.coverage.status(span) != CoverageStatus.FULL || (m.coverage.acceptedDecodedFrames ?: 0) <= 0)
            reject(PlanningRejection.PARTIAL_COVERAGE)
        if (m.repeatedOutputFrames != 0L || m.preparation?.let { it.gapCount != 0 || it.synthesizedTimelineFrames != 0L } == true)
            reject(PlanningRejection.SOURCE_DISCONTINUITY)
        fun <T> value(v: MusicalValue<T>, method: EvidenceMethod): T =
            v.value?.takeIf { v.nature == EvidenceNature.INFERRED && v.method == method }
                ?: reject(PlanningRejection.INVALID_EVIDENCE)
        val e = result.evidence
        if (e.tempoBpm.nature == EvidenceNature.UNAVAILABLE || e.beatsUs.nature == EvidenceNature.UNAVAILABLE)
            reject(PlanningRejection.MISSING_BEATS)
        val bpm = value(e.tempoBpm, EvidenceMethod.BITCHORD_TEMPO_TRACKER)
        val beats = value(e.beatsUs, EvidenceMethod.BITCHORD_TEMPO_TRACKER)
        val confidence = value(e.gridConfidence, EvidenceMethod.BITCHORD_UNCALIBRATED_CONFIDENCE)
        if (!bpm.isFinite() || bpm !in 60.0..220.0 || !confidence.isFinite() || confidence !in 0.0..1.0)
            reject(PlanningRejection.INVALID_EVIDENCE)
        fun measured(v: MusicalValue<Double>): Double = v.value?.takeIf {v.nature==EvidenceNature.MEASURED &&
            v.method==EvidenceMethod.SUPPLIED_PCM_STATISTIC && it.isFinite() && it>=0}
            ?: reject(PlanningRejection.INVALID_EVIDENCE)
        val rms=measured(e.rms);val peak=measured(e.peak)
        if(rms>peak*(1+1e-9)) reject(PlanningRejection.INVALID_EVIDENCE)
        if (beats.isEmpty()) reject(PlanningRejection.MISSING_BEATS)
        if (beats.size > 512 || beats.any { it !in span.startUs until span.endUs } ||
            beats.zipWithNext().any { (a,b) -> b <= a }) reject(PlanningRejection.INVALID_EVIDENCE)
        if (beats.size < 9) reject(PlanningRejection.INSUFFICIENT_BEATS)
        val intervals = beats.zipWithNext { a,b -> b-a }.sorted()
        val median = intervals[intervals.size / 2]
        if (intervals.any { it !in 200_000..1_200_000 } || abs(median - 60_000_000 / bpm) > 60_000_000 / bpm * 0.02)
            reject(PlanningRejection.TEMPO_GRID_INCONSISTENT)
        val downbeats = when (e.downbeatsUs.nature) {
            EvidenceNature.UNAVAILABLE -> emptyList()
            EvidenceNature.INFERRED -> value(e.downbeatsUs, EvidenceMethod.BITCHORD_FOUR_BEAT_BASS_ACCENT)
            else -> reject(PlanningRejection.INVALID_EVIDENCE)
        }
        if (downbeats.size > 512 || downbeats.zipWithNext().any { (a,b) -> b <= a } || downbeats.any { it !in beats })
            reject(PlanningRejection.INVALID_EVIDENCE)
        val d = m.nativeDiagnostic ?: reject(PlanningRejection.INVALID_EVIDENCE)
        if (d.status != "OK_UNCALIBRATED_EVIDENCE" || d.internalPath != "upstream-sinc11025" ||
            d.analysisRateHz != 11025 || d.inputFrames !in 1..705600 || d.analysisFrames != d.inputFrames/2 ||
            d.rawBpm != bpm || d.rawConfidence != confidence ||
            listOf(d.elapsedUs,d.resamplingUs,d.dspUs,d.validationUs).any { !it.isFinite() || it<0 } ||
            abs((span.endUs-span.startUs) - d.inputFrames * 1_000_000.0 / 22050) > 1.0)
            reject(PlanningRejection.INVALID_EVIDENCE)
        val signal = window.signal
        if (signal != null && (signal.source != m.source || signal.profile != BitChordNativeAnalysisEngine.PROFILE ||
            signal.interval != span || signal.version != PlanningSignalObservation.VERSION || !signal.complete ||
            signal.riseEventsUs.size > 512 || signal.riseEventsUs.any { it !in span.startUs until span.endUs } ||
            signal.riseEventsUs.zipWithNext().any { (a,b) -> b <= a } ||
            !signal.windowRms.isFinite() || !signal.windowPeak.isFinite() || signal.windowRms < 0 || signal.windowPeak < 0 ||
            signal.windowRms>signal.windowPeak*(1+1e-9) ||
            abs(signal.windowRms-rms)>maxOf(1e-9,rms*1e-6) || abs(signal.windowPeak-peak)>maxOf(1e-9,peak*1e-6) ||
            signal.leadingQuietEndUs?.let { span.startUs != 0L || it !in 0..span.endUs } == true))
            reject(PlanningRejection.INVALID_SIGNAL_AUDIT)
        control.checkpoint()
        // Snapshot externally supplied lists. No raw PCM retained and no mutable grid is corrected.
        return NativePlanningObservations(track, AnalysisProfile(m.backend,m.preprocessingVersion), span, bpm,
            median, beats.toList(), downbeats.toSet(), confidence, signal?.copy(riseEventsUs=signal.riseEventsUs.toList()))
    }
}

object NativeTempoCompatibility {
    fun hypotheses(outgoingBpm: Double, incomingBpm: Double): List<TempoHypothesis> {
        require(outgoingBpm.isFinite() && incomingBpm.isFinite() && outgoingBpm in 60.0..220.0 && incomingBpm in 60.0..220.0)
        return listOf(1 to 1, 1 to 2, 2 to 1).map { (a,b) ->
            val x=outgoingBpm/a; val y=incomingBpm/b
            TempoHypothesis(if(a==2) MetricalRelation.OUTGOING_DOUBLE_PULSE else if(b==2)
                MetricalRelation.INCOMING_DOUBLE_PULSE else MetricalRelation.ONE_TO_ONE,
                a,b,outgoingBpm,incomingBpm,x,y,abs(x-y),abs(x-y)/x,
                60_000_000/y-60_000_000/x,x/y)
        }
    }
}

/** Fixed-index, one-to-one correspondence at explicit strides. No nearest-neighbor phase rotation. */
object NativeBeatAlignment {
    fun evaluate(outgoing: List<Long>, incoming: List<Long>, outIndex: Int, inIndex: Int,
                 pulseIntervals: Int, hypothesis: TempoHypothesis): BeatAlignment {
        require(pulseIntervals in 1..64 && outIndex >= 0 && inIndex >= 0)
        require(hypothesis.outgoingStride in 1..2 && hypothesis.incomingStride in 1..2)
        val outLast = Math.addExact(outIndex, pulseIntervals * hypothesis.outgoingStride)
        val inLast = Math.addExact(inIndex, pulseIntervals * hypothesis.incomingStride)
        require(outLast < outgoing.size && inLast < incoming.size)
        val a=outgoing[outIndex]; val b=incoming[inIndex]
        var drift=0L
        val residuals=LongArray(pulseIntervals+1) { k ->
            val x=Math.subtractExact(outgoing[outIndex+k*hypothesis.outgoingStride],a)
            val y=Math.subtractExact(incoming[inIndex+k*hypothesis.incomingStride],b)
            require(x>=0 && y>=0)
            drift=Math.subtractExact(y,x)
            require(drift != Long.MIN_VALUE)
            abs(drift)
        }
        val duration=Math.subtractExact(outgoing[outLast],a)
        val predicted=duration.toDouble() * (hypothesis.effectiveOutgoingBpm/hypothesis.effectiveIncomingBpm-1)
        require(predicted.isFinite() && abs(predicted)<Long.MAX_VALUE.toDouble())
        residuals.sort()
        return BeatAlignment(pulseIntervals+1,hypothesis.outgoingStride,hypothesis.incomingStride,0,
            residuals[residuals.size/2],residuals[((residuals.size-1)*0.95).roundToLong().toInt()],
            residuals.last(),drift,predicted.roundToLong(),
            pulseIntervals*(hypothesis.outgoingStride-1),pulseIntervals*(hypothesis.incomingStride-1))
    }
}
