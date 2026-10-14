package com.music.vivi.playback.automix

import java.util.concurrent.Future
import kotlin.math.abs

data class MixTrack(val id: String, val durationMs: Long, val sourceIdentity: String, val sourceUri: String = id,
                    val cacheable: Boolean = true)
data class MixSnapshot(val outgoing: MixTrack, val incoming: MixTrack, val queueIdentity: String,
                       val positionMs: Long, val playing: Boolean, val normalSpeed: Boolean)

/** Minimal deck boundary: fake PCM decks exercise the same state machine in JVM tests. */
interface MixDeck {
    val trackId: String
    val positionMs: Long
    val ready: Boolean
    val playing: Boolean
    val ended: Boolean
    var rate: Float
    fun play()
    fun pause()
    fun process(gain: Double, bassCut: Double = 0.0, trebleCut: Double = 0.0)
    fun release()
}

interface AutoMixHost {
    val allowed: Boolean
    fun snapshot(): MixSnapshot?
    fun analyze(track: MixTrack, startMs: Long, lengthMs: Long, deadlineNanos: Long): TrackFeatures
    fun currentDeck(): MixDeck
    fun prepareIncoming(snapshot: MixSnapshot, plan: AutoMixPlan): MixDeck?
    /** Installs the incoming deck as the actual service/session player, without restarting it. */
    fun promote(incoming: MixDeck)
    fun restoreNormalAudio()
}

/** All deck/queue operations run on the service's main looper; only analysis runs in the pool. */
class AutoMixController(private val host: AutoMixHost, private val workers: AnalysisWorkers<TrackFeatures>,
                        private val planner: AutoMixPlanner = AutoMixPlanner()) : AutoCloseable {
    private data class Preparation(val snapshot: MixSnapshot, val outgoing: List<Future<TrackFeatures>>,
                                   val incoming: List<Future<TrackFeatures>>, var plan: AutoMixPlan? = null,
                                   var deck: MixDeck? = null)
    private data class Blend(val outgoing: MixDeck, val incoming: MixDeck, val plan: AutoMixPlan,
                             var incomingQueue: String?, val incomingId: String,
                             var startedPosition: Long, var finished: Boolean = false,
                             var recoveryStart: Long = 0, var ownedRate: Float = plan.incomingRate)
    private var preparation: Preparation? = null
    private var blend: Blend? = null
    private var finishedPair: String? = null
    var enabled = false
        private set
    val mixing get() = blend != null

    fun syncPlaybackState() {
        blend?.takeIf { !it.finished }?.let { if (it.incoming.playing) it.outgoing.play() else it.outgoing.pause() }
    }

    fun trackChanged(id: String?) {
        if ((preparation != null && preparation!!.snapshot.outgoing.id != id) ||
            (blend != null && blend!!.incomingId != id)) invalidate()
    }

    fun playlistChanged(currentTrackId: String?) {
        val state = blend
        if (state == null || state.incomingId != currentTrackId) invalidate()
        else {
            // Appending/reordering FUTURE songs does not invalidate two already audible decks.
            // Preserve the blend when the active song is unchanged; seeks/skips still cancel it.
            state.incomingQueue = host.snapshot()?.queueIdentity
        }
    }

    fun configure(enabled: Boolean, mode: AutoMixPerformance) {
        if (this.enabled != enabled || (workers.mode != mode && blend == null)) invalidate()
        workers.configure(mode)
        this.enabled = enabled
        if (!enabled) workers.cancelAll()
    }

    fun nextTickDelayMs(): Long {
        if (blend != null) return 25
        val snapshot = host.snapshot() ?: return 1000
        if (!snapshot.playing) return 1000
        val target = preparation?.plan?.outgoingStartMs ?: snapshot.outgoing.durationMs - 14_000
        return when {
            target - snapshot.positionMs > 12_000 -> 500
            target - snapshot.positionMs > 500 -> 100
            else -> 25
        }
    }

    /** Explicit seek/skip/queue replacement/settings change cancels every obsolete operation. */
    fun invalidate() {
        preparation?.let { cancel(it); it.deck?.release() }
        preparation = null
        blend?.let {
            it.outgoing.release()
            if (abs(it.incoming.rate - it.ownedRate) < 0.002f) it.incoming.rate = 1f
            it.incoming.process(1.0)
        }
        blend = null
        finishedPair = null
        workers.cancelAll()
        host.restoreNormalAudio()
    }

    fun tick() {
        if (!enabled || !host.allowed) {
            if (preparation != null || blend != null) invalidate()
            return
        }
        blend?.let { driveBlend(it); return }
        val snapshot = host.snapshot()
        if (snapshot == null || !snapshot.playing || !snapshot.normalSpeed) {
            if (preparation != null) invalidate()
            return
        }
        if (snapshot.outgoing.durationMs < 20_000 || snapshot.incoming.durationMs in 1..19_999) return
        val pair = identity(snapshot)
        if (pair == finishedPair) return
        var prep = preparation
        if (prep != null && identity(prep.snapshot) != pair) { invalidate(); prep = null }
        if (prep == null) {
            val deadline = System.nanoTime() + 25_000_000_000L
            prep = Preparation(snapshot,
                requestWindows(snapshot.outgoing, true, deadline), requestWindows(snapshot.incoming, false, deadline))
            preparation = prep
        }
        if (prep.plan == null && ((prep.outgoing.all { it.isDone } && prep.incoming.all { it.isDone }) ||
                    snapshot.positionMs >= snapshot.outgoing.durationMs - 14_000)) {
            val out = completed(prep.outgoing, true)
            val incoming = completed(prep.incoming, false)
            prep.plan = planner.plan(out, incoming, snapshot.outgoing.durationMs,
                incoming?.durationMs ?: snapshot.incoming.durationMs)
            if (prep.plan != null) cancel(prep)
            if (prep.plan == null) {
                cancel(prep)
                preparation = null; finishedPair = pair
                return
            }
        }
        val plan = prep.plan ?: return
        if (snapshot.positionMs > plan.outgoingStartMs + 150) {
            prep.deck?.release(); cancel(prep)
            preparation = null; finishedPair = pair // missed deadline: ordinary playback, not a late surprise mix
            return
        }
        if (prep.deck == null && snapshot.positionMs >= plan.outgoingStartMs - 10_000) {
            prep.deck = host.prepareIncoming(snapshot, plan)
            if (prep.deck == null) { preparation = null; finishedPair = pair; workers.cancelAll(); return }
        }
        val incoming = prep.deck ?: return
        if (!incoming.ready || snapshot.positionMs < plan.outgoingStartMs) return
        val outgoing = host.currentDeck()
        incoming.rate = plan.incomingRate
        incoming.process(0.0, if (plan.technique == MixTechnique.BASS_HANDOFF) 0.85 else 0.0)
        outgoing.process(0.94 * plan.outgoingLevel)
        incoming.play()
        val state = Blend(outgoing, incoming, plan, null, snapshot.incoming.id, outgoing.positionMs)
        blend = state // publish state first: session callbacks cannot arm a second transition
        preparation = null
        cancel(prep)
        try {
            host.promote(incoming)
            state.incomingQueue = host.snapshot()?.queueIdentity
        } catch (_: Exception) {
            if (host.snapshot()?.outgoing?.id == snapshot.outgoing.id) {
                // A failed session promotion must not release the still-current audible deck.
                blend = null
                incoming.release()
                outgoing.process(1.0)
                finishedPair = pair
                host.restoreNormalAudio()
            } else invalidate()
        }
    }

    private fun driveBlend(state: Blend) {
        val active = host.snapshot()
        // Queue edits, natural unexpected advancement, tempo changes and decoder failures invalidate.
        if (state.incoming.trackId != state.incomingId ||
            (active != null && (active.outgoing.id != state.incomingId ||
                (state.incomingQueue != null && active.queueIdentity != state.incomingQueue))) ||
            abs(state.incoming.rate - state.ownedRate) > 0.002f) { invalidate(); return }
        if (state.incoming.ended || !state.incoming.ready) { invalidate(); return }
        if (!state.incoming.playing) { state.outgoing.pause(); return }
        if (!state.finished && !state.outgoing.ended && !state.outgoing.playing) state.outgoing.play()
        if (state.finished) {
            val elapsed = (state.incoming.positionMs - state.recoveryStart).coerceAtLeast(0)
            val fraction = (elapsed / 3000.0).coerceIn(0.0, 1.0)
            state.ownedRate = (state.plan.incomingRate + (1 - state.plan.incomingRate) * fraction).toFloat()
            state.incoming.rate = state.ownedRate
            state.incoming.process(0.94 * state.plan.incomingLevel + (1 - 0.94 * state.plan.incomingLevel) * fraction)
            if (fraction >= 1) { blend = null; host.restoreNormalAudio() }
            return
        }
        val elapsed = (state.outgoing.positionMs - state.startedPosition).coerceAtLeast(0)
        val progress = (elapsed / state.plan.durationMs.toDouble()).coerceIn(0.0, 1.0)
        val gains = mixGains(progress, state.plan.outgoingLevel, state.plan.incomingLevel)
        val bass = state.plan.technique == MixTechnique.BASS_HANDOFF
        val filter = state.plan.technique == MixTechnique.GENTLE_FILTER
        state.outgoing.process(gains.outgoing, if (bass) progress * 0.85 else 0.0, if (filter) progress * 0.65 else 0.0)
        state.incoming.process(gains.incoming, if (bass) (1 - progress) * 0.85 else 0.0)
        if (state.plan.technique in setOf(MixTechnique.BEAT_BLEND, MixTechnique.BASS_HANDOFF) && elapsed > 300) {
            val expected = state.plan.incomingCueMs.toDouble() + elapsed.toDouble() * state.plan.incomingRate
            val phaseErrorMs = expected - state.incoming.positionMs
            val correction = (phaseErrorMs / 5000).coerceIn(-0.01, 0.01)
            state.ownedRate = (state.plan.incomingRate + correction).toFloat().coerceIn(0.95f, 1.05f)
            state.incoming.rate = state.ownedRate
        }
        if (progress >= 1 || state.outgoing.ended) {
            state.outgoing.release()
            state.finished = true
            state.recoveryStart = state.incoming.positionMs
            state.incoming.process(0.94 * state.plan.incomingLevel)
        }
    }

    private fun requestWindows(track: MixTrack, tail: Boolean, deadline: Long): List<Future<TrackFeatures>> {
        val length = if (track.durationMs > 0) minOf(32_000L, track.durationMs) else 32_000L
        val start = if (tail) (track.durationMs - length).coerceAtLeast(0) else 0L
        // Four disjoint 16s jobs for an ordinary pair: Performance genuinely permits four
        // simultaneous decoder/DSP tasks; all modes analyze the identical combined windows.
        val windows = if (length == 32_000L) listOf(start to 16_000L, start + 16_000 to 16_000L)
            else listOf(start to length)
        return windows.map { (position, size) -> workers.submit("${track.sourceIdentity}|$position:$size", track.cacheable) {
            host.analyze(track, position, size, deadline)
        } }
    }

    private fun cancel(prep: Preparation) { (prep.outgoing + prep.incoming).forEach { it.cancel(true) } }
    private fun completed(futures: List<Future<TrackFeatures>>, preferTail: Boolean): TrackFeatures? {
        val values = futures.map { if (it.isDone && !it.isCancelled) runCatching { it.get() }.getOrNull() else null }
        if (values.any { it == null }) return null
        val parts = values.filterNotNull()
        val preferred = if (preferTail) parts.last() else parts.first()
        if (parts.any { abs(it.durationMs - preferred.durationMs) > 1000 }) return null
        val consistent = parts.all { abs(it.bpm - preferred.bpm) <= 3 }
        val total = parts.sumOf { it.lengthMs }.coerceAtLeast(1)
        return preferred.copy(startMs = parts.first().startMs, lengthMs = total,
            rms = kotlin.math.sqrt(parts.sumOf { it.rms * it.rms * it.lengthMs } / total),
            peak = parts.maxOf { it.peak },
            beatConfidence = if (consistent) parts.minOf { it.beatConfidence } else 0.0,
            beatsMs = parts.flatMap { it.beatsMs }.distinct().sorted(),
            energy = parts.flatMap { it.energy }.sortedBy { it.positionMs })
    }
    private fun identity(s: MixSnapshot) = "${s.queueIdentity}|${s.outgoing.sourceIdentity}|${s.incoming.sourceIdentity}"
    override fun close() { enabled = false; invalidate(); workers.close() }
}
