// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import java.util.UUID

/** Stable saved values. Missing/unknown preferences always retain the legacy route. */
enum class AutoMixEngineMode { LEGACY, NATIVE_DJ_EXPERIMENTAL;
    companion object { fun read(value:String?)=entries.firstOrNull {it.name==value} ?: LEGACY }
}

data class NativeServicePair<S>(val queue:NativeDjQueue,val outgoing:PlanningTrack,val incoming:PlanningTrack,
    val outgoingSource:S,val incomingSource:S,val positionUs:Long,val playing:Boolean,
    val immutableOutgoing:Boolean=false,val immutableIncoming:Boolean=false) {
    override fun toString()="NativeServicePair(generation=${queue.generation}, sources=redacted)"
}

/** Application-thread boundary, shared by MusicService and actual service instrumentation. */
interface NativeAutoMixServiceHost<S> {
    fun pair():NativeServicePair<S>?
    /** Initial renderer/sink setup is transient, not a rejected attempt. Checked before claiming a pair. */
    fun waitingForOutput():Boolean=false
    fun incompatibility():String?
    fun legacyEnabled(enabled:Boolean)
    fun execute(plan:NativeTransitionPlan,outgoingLevel:NativeDjLevel?,incomingLevel:NativeDjLevel?):NativeDjTransitionExecutor
    fun pause();fun resume();fun refreshVolume()
    fun cancel(reason:NativeDjExecutionReason)
    fun status(value:AutoMixStatus)
}

/** One active pair, one bounded batch, no nested future waits or PCM retention. */
class NativeAutoMixServiceController<S>(private val host:NativeAutoMixServiceHost<S>,
    private val analysis:NativePlanningAnalysis<S>,private val workers:AnalysisWorkers<PlanningWindowEvidence>,
    private val recorder:AutoMixRecorder,private val nowNanos:()->Long=System::nanoTime) : AutoCloseable {
    private var enabled=false
    private var mode=AutoMixEngineMode.LEGACY
    private var pair:NativeServicePair<S>?=null
    private var attempted:NativeDjQueue?=null
    private var session:ExperimentalNativePlanningSession<S>?=null
    private var ticket:NativePlanningTicket?=null
    private var plan:NativeTransitionPlan?=null
    private var executor:NativeDjTransitionExecutor?=null
    private var levels:Pair<NativeDjLevel?,NativeDjLevel?> = null to null
    private var suspended=false
    private var technicalFailures=0
    private var attempt=0L
    private var lastStage:NativeDjExecutionStage?=null
    private var lastRecordNanos=0L
    private var analysisCostUs=2_000_000L
    private var analysisBegan=0L
    private var performance=AutoMixPerformance.BALANCED
    private val salt=UUID.randomUUID().toString()
    private var lastRouteDecision:NativeRouteDecision?=null
    val nativeSelected get()=enabled && mode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL
    val ownsPlayback get()=executor?.terminal==false
    val currentExecutor get()=executor
    val confirmedPlan get()=plan
    fun routeObserved(decision:NativeRouteDecision) {
        if(decision!=lastRouteDecision || decision.material)recorder.native(attempt,NativeServiceEvent.ROUTE,route=decision)
        lastRouteDecision=decision
    }

    fun configure(enabled:Boolean,mode:AutoMixEngineMode,performance:AutoMixPerformance) {
        if(this.enabled==enabled && this.mode==mode && this.performance==performance)return
        invalidate(NativeDjExecutionReason.CANCELED)
        this.enabled=enabled;this.mode=mode;this.performance=performance
        suspended=false;technicalFailures=0
        workers.configure(performance)
        host.legacyEnabled(enabled && mode==AutoMixEngineMode.LEGACY)
        if(!enabled)host.status(AutoMixStatus(AutoMixPhase.DISABLED))
    }
    fun invalidate(reason:NativeDjExecutionReason) {
        session?.close();session=null;ticket=null;workers.cancelAll()
        if(executor!=null)host.cancel(reason)
        if(pair!=null)recorder.native(attempt,NativeServiceEvent.CANCELED,reason=reason.name)
        executor=null;plan=null;levels=null to null;pair=null;attempted=null;lastStage=null
        host.status(AutoMixStatus(if(enabled)AutoMixPhase.WAITING_SOURCE else AutoMixPhase.DISABLED,nativeMode=nativeSelected))
    }
    private fun fallback(reason:String) {
        session?.close();session=null;ticket=null
        host.cancel(NativeDjExecutionReason.CANCELED)
        executor=null;plan=null
        recorder.native(attempt,NativeServiceEvent.FALLBACK,reason=reason)
        if(reason in setOf("HOST_FAILURE","PREPARATION_FAILED","PLAYER_FAILED","DECODER_FAILED","NATIVE_LIBRARY_UNAVAILABLE","NATIVE_INTERNAL_ERROR","ANALYSIS_TIMEOUT"))technicalFailures++
        if(technicalFailures>=3) {
            suspended=true;recorder.native(attempt,NativeServiceEvent.FALLBACK,reason="SESSION_SAFETY_DISABLED")
        }
        host.status(AutoMixStatus(AutoMixPhase.UNAVAILABLE,reason=if(suspended)"SESSION_SAFETY_DISABLED" else reason,nativeMode=true))
        host.legacyEnabled(enabled) // genuine independent legacy decoding/profile, never native features
    }
    fun technicalFailure() {fallback("HOST_FAILURE")}
    fun playbackChanged(playing:Boolean) {if(ownsPlayback) {if(playing)host.resume() else host.pause()}}
    fun volumeChanged() {if(ownsPlayback)host.refreshVolume()}

    fun validateQueue() {
        if(pair!=null && host.pair()?.queue!=pair!!.queue && executor?.terminal!=true)
            invalidate(NativeDjExecutionReason.QUEUE_CHANGED)
    }
    fun tick() {
        if(!nativeSelected)return
        if(suspended) {host.legacyEnabled(true);return}
        // Promotion changes the authoritative pair before the next controller pump. Record
        // the terminal report first; never mislabel a successful handoff as queue cancellation.
        executor?.takeIf {it.terminal}?.let { e ->
            recorder.native(attempt,NativeServiceEvent.EXECUTION,plan=plan,execution=e.report())
            if(e.status.stage in setOf(NativeDjExecutionStage.COMPLETED,NativeDjExecutionStage.RECOVERED)) {
                host.status(AutoMixStatus(AutoMixPhase.COMPLETED,nativeMode=true,nativeExecutionStage=e.status.stage))
                host.cancel(NativeDjExecutionReason.CANCELED);executor=null;plan=null;pair=null;lastStage=null
                return
            } else {fallback(e.status.reason?.name ?: "NATIVE_EXECUTION_FAILED");return}
        }
        val current=host.pair()
        if(pair!=null && (current==null || current.queue!=pair!!.queue))invalidate(NativeDjExecutionReason.QUEUE_CHANGED)
        if(current==null) {
            host.legacyEnabled(true)
            host.status(AutoMixStatus(AutoMixPhase.WAITING_SOURCE,nativeMode=true,reason="WAITING_RESOLVED_PAIR"))
            return
        }
        if(pair==null && attempted!=current.queue) {
            // Timeline/source resolution may precede renderer and sink configuration. Do not
            // latch a transient inactive processor as a permanent rejection for this queue.
            if(host.waitingForOutput()) {
                host.legacyEnabled(true)
                host.status(AutoMixStatus(AutoMixPhase.WAITING_SOURCE,nativeMode=true,reason="WAITING_PLAYER_OUTPUT"))
                return
            }
            pair=current;attempted=current.queue
            host.legacyEnabled(false)
            attempt=recorder.begin("${current.outgoing.source.recordingId}|${current.outgoing.source.renditionKey}",
                "${current.incoming.source.recordingId}|${current.incoming.source.renditionKey}")
            recorder.native(attempt,NativeServiceEvent.PAIR,generation=current.queue.generation)
            val reason=host.incompatibility()
            if(reason!=null) {fallback(reason);return}
            val a=current.outgoing;val b=current.incoming
            if(a.durationUs<8_000_000 || b.durationUs<8_000_000) {fallback("SHORT_TRACK");return}
            val start=(a.durationUs-32_000_000).coerceAtLeast(0)
            // Analyze as soon as the actual pair is resolved. Windows never exceed existing 32s caps.
            // The original deadline is bounded by both remaining candidate time and measured cost.
            val remaining=a.durationUs-current.positionUs-4_000_000
            if(remaining<2_000_000) {fallback("PREPARATION_DEADLINE");return}
            val budget=minOf(20_000_000L,maxOf(4_000_000L,analysisCostUs*3),remaining)
            val attemptNumber=attempt
            val control=AnalysisControl(Math.addExact(nowNanos(),budget*1000),decoderDiagnostics=DecoderDiagnosticSink {
                recorder.record(attemptNumber,MixEvent.DECODER,decoder=it)
            })
            session=ExperimentalNativePlanningSession(workers,analysis,control,useDecodedDurations=true,onEvidence={a,b ->
                recorder.native(attemptNumber,NativeServiceEvent.EVIDENCE,evidence=a.analysis,role=MixRole.OUTGOING)
                recorder.native(attemptNumber,NativeServiceEvent.EVIDENCE,evidence=b.analysis,role=MixRole.INCOMING)
            })
            analysisBegan=nowNanos()
            try {
                ticket=session!!.begin(NativePlanningRequest(a,b,current.positionUs,2_000_000,current.queue.generation,salt,
                    placementPolicy=NativePlacementPolicy.PRESERVE_OUTGOING),
                    PlanningSourceRequest(current.outgoingSource,DecodeRequest(AudioIntervalUs(start,a.durationUs),a.source),immutableRendition=current.immutableOutgoing),
                    PlanningSourceRequest(current.incomingSource,DecodeRequest(AudioIntervalUs(0,minOf(32_000_000,b.durationUs)),b.source),immutableRendition=current.immutableIncoming))
                host.status(AutoMixStatus(AutoMixPhase.ANALYZING_CURRENT,a.source.recordingId,b.source.recordingId,nativeMode=true))
            } catch(_:Exception) {fallback("NATIVE_ANALYSIS_START_FAILED")}
        }
        val batch=session;val task=ticket
        if(batch!=null && task!=null) {
            batch.updateEarliest(task,current.positionUs)
            when(val result=batch.poll(task)) {
                is NativePlanningPoll.Pending -> host.status(AutoMixStatus(
                    if(result.stage==NativePlanningStage.PLANNING)AutoMixPhase.REFINING else if(result.stage==NativePlanningStage.ANALYZING_INCOMING)AutoMixPhase.ANALYZING_NEXT else AutoMixPhase.ANALYZING_CURRENT,
                    current.outgoing.source.recordingId,current.incoming.source.recordingId,nativeMode=true))
                is NativePlanningPoll.Finished -> {
                    analysisCostUs=((nowNanos()-analysisBegan)/1000).coerceIn(1,20_000_000)
                    batch.close();session=null;ticket=null
                    when(val outcome=result.outcome) {
                        is NativePlanningOutcome.Fallback -> {fallback(outcome.analysisFailure?.code ?: outcome.reason.name);return}
                        is NativePlanningOutcome.Selected -> {
                            if(outcome.plan.readiness!=NativePlanReadiness.TIMING_AND_TRANSIENT_SUPPORTED) {fallback("SIGNAL_NOT_CORROBORATED");return}
                            plan=outcome.plan;levels=outcome.outgoingLevel to outcome.incomingLevel
                            recorder.native(attempt,NativeServiceEvent.PLAN,plan=outcome.plan,
                                generation=current.queue.generation,elapsedUs=analysisCostUs,candidateCount=outcome.diagnostics.candidateCount,
                                planningRejections=outcome.diagnostics.rejections,laterCandidateRejections=outcome.diagnostics.laterCandidateRejections)
                        }
                    }
                }
            }
        }
        if((session!=null || plan!=null || executor!=null) && host.incompatibility()!=null) {
            fallback(requireNotNull(host.incompatibility()));return
        }
        val selected=plan ?: return
        if(executor==null) {
            host.status(AutoMixStatus(AutoMixPhase.PLANNED,current.outgoing.source.recordingId,current.incoming.source.recordingId,
                selected.outgoing.durationUs/1000,nativeMode=true,nativePlan=selected))
            // Preparation gets at least the plan's 2s lead plus observed analysis cost (2..8s).
            // No idle prebuffered deck is retained for the entire remaining song.
            val preparation=maxOf(4_000_000L,analysisCostUs+2_000_000).coerceAtMost(10_000_000)
            if(!current.playing || selected.candidate.outgoingCueUs-current.positionUs>preparation)return
            if(host.incompatibility()!=null || current.positionUs>selected.candidate.outgoingCueUs-selected.assumedPreparationLeadUs) {
                fallback("PREPARATION_DEADLINE");return
            }
            executor=try {host.execute(selected,levels.first,levels.second)}catch(_:Exception){fallback("PREPARATION_FAILED");return}
        }
        val e=executor ?: return
        val stage=e.status.stage
        if(stage!=lastStage || nowNanos()-lastRecordNanos>=1_000_000_000) {
            recorder.native(attempt,NativeServiceEvent.EXECUTION,plan=selected,execution=e.report())
            lastStage=stage;lastRecordNanos=nowNanos()
        }
        val phase=when(stage) {
            NativeDjExecutionStage.COMPLETED,NativeDjExecutionStage.RECOVERED -> AutoMixPhase.COMPLETED
            NativeDjExecutionStage.MIXING,NativeDjExecutionStage.PAUSED -> AutoMixPhase.MIXING
            NativeDjExecutionStage.ARMED -> AutoMixPhase.READY
            else -> AutoMixPhase.PREPARING
        }
        host.status(AutoMixStatus(phase,current.outgoing.source.recordingId,current.incoming.source.recordingId,
            selected.outgoing.durationUs/1000,reason=e.status.reason?.name,actualStartMs=e.measurements().fadeStartedOutgoingUs?.div(1000),
            nativeMode=true,nativePlan=selected,nativeExecutionStage=stage))
        if(e.terminal) {
            if(stage !in setOf(NativeDjExecutionStage.COMPLETED,NativeDjExecutionStage.RECOVERED))fallback(e.status.reason?.name ?: "NATIVE_EXECUTION_FAILED")
            else {host.cancel(NativeDjExecutionReason.CANCELED);executor=null;plan=null
                host.status(AutoMixStatus(AutoMixPhase.COMPLETED,nativeMode=true,nativeExecutionStage=stage))}
        }
    }
    override fun close() {enabled=false;invalidate(NativeDjExecutionReason.SHUTDOWN);workers.close()}
}

/** Waiting native resolution must not overwrite a real ongoing legacy fade/plan in Stats. */
internal fun nativeServiceVisibleStatus(native:AutoMixStatus,legacy:AutoMixStatus?,legacyOwns:Boolean):AutoMixStatus =
    if(legacyOwns && native.phase==AutoMixPhase.WAITING_SOURCE && legacy!=null)
        legacy.copy(nativeFallback=native.reason) else native
