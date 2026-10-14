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
    fun scan(track: MixTrack, startMs: Long, lengthMs: Long, deadlineNanos: Long): TrackFeatures =
        analyze(track, startMs, lengthMs, deadlineNanos)
    fun diagnostic(message: String, verbose: Boolean = false) {}
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
    private data class Decision(val outgoing: TrackFeatures?, val incoming: TrackFeatures?, val selection: AutoMixPlanner.Decision)
    private data class Preparation(val snapshot: MixSnapshot, val outgoing: List<Future<TrackFeatures>>,
                                   val incoming: List<Future<TrackFeatures>>, var plan: AutoMixPlan? = null,
                                   var deck: MixDeck? = null, val deadline: Long,
                                   var coarseOut: TrackFeatures? = null, var coarseIn: TrackFeatures? = null,
                                   var preview: AutoMixPlan? = null,
                                   var refinement: Pair<Future<TrackFeatures>,Future<TrackFeatures>>? = null,
                                   var announcedPlan: AutoMixPlan? = null, var readyLogged: Boolean = false,
                                   var previewDecision: Future<Decision>? = null, var previewProcessed: Boolean = false,
                                   var finalDecision: Future<Decision>? = null)
    private data class Blend(val outgoing: MixDeck, val incoming: MixDeck, val plan: AutoMixPlan,
                             var incomingQueue: String?, val incomingId: String,
                             var startedPosition: Long, var finished: Boolean = false,
                             var recoveryStart: Long = 0, var ownedRate: Float = plan.incomingRate,
                             var lastRateUpdate: Long = 0, var recoveryRate: Float = plan.incomingRate)
    private var preparation: Preparation? = null
    private var blend: Blend? = null
    private var finishedPair: String? = null
    var enabled = false
        private set
    val mixing get() = blend != null
    internal val plannedTransition get() = preparation?.plan

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
            val deadline = System.nanoTime() + 40_000_000_000L
            prep = Preparation(snapshot,
                requestWindows(snapshot.outgoing, true, deadline), requestWindows(snapshot.incoming, false, deadline), deadline=deadline)
            preparation = prep
        }
        if (!prep.previewProcessed && prep.previewDecision == null && ((prep.outgoing.all { it.isDone } && prep.incoming.all { it.isDone }) ||
                    snapshot.positionMs >= snapshot.outgoing.durationMs - 14_000)) {
            workers.cancelAll()
            val preparationSnapshot=prep.snapshot
            val outJobs=prep.outgoing; val inJobs=prep.incoming
            prep.previewDecision=workers.submitTransient {
                val out=completed(outJobs,true); val incoming=completed(inJobs,false)
                Decision(out,incoming,planner.decide(out,incoming,preparationSnapshot.outgoing.durationMs,
                    incoming?.durationMs ?: preparationSnapshot.incoming.durationMs,preparationSnapshot.positionMs+20_000))
            }
        }
        prep.previewDecision?.takeIf { it.isDone && !prep.previewProcessed }?.let { future ->
            prep.previewProcessed=true
            val result=runCatching { future.get() }.getOrNull()
            prep.coarseOut=result?.outgoing; prep.coarseIn=result?.incoming
            val preview=result?.selection?.plan
            prep.preview=preview
            host.diagnostic("event=scan-complete outgoing=${prep.coarseOut!=null} incoming=${prep.coarseIn!=null} rejected=${result?.selection?.rejections}",true)
            if(preview==null) { cancel(prep); preparation=null; finishedPair=pair; return }
            if(preview.technique!=MixTechnique.SAFE_FADE && prep.coarseOut!=null && prep.coarseIn!=null && snapshot.positionMs<preview.outgoingStartMs-1500) {
                fun refine(track: MixTrack,cue: Long): Future<TrackFeatures> {
                    val start=(cue-3000).coerceAtLeast(0)
                    val duration=if(track.durationMs>0) track.durationMs else prep.coarseIn!!.durationMs
                    val length=minOf(32_000L,duration-start)
                    return workers.submit("v3-detail:${track.sourceIdentity}|$start:$length",track.cacheable) {
                        host.analyze(track,start,length,prep.deadline)
                    }
                }
                prep.refinement=refine(snapshot.outgoing,preview.outgoingStartMs) to refine(snapshot.incoming,preview.incomingCueMs)
            } else prep.plan=preview
        }
        prep.refinement?.let { refinement ->
            if(prep.plan==null && prep.finalDecision==null && ((refinement.first.isDone && refinement.second.isDone) ||
                    snapshot.positionMs >= (prep.preview?.outgoingStartMs ?: Long.MAX_VALUE)-5000)) {
                val coarseOut=prep.coarseOut; val coarseIn=prep.coarseIn
                val earliest=snapshot.positionMs+1500
                val outDuration=snapshot.outgoing.durationMs; val inDuration=snapshot.incoming.durationMs
                workers.cancelAll()
                prep.finalDecision=workers.submitTransient {
                    val out=completed(listOf(refinement.first),true)
                    val incoming=completed(listOf(refinement.second),false)
                    val mergedOut=mergeTrackFeatures(listOfNotNull(coarseOut,out),true)
                    val mergedIn=mergeTrackFeatures(listOfNotNull(coarseIn,incoming),false)
                    val decision=if(out!=null && incoming!=null) planner.decide(mergedOut,mergedIn,outDuration,incoming.durationMs,
                        earliest,requireDetailed=true) else AutoMixPlanner.Decision(
                            planner.fallback(outDuration,coarseIn?.durationMs ?: inDuration)?.copy(reason="refinement-incomplete-or-deadline"),emptyMap())
                    Decision(out,incoming,decision)
                }
            }
        }
        prep.finalDecision?.takeIf { it.isDone && prep.plan==null }?.let { future ->
            val result=runCatching { future.get() }.getOrNull()
            prep.plan=result?.selection?.plan
            host.diagnostic("event=refine-complete outgoing=${result?.outgoing!=null} incoming=${result?.incoming!=null} rejected=${result?.selection?.rejections}",true)
            if(prep.plan==null) { cancel(prep); preparation=null; finishedPair=pair; return }
        }
        val plan = prep.plan ?: return
        if (prep.announcedPlan != plan) {
            prep.announcedPlan=plan
            host.diagnostic("event=plan technique=${plan.technique} startMs=${plan.outgoingStartMs} cueMs=${plan.incomingCueMs} durationMs=${plan.durationMs} score=${plan.score} phase=${plan.phaseConfidence} reason=${plan.reason}")
        }
        if (snapshot.positionMs > plan.outgoingStartMs + 150) {
            prep.deck?.release(); cancel(prep)
            host.diagnostic("event=start-missed technique=${plan.technique} latenessMs=${snapshot.positionMs-plan.outgoingStartMs}")
            val fallback = planner.fallback(snapshot.outgoing.durationMs,prep.coarseIn?.durationMs ?: snapshot.incoming.durationMs)
            if(plan.technique != MixTechnique.SAFE_FADE && fallback != null && fallback.outgoingStartMs>snapshot.positionMs+1000) {
                prep.deck=null; prep.readyLogged=false; prep.plan=fallback.copy(reason="missed-intelligent-boundary")
            } else { preparation=null; finishedPair=pair }
            return
        }
        if (prep.deck == null && snapshot.positionMs >= plan.outgoingStartMs - 20_000) {
            prep.deck = host.prepareIncoming(snapshot, plan)
            host.diagnostic("event=prepare success=${prep.deck!=null} leadMs=${plan.outgoingStartMs-snapshot.positionMs}")
            if (prep.deck == null) { preparation = null; finishedPair = pair; workers.cancelAll(); return }
        }
        val incoming = prep.deck ?: return
        if (!incoming.ready || abs(incoming.positionMs-plan.incomingCueMs)>100) return
        if(!prep.readyLogged) {
            prep.readyLogged=true
            host.diagnostic("event=ready actualCueMs=${incoming.positionMs} requestedCueMs=${plan.incomingCueMs} leadMs=${plan.outgoingStartMs-snapshot.positionMs}")
        }
        if (snapshot.positionMs < plan.outgoingStartMs) return
        val outgoing = host.currentDeck()
        incoming.rate = plan.incomingRate
        incoming.process(0.0, if (plan.technique == MixTechnique.BASS_HANDOFF) 0.85 else 0.0)
        outgoing.process(0.94) // match outgoing level gradually, never a large instantaneous attenuation
        incoming.play()
        val state = Blend(outgoing, incoming, plan, null, snapshot.incoming.id, outgoing.positionMs)
        blend = state // publish state first: session callbacks cannot arm a second transition
        preparation = null
        cancel(prep)
        try {
            host.promote(incoming)
            host.diagnostic("event=start technique=${plan.technique} startMs=${outgoing.positionMs} cueMs=${incoming.positionMs} durationMs=${plan.durationMs} reason=${plan.reason}")
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
            state.ownedRate = (state.recoveryRate + (1 - state.recoveryRate) * fraction).toFloat()
            if (elapsed-state.lastRateUpdate>=150 || fraction>=1) {
                state.incoming.rate = state.ownedRate
                state.lastRateUpdate=elapsed
            } else state.ownedRate=state.incoming.rate
            state.incoming.process(0.94 * state.plan.incomingLevel + (1 - 0.94 * state.plan.incomingLevel) * fraction)
            if (fraction >= 1) { blend = null; host.restoreNormalAudio() }
            return
        }
        val elapsed = (state.outgoing.positionMs - state.startedPosition).coerceAtLeast(0)
        val progress = (elapsed / state.plan.durationMs.toDouble()).coerceIn(0.0, 1.0)
        val levelRamp=(elapsed/2000.0).coerceIn(0.0,1.0)
        val gains = mixGains(progress, 1+(state.plan.outgoingLevel-1)*levelRamp, state.plan.incomingLevel)
        val bass = state.plan.technique == MixTechnique.BASS_HANDOFF
        val filter = state.plan.technique == MixTechnique.GENTLE_FILTER
        fun smooth(value: Double): Double { val x=value.coerceIn(0.0,1.0); return x*x*(3-2*x) }
        // Open incoming bass before removing outgoing bass: avoid the former simultaneous
        // mid-blend bass cuts. Correlation headroom still bounds their combined peak gain.
        state.outgoing.process(gains.outgoing, if (bass) smooth((progress-0.55)/0.30)*0.85 else 0.0,
            if (filter) smooth(progress)*0.65 else 0.0)
        state.incoming.process(gains.incoming, if (bass) (1-smooth((progress-0.30)/0.25))*0.85 else 0.0)
        if (state.plan.technique in setOf(MixTechnique.BEAT_BLEND, MixTechnique.BASS_HANDOFF) && elapsed-state.lastRateUpdate>=500) {
            val expected = state.plan.incomingCueMs.toDouble() + elapsed.toDouble() * state.plan.incomingRate
            val phaseErrorMs = expected - state.incoming.positionMs
            val correction = if(kotlin.math.abs(phaseErrorMs)<35) 0.0 else (phaseErrorMs / 8000).coerceIn(-0.01, 0.01)
            val target=(state.plan.incomingRate+correction).toFloat()
            state.ownedRate = target.coerceIn(state.ownedRate-0.002f,state.ownedRate+0.002f).coerceIn(0.95f,1.05f)
            state.incoming.rate = state.ownedRate
            state.lastRateUpdate=elapsed
        }
        if (progress >= 1 || state.outgoing.ended) {
            state.outgoing.release()
            host.diagnostic("event=handoff-complete durationMs=$elapsed")
            state.recoveryRate=state.ownedRate
            state.lastRateUpdate=0
            state.finished = true
            state.recoveryStart = state.incoming.positionMs
            state.incoming.process(0.94 * state.plan.incomingLevel)
        }
    }

    private fun requestWindows(track: MixTrack, tail: Boolean, deadline: Long): List<Future<TrackFeatures>> {
        val length = if(tail) when {
            track.durationMs>=180_000 -> 90_000L
            track.durationMs>=90_000 -> 60_000L
            else -> minOf(32_000L,track.durationMs)
        } else if(track.durationMs>0) minOf(32_000L,track.durationMs) else 32_000L
        val start = if(tail) (track.durationMs-length).coerceAtLeast(0) else 0L
        val chunk = if(tail && length>32_000) 30_000L else 16_000L
        val windows=generateSequence(start) { it+chunk }.takeWhile { it<start+length }
            .map { it to minOf(chunk,start+length-it) }.toList()
        return windows.map { (position,size) -> workers.submit("v3-scan:${track.sourceIdentity}|$position:$size",track.cacheable) {
            host.scan(track,position,size,deadline)
        } }
    }
    private fun cancel(prep: Preparation) { (prep.outgoing+prep.incoming+listOfNotNull(prep.refinement?.first,prep.refinement?.second,prep.previewDecision,prep.finalDecision)).forEach { it.cancel(true) } }
    private fun completed(futures: List<Future<TrackFeatures>>, preferTail: Boolean): TrackFeatures? =
        mergeTrackFeatures(futures.mapNotNull { if(it.isDone && !it.isCancelled) runCatching { it.get() }.getOrNull() else null },preferTail)

    private fun identity(s: MixSnapshot) = "${s.queueIdentity}|${s.outgoing.sourceIdentity}|${s.incoming.sourceIdentity}"
    override fun close() { enabled = false; invalidate(); workers.close() }
}
