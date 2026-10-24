package com.music.vivi.playback.automix

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Typed fields prevent accidentally recording an arbitrary log string, URL or exception message. */
enum class MixEvent { DECODER, PAIR, SOURCE_READY, WINDOW_START, WINDOW_DONE, CACHE_HIT, FAILURE, RETRY,
    RECOVERED, COARSE_READY, CANDIDATE, REJECTION, PLAN, PREPARING, READY, START, AUTOMATION, COMPLETE, SKIPPED, CANCELED }
enum class MixMetric { POSITION_MS, OUT_DURATION_MS, IN_DURATION_MS, REGION_START_MS, REGION_LENGTH_MS,
    BPM, GRID_CONFIDENCE, PHASE_CONFIDENCE, RMS, PEAK, KEY_TONIC, KEY_MINOR, KEY_CONFIDENCE,
    BASS_FRACTION, VOCAL_RISK, SPECTRAL_DENSITY, SCORE, START_MS, CUE_MS, OVERLAP_MS, BEATS,
    OUT_GAIN, IN_GAIN, OUT_BASS_CUT, IN_BASS_CUT, OUT_TREBLE_CUT, IN_TREBLE_CUT, IN_RATE,
    HARMONIC_COMPATIBLE, LEAD_MS, PREPARATION_OBSERVED_MS, ACTUAL_START_MS, ELAPSED_MS, QUEUED, WORKERS, COUNT, NATIVE_RAW_CONFIDENCE, NATIVE_ANALYSIS_MS }
enum class MixStage { SOURCE, SCAN, REFINEMENT, PLANNING, PREPARATION, MIX, RECOVERY }
enum class MixErrorType { CODED_ANALYSIS, CANCELLATION, INTERRUPTED, IO, MEDIA_CODEC, INVALID_STATE, OTHER;
    companion object { fun of(error: Throwable): MixErrorType {
        val cause=error.cause ?: error
        return when {
            cause.javaClass.simpleName=="CodecException" -> MEDIA_CODEC
            error is AnalysisUnavailable -> CODED_ANALYSIS
            error is java.util.concurrent.CancellationException -> CANCELLATION
            error is InterruptedException -> INTERRUPTED
            error is java.io.IOException -> IO
            error is IllegalStateException -> INVALID_STATE
            else -> OTHER
        }
    } }
}
enum class MixRole { OUTGOING, INCOMING }
data class MixRecord(val elapsedMs: Long, val event: MixEvent, val metrics: Map<MixMetric,Double>,
                     val role: MixRole?=null, val failure: AutoMixFailure?=null,
                     val tier: AutoMixTier?=null, val technique: MixTechnique?=null,
                     val cueReason: CueReason?=null,val stage: MixStage?=null,val errorType: MixErrorType?=null, val decoder: DecoderDiagnostic?=null,val native:NativeServiceRecord?=null)
data class MixAttempt(val number: Long,val outgoingHash: String,val incomingHash: String,val records: List<MixRecord>)
data class MixHistory(val session: String,val startedAt: String,val attempts: List<MixAttempt>)
data class MixReportHeader(val versionName: String,val versionCode: Long,val androidVersion: String,
                           val model: String,val enabled: Boolean,val mode: AutoMixPerformance,
                           val generatedAt: String=Instant.now().toString(),val engineMode:AutoMixEngineMode=AutoMixEngineMode.LEGACY)

/** Service-scoped history. No PCM, disk writes, raw identities, messages or network headers. */
class AutoMixRecorder(private val maxAttempts: Int=30, private val maxEvents: Int=128,
                      private val nanoTime: ()->Long=System::nanoTime) {
    private val session=UUID.randomUUID().toString()
    private val hashSalt=UUID.randomUUID().toString()
    private val startedAt=Instant.now().toString()
    private val started=nanoTime()
    private var sequence=0L
    private data class MutableAttempt(val number: Long,val outgoing: String,val incoming: String,val records: ArrayDeque<MixRecord>)
    private val attempts=ArrayDeque<MutableAttempt>()
    private fun hash(identity: String)=MessageDigest.getInstance("SHA-256").digest((hashSalt+identity).toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }
    @Synchronized fun begin(outgoing: String,incoming: String): Long {
        val attempt=MutableAttempt(++sequence,hash(outgoing),hash(incoming),ArrayDeque())
        attempts.addLast(attempt)
        while(attempts.size>maxAttempts) attempts.removeFirst()
        return attempt.number
    }
    @Synchronized fun record(attempt: Long,event: MixEvent,metrics: Map<MixMetric,Double> = emptyMap(),
                             role: MixRole?=null,failure: AutoMixFailure?=null,tier: AutoMixTier?=null,
                             technique: MixTechnique?=null,cueReason: CueReason?=null,stage: MixStage?=null,errorType: MixErrorType?=null, decoder: DecoderDiagnostic?=null) {
        val target=attempts.firstOrNull { it.number==attempt } ?: return // late obsolete decoder result
        val values=metrics.filterValues { it.isFinite() }.toMap()
        target.records.addLast(MixRecord((nanoTime()-started)/1_000_000,event,values,role,failure,tier,technique,cueReason,stage,errorType,decoder))
        while(target.records.size>maxEvents) target.records.removeFirst()
    }
    @Synchronized fun native(attempt:Long,event:NativeServiceEvent,reason:String?=null,
        plan:NativeTransitionPlan?=null,execution:NativeDjExecutionReport?=null,generation:Long?=null,
        elapsedUs:Long?=null,candidateCount:Int?=null,evidence:AnalysisResult?=null,role:MixRole?=null,
        route:NativeRouteDecision?=null,planningRejections:Map<PlanningRejection,Int> = emptyMap(),
        laterCandidateRejections:Map<PlanningRejection,Int> = emptyMap(),region:NativeRegionAudit?=null,reuse:NativeReuseSnapshot?=null) {
        val target=attempts.firstOrNull {it.number==attempt} ?: return
        val c=plan?.candidate
        val record=NativeServiceRecord(event,reason?.takeIf {it.matches(Regex("[A-Z0-9_]{1,80}"))},
            generation,elapsedUs,candidateCount,c?.outgoingCueUs,c?.incomingCueUs,c?.overlapUs,
            c?.tempo,c?.alignment,plan?.readiness,c?.uncertainties?.toSet(),
            execution?.copy(measurements=execution.measurements.copy(events=execution.measurements.events.takeLast(1))),
            c?.placement,plan?.placementPolicy,route,planningRejections.toMap(),c?.rank,laterCandidateRejections.toMap(),region,reuse)
        val values=mutableMapOf<MixMetric,Double>()
        (evidence as? AnalysisResult.Completed)?.let { result ->
            fun add(key:MixMetric,value:Double?) {if(value!=null && value.isFinite())values[key]=value}
            add(MixMetric.BPM,result.evidence.tempoBpm.value)
            add(MixMetric.NATIVE_RAW_CONFIDENCE,result.evidence.gridConfidence.value)
            add(MixMetric.RMS,result.evidence.rms.value);add(MixMetric.PEAK,result.evidence.peak.value)
            add(MixMetric.REGION_START_MS,result.metadata.sampleInterval?.startUs?.div(1000.0))
            add(MixMetric.REGION_LENGTH_MS,result.metadata.sampleInterval?.let {(it.endUs-it.startUs)/1000.0})
            add(MixMetric.NATIVE_ANALYSIS_MS,result.metadata.nativeDiagnostic?.elapsedUs?.div(1000.0))
        }
        val failure=(evidence as? AnalysisResult.Failed)?.failure
        target.records.addLast(MixRecord((nanoTime()-started)/1_000_000,MixEvent.AUTOMATION,values,
            role=role,failure=failure?.category,native=record.copy(reason=record.reason ?: failure?.code?.takeIf {it.matches(Regex("[A-Z0-9_]{1,80}"))})))
        while(target.records.size>maxEvents)target.records.removeFirst()
    }
    @Synchronized fun snapshot()=MixHistory(session,startedAt,attempts.map { MixAttempt(it.number,it.outgoing,it.incoming,it.records.toList()) })

    companion object {
        fun render(history: MixHistory,header: MixReportHeader): String = buildString {
            fun safe(s: String)=s.take(100).replace(Regex("[^A-Za-z0-9 ._:+-]"),"_")
            appendLine("Tinium AutoMix diagnostic report — schema 2")
            appendLine("Generated: ${safe(header.generatedAt)}")
            appendLine("Tinium ${safe(header.versionName)} / versionCode ${header.versionCode}")
            appendLine("Android ${safe(header.androidVersion)} / model ${safe(header.model)}")
            appendLine("AutoMix enabled: ${header.enabled} / mode ${header.mode} / analysis workers ${header.mode.workers}")
            appendLine("Native DJ extension schema 1: explicitly selected experiment; media-clock observations only.")
            appendLine("NATIVE_RAW_CONFIDENCE is uncalibrated upstream evidence, not a legacy score or probability.")
            appendLine("Selected engine: ${header.engineMode} (persisted selection; session safety suspension is recorded per attempt)")
            appendLine("Session: ${history.session} / started ${history.startedAt}")
            appendLine("Track hashes are salted for this service session. No titles, URLs or audio are included.")
            appendLine("Automation is commanded deck gain/filter state, not post-EQ/Bluetooth measurement.")
            appendLine("Counters cover retained history only; queued/failed plans are not counted as executed mixes.")
            val records=history.attempts.flatMap { it.records }
            appendLine("\nSession summary")
            appendLine("Retained pairs: ${history.attempts.size}")
            AutoMixTier.entries.forEach { tier -> appendLine("Started tier $tier: ${records.count { it.event==MixEvent.START && it.tier==tier }}") }
            val nativeAttempts=history.attempts.filter {a->a.records.any {it.native!=null}}
            appendLine("Native pairs: ${nativeAttempts.size} / native plans: ${nativeAttempts.count {a->a.records.any {it.native?.event==NativeServiceEvent.PLAN}}}")
            appendLine("Native mixes observed: ${nativeAttempts.count {a->a.records.any {it.native?.execution?.status?.stage==NativeDjExecutionStage.MIXING}}}")
            appendLine("Native completed: ${nativeAttempts.count {a->a.records.any {it.native?.execution?.status?.stage==NativeDjExecutionStage.COMPLETED}}} / native fallback pairs: ${nativeAttempts.count {a->a.records.any {it.native?.event==NativeServiceEvent.FALLBACK}}}")
            appendLine("Completed handoffs: ${records.count { it.event==MixEvent.COMPLETE } + nativeAttempts.count {a->a.records.any {it.native?.execution?.status?.stage in setOf(NativeDjExecutionStage.COMPLETED,NativeDjExecutionStage.RECOVERED)}}}")
            appendLine("Skipped: ${records.count { it.event==MixEvent.SKIPPED }} / canceled: ${records.count { it.event==MixEvent.CANCELED }}")
            history.attempts.flatMap { a -> a.records.mapNotNull { it.failure }.distinct() }.groupingBy { it }.eachCount()
                .toSortedMap(compareBy { it.name }).forEach { (reason,count) -> appendLine("Pairs with recorded failure/rejection $reason: $count") }
            history.attempts.forEach { attempt ->
                appendLine("\nPair ${attempt.number}: outgoing ${attempt.outgoingHash} / incoming ${attempt.incomingHash}")
                attempt.records.forEach { r ->
                    append("+${r.elapsedMs}ms ${r.event}")
                    r.stage?.let { append(" stage=$it") };r.errorType?.let { append(" exceptionType=$it") }
                    r.role?.let { append(" role=$it") };r.failure?.let { append(" failure=$it") }
                    r.tier?.let { append(" tier=$it") };r.technique?.let { append(" technique=$it") }
                    r.cueReason?.let { append(" cueReason=$it") }
                    r.decoder?.let { append(it.reportFields()) }
                    r.native?.let { n ->
                        append(" nativeMode=EXPERIMENTAL event=${n.event} planner=${NativeAwareAutoMixPlanner.VERSION}")
                        append(" executor=${NativeDjTransitionExecutor.VERSION} backend=${BitChordNativeAnalysisEngine.BACKEND}")
                        append(" preprocessing=${PcmPreprocessing.BANDLIMITED} generation=${n.generation}")
                        n.reason?.let {append(" reason=$it")}
                        n.elapsedUs?.let {append(" analysisElapsedUs=$it")}
                        n.candidateCount?.let {append(" candidates=$it")}
                        if(n.outgoingCueUs!=null)append(" outgoingCueUs=${n.outgoingCueUs} incomingCueUs=${n.incomingCueUs} overlapUs=${n.overlapUs} readiness=${n.readiness}")
                        n.tempo?.let {append(" tempo=$it")};n.alignment?.let {append(" alignment=$it")}
                        n.uncertainties?.let {append(" uncertainties=$it")}
                        n.placement?.let {append(" placementSchema=1 placementPolicy=${n.placementPolicy} selectedRank=1 placement=$it rank=${n.rank}")}
                        if(n.planningRejections.isNotEmpty())append(" candidateRejections=${n.planningRejections}")
                        if(n.laterCandidateRejections.isNotEmpty())append(" laterCueRejections=${n.laterCandidateRejections}")
                        n.region?.let {append(" analysisRegionSchema=1 region=$it")}
                        n.reuse?.let {append(" analysisReuseSchema=1 reuse=$it")}
                        n.route?.let {append(" routeSchema=1 route=$it")}
                        n.execution?.let { e ->
                            val m=e.measurements
                            append(" stage=${e.status.stage} reason=${e.status.reason} curve=${e.curve} contentGains=${e.contentGains}")
                            append(" startCommandUs=${m.commandOutgoingUs} startupLeadUs=${m.startupLeadUs} firstOffsetUs=${m.estimatedStartOffsetUs}")
                            append(" commonProgressUs=${m.concurrentProgressUs} maxDriftUs=${m.maximumObservedDriftUs} maxTickGapUs=${m.maximumTickGapUs}")
                            append(" preparationUs=${m.preparationElapsedUs} promotions=${m.promotions}")
                            append(" clockSchema=3 maxRawOffsetUs=${m.maximumRawOffsetUs} maxQuerySpanUs=${m.maximumQuerySpanUs} lastClock=${m.lastClock}")
                            append(" commandMonotonicUs=${m.commandMonotonicUs} promotionOutgoingUs=${m.promotionOutgoingUs} prediction=${m.predictionState}")
                            append(" firstIncomingProgressMonotonicUs=${m.firstIncomingProgressMonotonicUs} fadeStartedOutgoingUs=${m.fadeStartedOutgoingUs} steadyMinOffsetUs=${m.steadyMinimumOffsetUs} steadyMaxOffsetUs=${m.steadyMaximumOffsetUs}")
                            e.outputTiming?.let {append(" rawOutputTimestamps=$it")}
                            e.startupReference?.let {append(" startupReference=$it")}
                            m.events.lastOrNull()?.let {append(" lastPlayerObservation=$it")}
                        }
                    }
                    r.metrics.forEach { (key,value) -> append(" $key=$value") }
                    appendLine()
                }
            }
            appendLine("\nOmitted values were not collected. These diagnostics do not certify audible quality or external EQ processing.")
        }
    }
}

/** Only compact controlled fields; no plan source handles, raw PCM, URLs or titles. */
enum class NativeServiceEvent { PAIR, EVIDENCE, PLAN, EXECUTION, FALLBACK, CANCELED, ROUTE, ANALYSIS_REUSE }
data class NativeServiceRecord(val event:NativeServiceEvent,val reason:String?,val generation:Long?,
    val elapsedUs:Long?,val candidateCount:Int?,val outgoingCueUs:Long?,val incomingCueUs:Long?,val overlapUs:Long?,
    val tempo:TempoHypothesis?,val alignment:BeatAlignment?,val readiness:NativePlanReadiness?,
    val uncertainties:Set<PlanningUncertainty>?,val execution:NativeDjExecutionReport?,
    val placement:NativeCuePlacement?=null,val placementPolicy:NativePlacementPolicy?=null,
    val route:NativeRouteDecision?=null,val planningRejections:Map<PlanningRejection,Int> = emptyMap(),val rank:CandidateRank?=null,
    val laterCandidateRejections:Map<PlanningRejection,Int> = emptyMap(),val region:NativeRegionAudit?=null,val reuse:NativeReuseSnapshot?=null)
