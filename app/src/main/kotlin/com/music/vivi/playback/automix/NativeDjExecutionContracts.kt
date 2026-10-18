// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** All positions are track-relative microseconds. Host methods run on its player application thread. */
data class NativeDjQueue(val generation: Long, val outgoing: AnalysisSourceIdentity, val incoming: AnalysisSourceIdentity)
/** One caller-observed cold start on the same outgoing source/queue. Monotonic command/observation
 * times and track positions stay separate. This predicts command lead, NOT acoustic latency or
 * verified incoming alignment. No device constants or retained cross-session calibration. */
data class NativeDjStartupObservation(val source:AnalysisSourceIdentity,val generation:Long,
    val commandMonotonicUs:Long,val commandPositionUs:Long,
    val observedMonotonicUs:Long,val observedPositionUs:Long) {
    fun leadUs(queue:NativeDjQueue,nowUs:Long):Long? = try {
        val elapsed=Math.subtractExact(observedMonotonicUs,commandMonotonicUs)
        val progress=Math.subtractExact(observedPositionUs,commandPositionUs)
        val age=Math.subtractExact(nowUs,observedMonotonicUs)
        val delay=Math.subtractExact(elapsed,progress)
        if(source!=queue.outgoing || generation!=queue.generation || commandPositionUs<0 ||
            progress !in 1..100_000 || elapsed !in 1..500_000 || age !in 0..10_000_000 ||
            delay !in 0..250_000)null else delay
    }catch(_:ArithmeticException){null}
}
data class NativeDjDeckState(val source: AnalysisSourceIdentity, val durationUs: Long, val positionUs: Long,
    val ready: Boolean, val playing: Boolean, val ended: Boolean = false, val failed: Boolean = false,
    val seekable: Boolean = true, val rate: Double = 1.0)
interface NativeDjDeck {
    fun snapshot(): NativeDjDeckState
    fun play()
    fun pause()
    /** Relative deck gain only. Host applies current user volume/focus once, never system volume. */
    fun gain(value: Double)
    fun release()
}
interface NativeDjHost {
    val outgoing: NativeDjDeck
    fun queue(): NativeDjQueue
    fun observeClocks(incoming:NativeDjDeck,nowUs:()->Long)=NativeDjClockPair.capture(outgoing,incoming,nowUs)
    /** Optional raw output timestamps for diagnostics only; never a correction to beat/media clocks. */
    fun outputTiming():NativeDjOutputTiming?=null
    /** Optional fresh observation, sampled once per attempt. Absent/invalid means no early start. */
    fun startupObservation():NativeDjStartupObservation?=null
    /** True only for known compatible prepared audio formats. Unknown means no prediction. */
    fun startupTimingCompatible():Boolean=false
    /** Atomic exclusive ownership; must exclude legacy fade controllers. */
    fun claim(): Boolean
    /** Prepare paused at the exact planned cue, nonblocking. No queue promotion here. */
    fun prepare(plan: NativeTransitionPlan): NativeDjDeck
    /** Compare generation/identities again and transfer ownership exactly once. */
    fun promote(expected: NativeDjQueue, incoming: NativeDjDeck): Boolean
    /** Release lease, restore normal end-of-item policy; never seek or restart current music. */
    fun restore()
}
enum class NativeDjExecutionStage { IDLE, VALIDATING, PREPARING, ARMED, STARTING, MIXING, PAUSED, COMPLETING, COMPLETED, RECOVERED, CANCELED, FALLBACK, FAILED }
enum class NativeDjExecutionReason {
    INVALID_PLAN, UNSUPPORTED_CAPABILITY, UNVERIFIED_SIGNAL, STALE_QUEUE, OWNERSHIP_BUSY,
    SOURCE_MISMATCH, DURATION_MISMATCH, UNSUPPORTED_RATE, NOT_SEEKABLE, PREPARATION_FAILED,
    PREPARATION_TIMEOUT, SEEK_FAILED, START_MISSED, START_FAILED, STARTUP_INTERRUPTED, PLAYER_FAILED, UNEXPECTED_EOS,
    BUFFERING, PLAYBACK_STALLED, UNEXPECTED_PAUSE, POSITION_DISCONTINUITY, EXCESSIVE_DRIFT, USER_SEEK, USER_SKIP,
    QUEUE_CHANGED, ROUTE_CHANGED, FOCUS_LOST, CANCELED, SHUTDOWN, HOST_FAILURE, CLOCK_OBSERVATION_UNCERTAIN,
}
data class NativeDjExecutionConfig(val curve: NativeDjCurve = NativeDjCurve.RESTRAINED_DJ,
    val maxStartLatenessUs: Long = 40_000, val maxObservedDriftUs: Long = 120_000,
    val cueToleranceUs: Long = 15_000, val preparationTimeoutUs: Long = 10_000_000,
    val startTimeoutUs: Long = 1_500_000, val progressTimeoutUs:Long = 2_000_000) {
    init {
        require(maxStartLatenessUs in 0..40_000 && maxObservedDriftUs in 1..120_000)
        require(progressTimeoutUs in 1..2_000_000)
        require(cueToleranceUs in 0..15_000 && preparationTimeoutUs in 1..10_000_000 && startTimeoutUs in 1..1_500_000)
    }
}
data class NativeDjExecutionStatus(val stage: NativeDjExecutionStage = NativeDjExecutionStage.IDLE,
    val reason: NativeDjExecutionReason? = null)
data class NativeDjExecutionMetric(val monotonicUs: Long, val stage: NativeDjExecutionStage,
    val outgoingPositionUs: Long?, val incomingPositionUs: Long?, val outgoingGain: Double,
    val incomingGain: Double, val reason: NativeDjExecutionReason?)
/** Observations of media clocks, NOT rendered/acoustic timestamps. No raw identities or URLs.
 * concurrentProgressUs is the common cumulative progression from the two original cue anchors;
 * it does not require both independently sampled clocks to change on the same polling tick. */
data class NativeDjExecutionMeasurements(val plannedStartUs: Long, val plannedIncomingCueUs: Long,
    val plannedOverlapUs: Long, val commandOutgoingUs: Long?, val estimatedStartOffsetUs: Long?,
    val concurrentProgressUs: Long, val maximumObservedDriftUs: Long, val maximumTickGapUs: Long,
    val preparationElapsedUs: Long?, val promotions: Int, val events: List<NativeDjExecutionMetric>,
    val startupLeadUs:Long=0,val maximumRawOffsetUs:Long=0,val maximumQuerySpanUs:Long=0,
    val lastClock:NativeDjClockEvidence?=null,val commandMonotonicUs:Long?=null,
    val promotionOutgoingUs:Long?=null,val predictionState:NativeDjPredictionState=NativeDjPredictionState.ABSENT,
    val firstIncomingProgressMonotonicUs:Long?=null,val fadeStartedOutgoingUs:Long?=null,
    val steadyMinimumOffsetUs:Long?=null,val steadyMaximumOffsetUs:Long?=null)

enum class NativeDjPredictionState { ABSENT, INCOMPATIBLE_FORMAT, EXPIRED_OR_INVALID, AVAILABLE }

/** Safe per-attempt report: identities/URLs deliberately omitted, not unsalted hashes. */
data class NativeDjExecutionReport(val executorVersion:String,val plannerVersion:String?,
    val backend:AnalysisBackend?,val preprocessing:String?,val curve:NativeDjCurve,
    val contentGains:NativeDjContentGains,val levelMethod:String,
    val outgoingBpm:Double?,val incomingBpm:Double?,val metricalRelation:MetricalRelation?,
    val plannedAlignment:BeatAlignment?,val plannedEarlyExit:Boolean,
    val uncertainties:Set<PlanningUncertainty>,val status:NativeDjExecutionStatus,
    val measurements:NativeDjExecutionMeasurements,
    val timingMethod:String="bracketed common-instant media-clock observations with conservative query uncertainty; acoustic alignment unmeasured",
    val outputTiming:NativeDjOutputTiming?=null)

data class NativeDjOutputTimestamp(val framePosition:Long,val nanoTime:Long,val sampleRate:Int)
data class NativeDjOutputTiming(val outgoing:NativeDjOutputTimestamp?,val incoming:NativeDjOutputTimestamp?,val observedMonotonicUs:Long)
