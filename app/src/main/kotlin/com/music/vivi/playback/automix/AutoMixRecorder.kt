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
    HARMONIC_COMPATIBLE, LEAD_MS, PREPARATION_OBSERVED_MS, ACTUAL_START_MS, ELAPSED_MS, QUEUED, WORKERS, COUNT }
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
                     val cueReason: CueReason?=null,val stage: MixStage?=null,val errorType: MixErrorType?=null, val decoder: DecoderDiagnostic?=null)
data class MixAttempt(val number: Long,val outgoingHash: String,val incomingHash: String,val records: List<MixRecord>)
data class MixHistory(val session: String,val startedAt: String,val attempts: List<MixAttempt>)
data class MixReportHeader(val versionName: String,val versionCode: Long,val androidVersion: String,
                           val model: String,val enabled: Boolean,val mode: AutoMixPerformance,
                           val generatedAt: String=Instant.now().toString())

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
    @Synchronized fun snapshot()=MixHistory(session,startedAt,attempts.map { MixAttempt(it.number,it.outgoing,it.incoming,it.records.toList()) })

    companion object {
        fun render(history: MixHistory,header: MixReportHeader): String = buildString {
            fun safe(s: String)=s.take(100).replace(Regex("[^A-Za-z0-9 ._:+-]"),"_")
            appendLine("Tinium AutoMix diagnostic report — schema 2")
            appendLine("Generated: ${safe(header.generatedAt)}")
            appendLine("Tinium ${safe(header.versionName)} / versionCode ${header.versionCode}")
            appendLine("Android ${safe(header.androidVersion)} / model ${safe(header.model)}")
            appendLine("AutoMix enabled: ${header.enabled} / mode ${header.mode} / analysis workers ${header.mode.workers}")
            appendLine("Session: ${history.session} / started ${history.startedAt}")
            appendLine("Track hashes are salted for this service session. No titles, URLs or audio are included.")
            appendLine("Automation is commanded deck gain/filter state, not post-EQ/Bluetooth measurement.")
            appendLine("Counters cover retained history only; queued/failed plans are not counted as executed mixes.")
            val records=history.attempts.flatMap { it.records }
            appendLine("\nSession summary")
            appendLine("Retained pairs: ${history.attempts.size}")
            AutoMixTier.entries.forEach { tier -> appendLine("Started tier $tier: ${records.count { it.event==MixEvent.START && it.tier==tier }}") }
            appendLine("Completed handoffs: ${records.count { it.event==MixEvent.COMPLETE }}")
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
                    r.metrics.forEach { (key,value) -> append(" $key=$value") }
                    appendLine()
                }
            }
            appendLine("\nOmitted values were not collected. These diagnostics do not certify audible quality or external EQ processing.")
        }
    }
}
