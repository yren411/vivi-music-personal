// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import java.util.concurrent.CancellationException
import java.util.concurrent.Future

data class PlanningSourceRequest<S>(val source: S, val decode: DecodeRequest,
    val detail: AnalysisDetail = AnalysisDetail.DETAIL, val immutableRendition: Boolean = false) {
    override fun toString()="PlanningSourceRequest(interval=${decode.interval}, detail=$detail, immutableRendition=$immutableRendition)"
}

/** Object identity, not a recycled sequence number: a ticket belongs to exactly one batch. */
class NativePlanningTicket internal constructor()
enum class NativePlanningStage { ANALYZING_BOTH, ANALYZING_OUTGOING, ANALYZING_INCOMING, PLANNING }
sealed interface NativePlanningPoll {
    data class Pending(val stage: NativePlanningStage) : NativePlanningPoll
    data class Finished(val outcome: NativePlanningOutcome) : NativePlanningPoll
}

/** One explicit developer batch, ONE caller-owned bounded AnalysisWorkers pool, ONE original
 * deadline. The supplied pool must be exclusive to this coordinator until close. No own
 * executors, blocking future joins, raw PCM cache or dependency on MusicService.
 * Several pairs may share a window; only the LAST observer's invalidation cancels that job.
 * Start a replacement before invalidating the old ticket to preserve still-needed work.
 * Close before reusing this pool with a new batch/deadline. Completed feature cache survives. */
class ExperimentalNativePlanningSession<S>(
    private val workers: AnalysisWorkers<PlanningWindowEvidence>,
    private val analysis: NativePlanningAnalysis<S>,
    private val control: AnalysisControl,
    private val planner: NativeAwareAutoMixPlanner = NativeAwareAutoMixPlanner(),
    private val useDecodedDurations:Boolean=false,
    private val onEvidence:((PlanningWindowEvidence,PlanningWindowEvidence)->Unit)?=null,
) : AutoCloseable {
    private val lock=Any()
    private var closed=false
    private inner class Job(val key: String, val cancellation: AnalysisCancellationToken,
                            val future: Future<PlanningWindowEvidence>, var observers: Int = 1)
    private inner class Attempt(var request: NativePlanningRequest, val outgoing: Job, val incoming: Job) {
        val cancellation=AnalysisCancellationToken()
        var planning: Future<NativePlanningOutcome>?=null
        var outcome: NativePlanningOutcome?=null
        var released=false
    }
    private val jobs=mutableMapOf<String,Job>()
    private val attempts=linkedMapOf<NativePlanningTicket,Attempt>()
    private class WindowFailure(val result: AnalysisResult.Failed) : Exception()

    fun begin(request: NativePlanningRequest, outgoing: PlanningSourceRequest<S>, incoming: PlanningSourceRequest<S>): NativePlanningTicket = synchronized(lock) {
        check(!closed) { "Experimental planning session closed" }
        control.checkpoint()
        require(outgoing.decode.identity==request.outgoing.source && incoming.decode.identity==request.incoming.source)
        if(attempts.size>=8) {
            val finished=attempts.entries.firstOrNull { it.value.outcome!=null }
            if(finished!=null) attempts.remove(finished.key) else throw AnalysisUnavailable("WORKER_QUEUE_FULL")
        }
        val a=acquire(outgoing)
        val b=try { acquire(incoming) } catch(error: Exception) { release(a);throw error }
        val ticket=NativePlanningTicket()
        attempts[ticket]=Attempt(request,a,b)
        ticket
    }

    private fun acquire(source: PlanningSourceRequest<S>): Job {
        val request=source.decode
        val digest=BitChordNativeAnalysisEngine.PROFILE.cacheKey(request.identity,0,0,source.detail)
        val key="$digest:${PlanningSignalObservation.VERSION}:${NativeDjLevel.VERSION}:${request.interval.startUs}:${request.interval.endUs}:${source.immutableRendition}"
        jobs[key]?.let { it.observers++;return it }
        val token=AnalysisCancellationToken()
        val workControl=control.copy(cancellation=AnalysisCancellation {
            control.cancellation.throwIfCancelled();token.throwIfCancelled()
        })
        val future=workers.submit(key,cacheable=source.immutableRendition) {
            workControl.checkpoint()
            val result=analysis.analyze(source.source,request,source.detail,workControl)
            workControl.checkpoint()
            // A completed lack of rhythm is cacheable evidence; technical failures are not.
            (result.analysis as? AnalysisResult.Failed)?.let { throw WindowFailure(it) }
            result
        }
        return Job(key,token,future).also { jobs[key]=it }
    }

    /** Main-thread snapshot only; no worker reads a player. Never re-decodes completed audio. */
    fun updateEarliest(ticket:NativePlanningTicket,positionUs:Long)=synchronized(lock) {
        val attempt=attempts[ticket] ?: return@synchronized
        if(attempt.planning==null && positionUs>=attempt.request.earliestOutgoingUs && positionUs<attempt.request.outgoing.durationUs)
            attempt.request=attempt.request.copy(earliestOutgoingUs=positionUs)
    }

    /** Nonblocking caller-side pump: aggregation is queued ONLY after both analyses finish. */
    fun poll(ticket: NativePlanningTicket): NativePlanningPoll = synchronized(lock) {
        val attempt=attempts[ticket] ?: throw IllegalArgumentException("Unknown or evicted planning ticket")
        fun finish(outcome: NativePlanningOutcome): NativePlanningPoll {
            attempt.outcome=outcome
            release(attempt)
            return NativePlanningPoll.Finished(outcome)
        }
        try { control.checkpoint();attempt.cancellation.throwIfCancelled() }
        catch(error: Exception) {
            val reason=if(error is AnalysisUnavailable && AutoMixFailure.of(error)==AutoMixFailure.ANALYSIS_TIMEOUT)
                PlanningRejection.DEADLINE_EXCEEDED else PlanningRejection.CANCELED
            return@synchronized finish(attempt.outcome ?: NativeAwareAutoMixPlanner.unavailable(attempt.request,reason))
        }
        attempt.outcome?.let { return@synchronized NativePlanningPoll.Finished(it) }
        val outDone=attempt.outgoing.future.isDone; val inDone=attempt.incoming.future.isDone
        if(!outDone || !inDone) return@synchronized NativePlanningPoll.Pending(when {
            !outDone && !inDone -> NativePlanningStage.ANALYZING_BOTH
            !outDone -> NativePlanningStage.ANALYZING_OUTGOING
            else -> NativePlanningStage.ANALYZING_INCOMING
        })
        if(attempt.planning==null) {
            val a=completed(attempt.outgoing.future);val b=completed(attempt.incoming.future)
            onEvidence?.invoke(a,b) // compact finished evidence, before candidate rejection can discard it
            val planningControl=control.copy(cancellation=AnalysisCancellation {
                control.cancellation.throwIfCancelled();attempt.cancellation.throwIfCancelled()
            })
            attempt.planning=workers.submitTransient {
                if(useDecodedDurations && listOf(a,b).any {
                    it.analysis is AnalysisResult.Completed && it.analysis.metadata.duration.durationUs==null
                })return@submitTransient NativeAwareAutoMixPlanner.unavailable(attempt.request,PlanningRejection.DURATION_MISMATCH)
                // Source duration comes from the decoder's actual container, never catalog rounding.
                // This changes track geometry only; sample origins/beat positions are untouched.
                fun actual(track:PlanningTrack,evidence:PlanningWindowEvidence):PlanningTrack {
                    val duration=(evidence.analysis as? AnalysisResult.Completed)?.metadata?.duration?.durationUs
                    return if(useDecodedDurations && duration!=null && duration>0)
                        track.copy(durationUs=duration,cueRegion=track.cueRegion.copy(endUs=minOf(track.cueRegion.endUs,duration))) else track
                }
                val request=attempt.request.copy(outgoing=actual(attempt.request.outgoing,a),incoming=actual(attempt.request.incoming,b))
                when(val outcome=planner.plan(request,a,b,planningControl)) {
                    is NativePlanningOutcome.Selected -> outcome.copy(outgoingLevel=a.level,incomingLevel=b.level)
                    else -> outcome
                }
            }
        }
        val future=requireNotNull(attempt.planning)
        if(!future.isDone) return@synchronized NativePlanningPoll.Pending(NativePlanningStage.PLANNING)
        val result=try { future.get() } catch(error: Exception) {
            NativeAwareAutoMixPlanner.unavailable(attempt.request,if(error is CancellationException)
                PlanningRejection.CANCELED else PlanningRejection.ANALYSIS_FAILED)
        }
        // Pair validity and cancellation are checked again at publication under this lock.
        try { control.checkpoint();attempt.cancellation.throwIfCancelled() }
        catch(error: Exception) {
            return@synchronized finish(NativeAwareAutoMixPlanner.unavailable(attempt.request,
                if(error is AnalysisUnavailable) PlanningRejection.DEADLINE_EXCEEDED else PlanningRejection.CANCELED))
        }
        finish(result)
    }

    private fun completed(future: Future<PlanningWindowEvidence>): PlanningWindowEvidence = try { future.get() }
        catch(error: Exception) {
            val original=error.cause
            if(original is WindowFailure) PlanningWindowEvidence(original.result)
            else PlanningWindowEvidence(AnalysisResult.Failed(BitChordNativeAnalysisEngine.BACKEND,
                AnalysisFailure(AnalysisFailureStage.ANALYSIS,
                    if(error is CancellationException) AutoMixFailure.ANALYSIS_CANCELED else AutoMixFailure.of(original ?: error),
                    if(error is CancellationException) "ANALYSIS_CANCELED" else "EXPERIMENTAL_ANALYSIS_FAILED")))
        }

    fun invalidate(ticket: NativePlanningTicket, reason: PlanningRejection = PlanningRejection.QUEUE_CHANGED) = synchronized(lock) {
        require(reason in setOf(PlanningRejection.QUEUE_CHANGED,PlanningRejection.CANCELED,PlanningRejection.DEADLINE_EXCEEDED))
        val attempt=attempts[ticket] ?: return@synchronized
        attempt.cancellation.cancel()
        attempt.planning?.cancel(true)
        attempt.outcome=NativeAwareAutoMixPlanner.unavailable(attempt.request,reason)
        release(attempt)
    }

    private fun release(attempt: Attempt) {
        if(attempt.released) return
        attempt.released=true
        release(attempt.outgoing);release(attempt.incoming)
    }
    private fun release(job: Job) {
        job.observers--
        if(job.observers==0) {
            jobs.remove(job.key)
            if(!job.future.isDone) { job.cancellation.cancel();job.future.cancel(true) }
        }
    }
    override fun close() = synchronized(lock) {
        if(!closed) {
            attempts.values.forEach { attempt ->
                attempt.cancellation.cancel();attempt.planning?.cancel(true);release(attempt)
            }
            attempts.clear();closed=true
        }
    }
}
