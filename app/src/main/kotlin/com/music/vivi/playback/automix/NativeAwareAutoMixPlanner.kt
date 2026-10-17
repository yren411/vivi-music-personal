// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max

/** Experimental, deterministic planning only. Does not schedule, decode, change rate or play audio. */
class NativeAwareAutoMixPlanner(config: NativePlannerConfig = NativePlannerConfig()) {
    private val config = config.copy(pulseCounts=config.pulseCounts.toList())

    fun plan(request: NativePlanningRequest, outgoing: PlanningWindowEvidence, incoming: PlanningWindowEvidence,
             control: AnalysisControl = AnalysisControl()): NativePlanningOutcome {
        val start = System.nanoTime()
        var validatedAt = start
        var inspected = 0
        var accepted = 0
        var truncated = false
        var anchorGenerationNanos = 0L
        val rejects = linkedMapOf<PlanningRejection,Int>()
        val best = ArrayList<NativeTransitionCandidate>()
        fun reject(reason: PlanningRejection) { rejects[reason]=(rejects[reason] ?: 0)+1 }
        fun diagnostics() = PlanningDiagnostics(VERSION, reportId(request.diagnosticSalt,request.outgoing.source),
            reportId(request.diagnosticSalt,request.incoming.source),inspected,accepted,rejects.toMap(),best.toList(),
            (System.nanoTime()-validatedAt).coerceAtLeast(0), (validatedAt-start).coerceAtLeast(0),
            (System.nanoTime()-start).coerceAtLeast(0),truncated,anchorGenerationNanos,
            (System.nanoTime()-validatedAt-anchorGenerationNanos).coerceAtLeast(0))
        fun fallback(reason: PlanningRejection, failure: AnalysisFailure? = null): NativePlanningOutcome {
            reject(reason)
            return NativePlanningOutcome.Fallback(reason,diagnostics(),failure)
        }
        return try {
            control.checkpoint()
            if (!validRequest(request)) return fallback(PlanningRejection.INVALID_REQUEST)
            val earliest = Math.addExact(request.earliestOutgoingUs,request.preparationLeadUs)
            val a=NativePlanningEvidence.validate(request.outgoing,outgoing,control)
            val b=NativePlanningEvidence.validate(request.incoming,incoming,control)
            validatedAt=System.nanoTime()
            val hypotheses=NativeTempoCompatibility.hypotheses(a.bpm,b.bpm)
                .filter { it.relativeDifference<=config.maxRelativeTempoDifference }
            if (hypotheses.isEmpty()) return fallback(PlanningRejection.INCOMPATIBLE_TEMPO)
            val outRegion=a.beats.indices.filter {i ->a.beats[i] in request.outgoing.cueRegion.startUs until request.outgoing.cueRegion.endUs}
            if(outRegion.isEmpty()) return fallback(PlanningRejection.OUTSIDE_TRACK_OR_COVERAGE)
            val outAnchors=anchors(outRegion.filter {i ->a.beats[i]>=earliest},config.maxOutgoingAnchors)
            if (outAnchors.isEmpty()) return fallback(PlanningRejection.PREPARATION_DEADLINE)
            val quietEnd=b.signal?.leadingQuietEndUs ?: 0L
            val skipLimit=if (quietEnd > Long.MAX_VALUE-config.maxUnverifiedIntroSkipUs) Long.MAX_VALUE
                else quietEnd+config.maxUnverifiedIntroSkipUs
            val incomingAudibleFloor=(quietEnd-70_000).coerceAtLeast(0)
            val inRegion=b.beats.indices.filter {i ->b.beats[i] in request.incoming.cueRegion.startUs until request.incoming.cueRegion.endUs}
            if(inRegion.isEmpty()) return fallback(PlanningRejection.OUTSIDE_TRACK_OR_COVERAGE)
            val inAnchors=anchors(inRegion.filter { i ->
                val cue=b.beats[i]
                cue<=skipLimit && cue>=incomingAudibleFloor && (request.incoming.seekable || cue==0L)
            },config.maxIncomingAnchors)
            if (inAnchors.isEmpty()) return fallback(if (!request.incoming.seekable)
                PlanningRejection.CUE_NOT_SEEKABLE else PlanningRejection.CUE_NOT_PRESERVING_INTRO)
            anchorGenerationNanos=System.nanoTime()-validatedAt
            search@ for (i in outAnchors) for (j in inAnchors) for (h in hypotheses) for (count in config.pulseCounts) {
                control.checkpoint()
                if (inspected>=config.maxCandidates) { truncated=true;break@search }
                inspected++
                val outLast=i+count*h.outgoingStride
                val inLast=j+count*h.incomingStride
                if (outLast>=a.beats.size || inLast>=b.beats.size) { reject(PlanningRejection.INSUFFICIENT_BEATS);continue }
                val outCue=a.beats[i]; val inCue=b.beats[j]
                val duration=a.beats[outLast]-outCue
                if (duration !in config.minOverlapUs..config.maxOverlapUs) { reject(PlanningRejection.OVERLAP_UNSUPPORTED);continue }
                val outEnd=Math.addExact(outCue,duration);val inEnd=Math.addExact(inCue,duration)
                if (outEnd>request.outgoing.durationUs || inEnd>request.incoming.durationUs ||
                    !a.sampleInterval.contains(AudioIntervalUs(outCue,outEnd)) ||
                    !b.sampleInterval.contains(AudioIntervalUs(inCue,inEnd))) {
                    reject(PlanningRejection.OUTSIDE_TRACK_OR_COVERAGE);continue
                }
                val alignment=NativeBeatAlignment.evaluate(a.beats,b.beats,i,j,count,h)
                if (abs(alignment.signedEndDriftUs)>config.maxResidualUs || abs(alignment.predictedTempoDriftUs)>config.maxResidualUs) {
                    reject(PlanningRejection.EXCESSIVE_DRIFT);continue
                }
                if (alignment.medianResidualUs>config.maxMedianResidualUs || alignment.p95ResidualUs>config.maxP95ResidualUs ||
                    alignment.maxResidualUs>config.maxResidualUs) { reject(PlanningRejection.POOR_ALIGNMENT);continue }
                val outSupport=transientSupport(a,i,count,h.outgoingStride,control)
                val inSupport=transientSupport(b,j,count,h.incomingStride,control)
                if ((outSupport!=null && outSupport<0.6) || (inSupport!=null && inSupport<0.6)) {
                    reject(PlanningRejection.SIGNAL_NOT_CORROBORATED);continue
                }
                val corroborated=(if(outSupport!=null) 1 else 0)+(if(inSupport!=null) 1 else 0)
                val flags=mutableSetOf(PlanningUncertainty.UNCALIBRATED_NATIVE_CONFIDENCE,
                    PlanningUncertainty.PHRASE_UNKNOWN,PlanningUncertainty.VOCALS_UNKNOWN,PlanningUncertainty.HARMONY_UNKNOWN,
                    PlanningUncertainty.METER_UNKNOWN,PlanningUncertainty.ACTIVE_OVERLAP_UNKNOWN)
                if (corroborated<2) flags+=PlanningUncertainty.SIGNAL_AUDIT_UNAVAILABLE
                if (h.relation!=MetricalRelation.ONE_TO_ONE) flags+=PlanningUncertainty.OCTAVE_HYPOTHESIS_UNVERIFIED
                if (quietEnd>0) flags+=PlanningUncertainty.LOW_LEVEL_INTRO_CONTENT_UNKNOWN
                val downbeatSupport=(if(outCue in a.heuristicDownbeats) 1 else 0)+(if(inCue in b.heuristicDownbeats) 1 else 0)
                val rank=CandidateRank(corroborated,h.relation!=MetricalRelation.ONE_TO_ONE,count,
                    alignment.p95ResidualUs,inCue,outCue-max(earliest,request.outgoing.cueRegion.startUs),downbeatSupport)
                val candidate=NativeTransitionCandidate(outCue,inCue,duration,h,alignment,rank,flags.toSet(),outSupport,inSupport)
                accepted++
                best+=candidate
                best.sortWith(RANKING)
                if(best.size>config.maxReportedCandidates) best.removeAt(best.lastIndex)
            }
            control.checkpoint()
            val selected=best.firstOrNull() ?: return fallback(primaryRejection(rejects))
            NativePlanningOutcome.Selected(NativeTransitionPlan(VERSION,request.generation,request.outgoing,request.incoming,
                a.profile,b.profile,a.sampleInterval,b.sampleInterval,request.earliestOutgoingUs,request.preparationLeadUs,
                selected,if(selected.rank.corroboratedTracks==2) NativePlanReadiness.TIMING_AND_TRANSIENT_SUPPORTED
                else NativePlanReadiness.TIMING_ONLY_SIGNAL_UNVERIFIED,a.rawNativeConfidence,b.rawNativeConfidence),diagnostics())
        } catch (error: PlanningEvidenceRejected) { fallback(error.reason,error.analysisFailure)
        } catch (_: CancellationException) { fallback(PlanningRejection.CANCELED)
        } catch (_: InterruptedException) { fallback(PlanningRejection.CANCELED)
        } catch (error: AnalysisUnavailable) { fallback(if(AutoMixFailure.of(error)==AutoMixFailure.ANALYSIS_TIMEOUT)
            PlanningRejection.DEADLINE_EXCEEDED else PlanningRejection.ANALYSIS_FAILED)
        } catch (_: ArithmeticException) { fallback(PlanningRejection.INVALID_REQUEST)
        } catch (_: IllegalArgumentException) { fallback(PlanningRejection.INVALID_EVIDENCE) }
    }

    private fun transientSupport(o: NativePlanningObservations, index: Int, count: Int, stride: Int,
                                 control: AnalysisControl): Double? {
        val signal=o.signal ?: return null
        var matched=0
        var previousEvent=-1
        for(k in 0..count) {
            control.checkpoint()
            val beat=o.beats[index+k*stride]
            val location=signal.riseEventsUs.binarySearch(beat)
            val insertion=if(location>=0) location else -location-1
            var event=-1
            val left=insertion-1
            if(left>previousEvent && left in signal.riseEventsUs.indices) event=left
            if(insertion>previousEvent && insertion in signal.riseEventsUs.indices &&
                (event<0 || abs(signal.riseEventsUs[insertion]-beat)<abs(signal.riseEventsUs[event]-beat))) event=insertion
            if(event>=0 && abs(signal.riseEventsUs[event]-beat)<=70_000) { matched++;previousEvent=event }
        }
        return matched.toDouble()/(count+1)
    }

    private fun validRequest(r: NativePlanningRequest): Boolean {
        fun valid(t: PlanningTrack)=t.durationUs>0 && t.cueRegion.valid && t.cueRegion.endUs<=t.durationUs &&
            t.source.recordingId.length in 1..2048 && t.source.renditionKey.length in 1..8192
        return valid(r.outgoing) && valid(r.incoming) && r.outgoing.source!=r.incoming.source &&
            r.generation>=0 && r.earliestOutgoingUs>=0 && r.preparationLeadUs in 0..60_000_000 &&
            r.diagnosticSalt.length in 1..128
    }

    private fun anchors(indices: List<Int>, limit: Int): List<Int> = if(indices.size<=limit) indices
        else if(limit==1) listOf(indices.first()) else (0 until limit).map { indices[it*(indices.size-1)/(limit-1)] }

    private fun primaryRejection(rejections: Map<PlanningRejection,Int>) = listOf(
        PlanningRejection.SIGNAL_NOT_CORROBORATED,PlanningRejection.POOR_ALIGNMENT,PlanningRejection.EXCESSIVE_DRIFT,
        PlanningRejection.OUTSIDE_TRACK_OR_COVERAGE,PlanningRejection.OVERLAP_UNSUPPORTED,PlanningRejection.INSUFFICIENT_BEATS)
        .firstOrNull { it in rejections } ?: PlanningRejection.NO_FEASIBLE_CANDIDATE

    companion object {
        const val VERSION="tinium-native-dj-planner-v1"
        internal fun unavailable(request: NativePlanningRequest, reason: PlanningRejection) =
            NativePlanningOutcome.Fallback(reason,PlanningDiagnostics(VERSION,
                reportId(request.diagnosticSalt,request.outgoing.source),reportId(request.diagnosticSalt,request.incoming.source),
                0,0,mapOf(reason to 1),emptyList(),0,0,0,false))
        /** Hard evidence/feasibility gates run before this documented lexicographic ranking. */
        private val RANKING=compareByDescending<NativeTransitionCandidate> { it.rank.corroboratedTracks }
            .thenBy { it.rank.octaveHypothesis }.thenByDescending { it.rank.supportedPulseIntervals }
            // Within a 10ms residual band, preserve the intro and requested outgoing region
            // before comparing finer residual differences. All hard limits still apply.
            .thenBy { it.rank.p95ResidualUs/10_000 }.thenBy { it.rank.incomingSkipUs }
            .thenBy { it.rank.outgoingPreferenceDistanceUs }.thenBy { it.rank.p95ResidualUs }
            .thenByDescending { it.rank.heuristicDownbeatAnchors }
            .thenBy { it.outgoingCueUs }.thenBy { it.incomingCueUs }.thenBy { it.tempo.relation.ordinal }

        private fun reportId(salt: String, source: AnalysisSourceIdentity): String {
            if(salt.length !in 1..128) return "redacted-invalid-context"
            val values=listOf(salt,source.recordingId,source.renditionKey).map {if(it.length<=8192)it else "oversize-invalid-identity"}
            val bytes=values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes).take(12)
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        }
    }
}
