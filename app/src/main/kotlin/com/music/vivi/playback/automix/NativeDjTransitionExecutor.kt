// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** Explicitly invoked, serialized coordinator. No decoding, threads, timers, legacy-plan bridge or
 * production registration. tick() and host callbacks belong to the player's application thread.
 * Position-based progress freezes on pause. Monotonic time is used only for deadlines/observations. */
class NativeDjTransitionExecutor(private val host: NativeDjHost,
    private val nowUs: () -> Long = { System.nanoTime()/1000 },
    private val config: NativeDjExecutionConfig = NativeDjExecutionConfig()) : AutoCloseable {
    companion object { const val VERSION = "tinium-native-dj-executor-v3-format-aware-clock-progress" }
    var status=NativeDjExecutionStatus(); private set
    private var plan:NativeTransitionPlan?=null
    private var incoming:NativeDjDeck?=null
    private var queue:NativeDjQueue?=null
    private var owned=false
    private var promoted=false
    private var preparedAt=0L
    private var dispatchedAt=0L
    private var dispatchPosition:Long?=null
    private var estimatedOffset:Long?=null
    private var concurrent=0L
    private var maxDrift=0L
    private var maxTickGap=0L
    private var preparationElapsed:Long?=null
    private var startupReference:NativeDjStartupObservation?=null
    private var startupLead=0L
    private var lastTick:Long?=null
    private var lastOutgoingProgressAtUs=0L
    private var lastIncomingProgressAtUs=0L
    private var lastOut:Long?=null
    private var lastIn:Long?=null
    private var outGain=1.0
    private var inGain=0.0
    private var content=NativeDjContentGains()
    private var pausedAt=0L
    private var pausedStage=NativeDjExecutionStage.ARMED
    private val events=ArrayDeque<NativeDjExecutionMetric>()
    val terminal get()=status.stage in setOf(NativeDjExecutionStage.COMPLETED,NativeDjExecutionStage.RECOVERED,
        NativeDjExecutionStage.CANCELED,NativeDjExecutionStage.FALLBACK,NativeDjExecutionStage.FAILED)

    @Synchronized fun start(proposal:NativeTransitionPlan, outgoingLevel:NativeDjLevel?=null,
                            incomingLevel:NativeDjLevel?=null):NativeDjExecutionStatus {
        check(status.stage==NativeDjExecutionStage.IDLE) { "One executor per attempt" }
        // Snapshot the only nested collection used here; no caller mutation after admission.
        plan=proposal.copy(candidate=proposal.candidate.copy(uncertainties=proposal.candidate.uncertainties.toSet()))
        stage(NativeDjExecutionStage.VALIDATING)
        try {
            val p=requireNotNull(plan)
            val q=host.queue();queue=q
            val reason=admission(p,q,host.outgoing.snapshot())
            if(reason!=null)return stop(NativeDjExecutionStage.FALLBACK,reason)
            if(!host.claim())return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.OWNERSHIP_BUSY)
            owned=true
            startupReference=host.startupObservation()
            content=NativeDjGain.content(p,outgoingLevel,incomingLevel)
            preparedAt=nowUs()
            stage(NativeDjExecutionStage.PREPARING)
            incoming=host.prepare(p)
            host.outgoing.gain(1.0);incoming!!.gain(0.0)
            tick()
        } catch(_:IllegalArgumentException) { stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.INVALID_PLAN) }
        catch(_:ArithmeticException) { stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.INVALID_PLAN) }
        catch(_:Exception) { stop(NativeDjExecutionStage.FAILED,NativeDjExecutionReason.PREPARATION_FAILED) }
        return status
    }

    /** Validate untrusted plan fields again at the playback boundary, using checked arithmetic. */
    private fun admission(p:NativeTransitionPlan,q:NativeDjQueue,out:NativeDjDeckState):NativeDjExecutionReason? {
        val c=p.candidate
        if(p.requiredFutureCapability!="PHASE32_NATIVE_PLAN_EXECUTOR")return NativeDjExecutionReason.UNSUPPORTED_CAPABILITY
        if(p.readiness!=NativePlanReadiness.TIMING_AND_TRANSIENT_SUPPORTED)return NativeDjExecutionReason.UNVERIFIED_SIGNAL
        if(p.plannerVersion!=NativeAwareAutoMixPlanner.VERSION || p.playbackRate!=1.0 ||
            p.outgoingProfile!=BitChordNativeAnalysisEngine.PROFILE || p.incomingProfile!=BitChordNativeAnalysisEngine.PROFILE)
            return NativeDjExecutionReason.INVALID_PLAN
        if(q!=NativeDjQueue(p.generation,p.outgoing.source,p.incoming.source))return NativeDjExecutionReason.STALE_QUEUE
        if(out.source!=p.outgoing.source)return NativeDjExecutionReason.SOURCE_MISMATCH
        if(out.rate!=1.0)return NativeDjExecutionReason.UNSUPPORTED_RATE
        if(!out.playing || !out.ready || out.failed || out.ended)return NativeDjExecutionReason.PLAYER_FAILED
        if(out.durationUs<=0 || abs(out.durationUs-p.outgoing.durationUs)>1000)return NativeDjExecutionReason.DURATION_MISMATCH
        if(!p.incoming.seekable)return NativeDjExecutionReason.NOT_SEEKABLE
        if(p.generation<0 || p.outgoing.durationUs<=0 || p.incoming.durationUs<=0 || c.overlapUs !in 500_000..48_000_000 ||
            c.outgoingCueUs<0 || c.incomingCueUs<0 || p.assumedEarliestOutgoingUs<0 || p.assumedPreparationLeadUs<0)
            return NativeDjExecutionReason.INVALID_PLAN
        val a=AudioIntervalUs(c.outgoingCueUs,Math.addExact(c.outgoingCueUs,c.overlapUs))
        val b=AudioIntervalUs(c.incomingCueUs,Math.addExact(c.incomingCueUs,c.overlapUs))
        val h=c.tempo;val alignment=c.alignment
        if(!p.outgoing.cueRegion.valid || !p.incoming.cueRegion.valid ||
            p.outgoing.cueRegion.endUs>p.outgoing.durationUs || p.incoming.cueRegion.endUs>p.incoming.durationUs ||
            !p.outgoingAnalyzedInterval.valid || !p.incomingAnalyzedInterval.valid ||
            p.outgoingAnalyzedInterval.endUs>p.outgoing.durationUs || p.incomingAnalyzedInterval.endUs>p.incoming.durationUs ||
            !p.outgoingAnalyzedInterval.contains(a) || !p.incomingAnalyzedInterval.contains(b) ||
            a.endUs>p.outgoing.durationUs || b.endUs>p.incoming.durationUs ||
            c.outgoingCueUs !in p.outgoing.cueRegion.startUs until p.outgoing.cueRegion.endUs ||
            c.incomingCueUs !in p.incoming.cueRegion.startUs until p.incoming.cueRegion.endUs ||
            c.outgoingCueUs<Math.addExact(p.assumedEarliestOutgoingUs,p.assumedPreparationLeadUs) ||
            listOf(p.outgoingRawNativeConfidence,p.incomingRawNativeConfidence).any {!it.isFinite() || it !in 0.0..1.0} ||
            listOf(c.outgoingTransientSupport,c.incomingTransientSupport).any {it==null || !it.isFinite() || it !in .6..1.0} ||
            (alignment.matchedPulses-1) !in setOf(8,16,32,64) || alignment.initialPhaseOffsetUs!=0L ||
            alignment.medianResidualUs !in 0..25_000 || alignment.p95ResidualUs !in alignment.medianResidualUs..40_000 ||
            alignment.maxResidualUs !in alignment.p95ResidualUs..70_000 || alignment.signedEndDriftUs !in -70_000..70_000 ||
            alignment.predictedTempoDriftUs !in -70_000..70_000 ||
            h !in NativeTempoCompatibility.hypotheses(h.measuredOutgoingBpm,h.measuredIncomingBpm) ||
            h.relativeDifference>0.03 || alignment.outgoingStride!=h.outgoingStride || alignment.incomingStride!=h.incomingStride)
            return NativeDjExecutionReason.INVALID_PLAN
        // A copied plan must not retain a 32-pulse grid while claiming a half-second overlap,
        // or change tempo without updating its own rate-1.0 drift model. The 2% duration
        // tolerance follows the current native evidence tempo/grid-consistency allowance;
        // it is not a beat-alignment tolerance and never adjusts the selected duration.
        val pulses=alignment.matchedPulses-1
        val expectedDuration=pulses*60_000_000.0/h.effectiveOutgoingBpm
        val expectedDrift=(c.overlapUs*(h.effectiveOutgoingBpm/h.effectiveIncomingBpm-1)).roundToLong()
        if(abs(c.overlapUs-expectedDuration)>max(70_000.0,expectedDuration*.02) ||
            abs(alignment.predictedTempoDriftUs-expectedDrift)>1 ||
            c.rank.supportedPulseIntervals!=pulses || c.rank.corroboratedTracks!=2 ||
            c.rank.p95ResidualUs!=alignment.p95ResidualUs || c.rank.incomingSkipUs!=c.incomingCueUs)
            return NativeDjExecutionReason.INVALID_PLAN
        // Preparation must start with the lead time the planner explicitly assumed.
        if(out.positionUs<0 || out.positionUs>c.outgoingCueUs-p.assumedPreparationLeadUs)
            return NativeDjExecutionReason.START_MISSED
        return null
    }

    @Synchronized fun tick():NativeDjExecutionStatus {
        if(status.stage==NativeDjExecutionStage.IDLE || terminal)return status
        try {
            val p=requireNotNull(plan);val c=p.candidate;val next=requireNotNull(incoming)
            if(host.queue()!=queue)return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.QUEUE_CHANGED)
            val a=host.outgoing.snapshot();val b=next.snapshot();val now=nowUs()
            val previousTick=lastTick
            if(previousTick!=null && now<previousTick)return stop(NativeDjExecutionStage.FAILED,NativeDjExecutionReason.HOST_FAILURE)
            val gap=previousTick?.let {now-it} ?: 0
            maxTickGap=max(maxTickGap,gap)
            if(a.source!=p.outgoing.source || b.source!=p.incoming.source)return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.SOURCE_MISMATCH)
            if(a.rate!=1.0 || b.rate!=1.0)return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.UNSUPPORTED_RATE)
            if(a.failed || (a.ended && a.positionUs<c.outgoingCueUs+c.overlapUs))return recoverOutgoingFailure(if(a.failed)NativeDjExecutionReason.PLAYER_FAILED else NativeDjExecutionReason.UNEXPECTED_EOS,b)
            if(b.failed || b.ended)return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.PLAYER_FAILED)
            if(a.durationUs<=0 || abs(a.durationUs-p.outgoing.durationUs)>1000 ||
                (b.durationUs>0 && abs(b.durationUs-p.incoming.durationUs)>1000))
                return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.DURATION_MISMATCH)
            if(status.stage==NativeDjExecutionStage.PAUSED) {
                if(a.playing || b.playing)return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.UNEXPECTED_PAUSE)
                if(lastOut!=null && (abs(a.positionUs-lastOut!!)>config.cueToleranceUs || abs(b.positionUs-lastIn!!)>config.cueToleranceUs))
                    return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.POSITION_DISCONTINUITY)
                lastTick=now;return status
            }
            // A per-attempt prediction affects only command scheduling, never media positions.
            // Recheck freshness while waiting; a pause/seek/queue change must not revive old data.
            if(status.stage==NativeDjExecutionStage.PREPARING || status.stage==NativeDjExecutionStage.ARMED) {
                startupLead=if(host.startupTimingCompatible())startupReference
                    ?.takeIf {it.observedPositionUs<=a.positionUs}?.leadUs(requireNotNull(queue),now) ?: 0L else 0L
            }
            val dispatchTarget=c.outgoingCueUs-startupLead
            val expectedEnd=a.ended && a.positionUs>=c.outgoingCueUs+c.overlapUs
            if(!a.ready && !expectedEnd)return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.BUFFERING)
            if(!a.playing && !expectedEnd)return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.UNEXPECTED_PAUSE)
            if(status.stage==NativeDjExecutionStage.ARMED && (b.playing || abs(b.positionUs-c.incomingCueUs)>config.cueToleranceUs))
                return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.SEEK_FAILED)
            // Media3 clock snapshots can update on different ticks. Monitor each deck
            // independently; requiring simultaneous deltas falsely labels live decks stalled.
            if(lastOut==null || a.positionUs>lastOut!!)lastOutgoingProgressAtUs=now
            if(lastIn==null || b.positionUs>lastIn!!)lastIncomingProgressAtUs=now
            if(status.stage!=NativeDjExecutionStage.PREPARING &&
                (now-lastOutgoingProgressAtUs>config.progressTimeoutUs ||
                    (estimatedOffset!=null && now-lastIncomingProgressAtUs>config.progressTimeoutUs)))
                return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.PLAYBACK_STALLED)
            if(lastOut!=null && (a.positionUs<lastOut!!-2000 || a.positionUs-lastOut!!>gap+250_000 ||
                b.positionUs<lastIn!!-2000 || ((status.stage==NativeDjExecutionStage.STARTING || status.stage==NativeDjExecutionStage.MIXING) && b.positionUs-lastIn!!>gap+250_000)))
                return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.POSITION_DISCONTINUITY)
            if(status.stage==NativeDjExecutionStage.PREPARING) {
                if(now-preparedAt>config.preparationTimeoutUs)return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.PREPARATION_TIMEOUT)
                if(b.ready && b.durationUs>0 && b.seekable && !b.playing && abs(b.positionUs-c.incomingCueUs)<=config.cueToleranceUs) {
                    preparationElapsed=now-preparedAt;stage(NativeDjExecutionStage.ARMED)
                } else if(a.positionUs>=dispatchTarget)return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.START_MISSED)
            }
            if(status.stage==NativeDjExecutionStage.ARMED && a.positionUs>=dispatchTarget) {
                if(!a.ready || !a.playing || !b.ready || b.playing || abs(b.positionUs-c.incomingCueUs)>config.cueToleranceUs)
                    return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.SEEK_FAILED)
                if(a.positionUs-dispatchTarget>config.maxStartLatenessUs)return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.START_MISSED)
                dispatchPosition=a.positionUs;dispatchedAt=now;lastIncomingProgressAtUs=now
                next.play();stage(if(a.positionUs<c.outgoingCueUs)NativeDjExecutionStage.STARTING else NativeDjExecutionStage.MIXING)
            }
            if(status.stage==NativeDjExecutionStage.STARTING || status.stage==NativeDjExecutionStage.MIXING) {
                if((!a.ready && !(a.ended && a.positionUs>=c.outgoingCueUs+c.overlapUs)) || !b.ready)return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.BUFFERING)
                if(!a.playing && !(a.ended && a.positionUs>=c.outgoingCueUs+c.overlapUs))return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.UNEXPECTED_PAUSE)
                // Keep the original signed anchor, including early presentation. Subtracting the
                // measured lead here would hide real drift. No seek/rate correction is performed.
                val outElapsed=a.positionUs-c.outgoingCueUs
                val inElapsed=b.positionUs-c.incomingCueUs
                if(b.playing && inElapsed>0) {
                    if(estimatedOffset==null)estimatedOffset=outElapsed-inElapsed
                    val drift=abs(outElapsed-inElapsed);maxDrift=max(maxDrift,drift)
                    if(drift>config.maxObservedDriftUs)return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.EXCESSIVE_DRIFT)
                    // Common cumulative media-clock progression from the original anchors,
                    // not a sum of coincident per-poll deltas. Early muted time is excluded.
                    concurrent=maxOf(concurrent,minOf(outElapsed.coerceAtLeast(0),inElapsed.coerceAtLeast(0)))
                } else if(now-dispatchedAt>config.startTimeoutUs || estimatedOffset!=null)
                    return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.START_FAILED)
                if(status.stage==NativeDjExecutionStage.STARTING && outElapsed>=0)stage(NativeDjExecutionStage.MIXING)
                val gains=NativeDjGain.gains(outElapsed.coerceAtLeast(0).toDouble()/c.overlapUs,config.curve,content)
                outGain=gains.outgoing;inGain=gains.incoming
                host.outgoing.gain(outGain);next.gain(inGain)
                if(outElapsed>=c.overlapUs) {
                    if(estimatedOffset==null || !b.playing || inElapsed<c.overlapUs-config.maxObservedDriftUs)
                        return stop(NativeDjExecutionStage.FALLBACK,NativeDjExecutionReason.START_FAILED)
                    stage(NativeDjExecutionStage.COMPLETING)
                    if(host.queue()!=queue || !host.promote(requireNotNull(queue),next))return stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.STALE_QUEUE)
                    promoted=true;incoming=null
                    host.outgoing.release();host.restore();owned=false
                    stage(NativeDjExecutionStage.COMPLETED)
                }
            }
            lastOut=a.positionUs;lastIn=b.positionUs;lastTick=now
            if(events.lastOrNull()?.monotonicUs?.let {now-it>=1_000_000}!=false)record(a.positionUs,b.positionUs)
        } catch(_:Exception) { stop(NativeDjExecutionStage.FAILED,NativeDjExecutionReason.HOST_FAILURE) }
        return status
    }

    @Synchronized fun pause(reason:NativeDjExecutionReason?=null) {
        if(terminal || status.stage !in setOf(NativeDjExecutionStage.ARMED,NativeDjExecutionStage.STARTING,NativeDjExecutionStage.MIXING,NativeDjExecutionStage.PREPARING))return
        pausedStage=status.stage;pausedAt=nowUs()
        if(pausedStage in setOf(NativeDjExecutionStage.ARMED,NativeDjExecutionStage.PREPARING)) {
            startupReference=null;startupLead=0
        }
        try { host.outgoing.pause();incoming?.pause();lastOut=host.outgoing.snapshot().positionUs;lastIn=incoming?.snapshot()?.positionUs
            stage(NativeDjExecutionStage.PAUSED,reason) } catch(_:Exception){stop(NativeDjExecutionStage.FAILED,NativeDjExecutionReason.HOST_FAILURE)}
    }
    @Synchronized fun resume() {
        if(status.stage!=NativeDjExecutionStage.PAUSED)return
        tick();if(status.stage!=NativeDjExecutionStage.PAUSED)return
        try {
            val now=nowUs();val pauseDuration=now-pausedAt
            if(pausedStage==NativeDjExecutionStage.PREPARING)preparedAt+=pauseDuration
            dispatchedAt+=pauseDuration
            // Pausing an as-yet-unpresented early start destroys the pending startup timing.
            // Resume normal outgoing music rather than restart an unverifiable incoming cue.
            if(pausedStage==NativeDjExecutionStage.STARTING && estimatedOffset==null) {
                host.outgoing.play()
                stop(NativeDjExecutionStage.CANCELED,NativeDjExecutionReason.STARTUP_INTERRUPTED)
                return
            }
            host.outgoing.play();if(pausedStage in setOf(NativeDjExecutionStage.STARTING,NativeDjExecutionStage.MIXING))incoming?.play()
            lastTick=now;lastOutgoingProgressAtUs=now;lastIncomingProgressAtUs=now;stage(pausedStage)
        }catch(_:Exception){stop(NativeDjExecutionStage.FAILED,NativeDjExecutionReason.HOST_FAILURE)}
    }
    @Synchronized fun cancel(reason:NativeDjExecutionReason=NativeDjExecutionReason.CANCELED):NativeDjExecutionStatus =
        if(terminal || status.stage==NativeDjExecutionStage.IDLE)status else stop(NativeDjExecutionStage.CANCELED,reason)
    private fun recoverOutgoingFailure(reason:NativeDjExecutionReason,b:NativeDjDeckState):NativeDjExecutionStatus {
        // Only already-audible, healthy incoming music may be promoted as recovery. Not a successful aligned mix.
        if(status.stage==NativeDjExecutionStage.MIXING && b.ready && b.playing && inGain>=.2 && host.queue()==queue) {
            val next=requireNotNull(incoming)
            next.gain(1.0)
            if(host.promote(requireNotNull(queue),next)) {
                promoted=true;incoming=null;host.outgoing.release();host.restore();owned=false
                stage(NativeDjExecutionStage.RECOVERED,reason);return status
            }
        }
        return stop(NativeDjExecutionStage.FAILED,reason)
    }
    private fun stop(stage:NativeDjExecutionStage,reason:NativeDjExecutionReason):NativeDjExecutionStatus {
        if(terminal)return status
        var cleanupFailed=false
        try { incoming?.release() } catch(_:Exception) { cleanupFailed=true }
        incoming=null
        if(owned) {
            try {host.outgoing.gain(1.0)}catch(_:Exception){cleanupFailed=true}
            try {host.restore()}catch(_:Exception){cleanupFailed=true}
            owned=false
        }
        outGain=1.0;inGain=0.0
        stage(stage,reason)
        // Preserve the original cancellation/failure event and expose cleanup failure separately.
        if(cleanupFailed)stage(NativeDjExecutionStage.FAILED,NativeDjExecutionReason.HOST_FAILURE)
        return status
    }
    private fun stage(stage:NativeDjExecutionStage,reason:NativeDjExecutionReason?=null) {status=NativeDjExecutionStatus(stage,reason);record(lastOut,lastIn)}
    private fun record(a:Long?,b:Long?) {
        if(events.size==128)events.removeFirst()
        events.addLast(NativeDjExecutionMetric(nowUs(),status.stage,a,b,outGain,inGain,status.reason))
    }
    @Synchronized fun measurements():NativeDjExecutionMeasurements {
        val c=plan?.candidate
        return NativeDjExecutionMeasurements(c?.outgoingCueUs ?: 0,c?.incomingCueUs ?: 0,c?.overlapUs ?: 0,
            dispatchPosition,estimatedOffset,concurrent,maxDrift,maxTickGap,preparationElapsed,if(promoted)1 else 0,events.toList(),startupLead)
    }
    @Synchronized fun report():NativeDjExecutionReport {
        val p=plan;val c=p?.candidate
        return NativeDjExecutionReport(VERSION,p?.plannerVersion?.takeIf {it==NativeAwareAutoMixPlanner.VERSION},
            p?.outgoingProfile?.takeIf {it==BitChordNativeAnalysisEngine.PROFILE}?.backend,
            p?.outgoingProfile?.takeIf {it==BitChordNativeAnalysisEngine.PROFILE}?.preprocessingVersion,
            config.curve,content,NativeDjLevel.VERSION,c?.tempo?.measuredOutgoingBpm?.takeIf {it.isFinite() && it in 60.0..220.0},
            c?.tempo?.measuredIncomingBpm?.takeIf {it.isFinite() && it in 60.0..220.0},
            c?.tempo?.relation,c?.alignment,p?.let {try {Math.addExact(it.candidate.outgoingCueUs,it.candidate.overlapUs)<it.outgoing.durationUs} catch(_:ArithmeticException){false}} ?: false,
            c?.uncertainties ?: emptySet(),status,measurements())
    }
    @Synchronized override fun close() {cancel(NativeDjExecutionReason.SHUTDOWN)}
}
