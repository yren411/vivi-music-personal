package com.music.vivi.playback.automix

import kotlin.math.*

/** Evidence-scored pairs. An early cut needs a measured, sustained outro, not just matching BPM. */
class AutoMixPlanner {
    var lastRejections: Map<String, Int> = emptyMap()
        private set
    fun fallback(outgoingDurationMs: Long, incomingDurationMs: Long): AutoMixPlan? {
        if (outgoingDurationMs < 15_000 || incomingDurationMs < 15_000) return null
        return AutoMixPlan(outgoingDurationMs - 2500, 0, 2500)
    }
    data class Decision(val plan: AutoMixPlan?, val rejections: Map<String,Int>, val candidates: List<AutoMixPlan> = emptyList())
    fun decide(outgoing: TrackFeatures?, incoming: TrackFeatures?, outgoingDurationMs: Long,
               incomingDurationMs: Long, earliestStartMs: Long = 0, requireDetailed: Boolean = false, allowFull: Boolean = true): Decision {
        val rejected=mutableMapOf<String,Int>()
        val result=compute(outgoing,incoming,outgoingDurationMs,incomingDurationMs,earliestStartMs,requireDetailed,rejected)
        val active=if(outgoing!=null && incoming!=null && abs(outgoing.durationMs-outgoingDurationMs)<=1000)
            DjCandidateSelector.select(outgoing,incoming,outgoingDurationMs,incomingDurationMs,earliestStartMs,
                requireDetailed,compatibleKeys(outgoing.key,incoming.key),allowFull)
            else DjCandidateSelector.Result(emptyList(),emptyMap())
        active.rejections.forEach { (key,value) -> rejected[key]=(rejected[key] ?: 0)+value }
        return Decision((active.plans+listOfNotNull(result)).maxByOrNull { it.score },rejected.toMap(),active.plans)
    }
    fun plan(outgoing: TrackFeatures?, incoming: TrackFeatures?, outgoingDurationMs: Long,
             incomingDurationMs: Long, earliestStartMs: Long = 0, requireDetailed: Boolean = false): AutoMixPlan? {
        val result=decide(outgoing,incoming,outgoingDurationMs,incomingDurationMs,earliestStartMs,requireDetailed)
        lastRejections=result.rejections
        return result.plan
    }
    private data class Exit(val time: Long, val evidence: Double, val early: Boolean)
    private data class Rhythm(val bpm: Double, val confidence: Double, val phase: Double, val stability: Double)
    private fun rhythm(track: TrackFeatures, time: Long): Rhythm {
        val local=track.sections.filter { time in it.startMs until it.endMs }
            .maxByOrNull { (if(it.detailed) 2.0 else 0.0)+it.confidence }
        if(local==null && track.sections.isNotEmpty()) return Rhythm(0.0,0.0,0.0,0.0)
        return if(local==null) Rhythm(track.bpm,track.beatConfidence,track.phaseConfidence,track.rhythmStability)
        else Rhythm(local.bpm,local.confidence,local.phaseConfidence,local.stability)
    }
    private fun region(track: TrackFeatures, start: Long, end: Long) = track.energy.filter { it.positionMs in start..end }
    private fun voice(points: List<EnergyPoint>) = points.map { it.vocalRisk }.average().finite()
    private fun rms(points: List<EnergyPoint>, fallback: Double) = points.map { it.rms }.average().takeIf { it.isFinite() } ?: fallback
    private fun snap(track: TrackFeatures, time: Long): Long {
        val measuredBars=track.sections.filter { it.downbeatConfidence>=0.4 }.flatMap { it.downbeatsMs }
        return measuredBars.minByOrNull { abs(it-time) }?.takeIf { abs(it-time)<=500 }
            ?: track.beatsMs.minByOrNull { abs(it-time) }?.takeIf { abs(it-time)<=250 } ?: time
    }

    private fun compute(outgoing: TrackFeatures?, incoming: TrackFeatures?, outgoingDurationMs: Long,
             incomingDurationMs: Long, earliestStartMs: Long, requireDetailed: Boolean, rejects: MutableMap<String,Int>): AutoMixPlan? {
        fun reject(reason: String) { rejects[reason]=(rejects[reason] ?: 0)+1 }
        if (outgoing==null || incoming==null) return fallback(outgoingDurationMs,incomingDurationMs)
        if (abs(outgoing.durationMs-outgoingDurationMs)>1000) {
            reject("source-duration-mismatch")
            return null // never schedule a timestamp from a different audio timeline
        }
        if(outgoingDurationMs<20_000 || incomingDurationMs<20_000 || outgoing.peak<0.001 || incoming.peak<0.001) return null
        val outRhythm=rhythm(outgoing,outgoingDurationMs-5000)
        val nominal=if(outRhythm.confidence>=0.6 && outRhythm.bpm in 60.0..182.0)
            (8*60_000/outRhythm.bpm).roundToLong().coerceIn(4000,10000) else 3500L
        val exits=mutableListOf(Exit(outgoingDurationMs-nominal,0.0,false))
        val firstAllowed=max((outgoingDurationMs*0.67).toLong(),outgoingDurationMs-60_000)
        for(boundary in outgoing.boundaries) {
            val time=snap(outgoing,boundary.positionMs)
            if(time !in max(firstAllowed,earliestStartMs)..outgoingDurationMs-14_000) continue
            val after=region(outgoing,time+1000,outgoingDurationMs)
            val head=region(outgoing,time+1000,time+9000)
            val tail=region(outgoing,outgoingDurationMs-8000,outgoingDurationMs)
            val energyDrop=boundary.afterRms<=boundary.beforeRms*0.8
            val vocalDrop=boundary.beforeVocal-boundary.afterVocal>=0.18
            val covered=after.lastOrNull()?.positionMs?.let { it>=outgoingDurationMs-1500 }==true
            val prominentReturn=after.any { it.vocalRisk>0.7 && it.rms>max(0.005,outgoing.rms*0.04) }
            val safeRemainder=covered && !prominentReturn && after.isNotEmpty() && voice(after)<0.4 &&
                after.count { it.vocalRisk>0.55 }.toDouble()/after.size<0.08 &&
                rms(tail,outgoing.rms)<=rms(head,outgoing.rms)*1.2 &&
                after.maxOf { it.rms }<=max(0.015,rms(head,outgoing.rms)*1.65)
            if(boundary.confidence<0.7 || !(energyDrop || vocalDrop) || !safeRemainder) { reject("unproven-outro-or-buildup"); continue }
            exits+=Exit(time,boundary.confidence,true)
        }
        val firstSound=incoming.energy.firstOrNull { it.rms>=max(0.005,incoming.rms*0.15) }?.positionMs ?: 0
        val cueLimit=min(32_000L,incomingDurationMs/5)
        val cues=(listOf(0L,snap(incoming,firstSound)) + incoming.boundaries.map { snap(incoming,it.positionMs) } +
            incoming.sections.filter { it.confidence>=0.6 }.map { snap(incoming,max(it.startMs,firstSound)) })
            .filter { it in 0..cueLimit }.distinct()
        val harmonic=compatibleKeys(outgoing.key,incoming.key)
        val plans=mutableListOf<AutoMixPlan>()
        for(exit in exits) for(cue in cues) {
            val skip=region(incoming,0,cue-100)
            val arrival=region(incoming,cue,cue+3000)
            val arrivalRms=rms(arrival,incoming.rms)
            if(cue>2000 && (skip.any { it.vocalRisk>0.5 } || rms(skip,0.0)>max(0.005,arrivalRms*0.35))) {
                reject("important-intro-would-be-skipped"); continue
            }
            if(cue<firstSound-500) { reject("silent-incoming-entry"); continue }
            val a=rhythm(outgoing,exit.time+500); val b=rhythm(incoming,cue+1000)
            val confident=min(a.confidence,b.confidence)>=0.6 && min(a.phase,b.phase)>=0.4 &&
                min(a.stability,b.stability)>=0.65 && a.bpm in 60.0..182.0 && b.bpm in 60.0..182.0
            val ratio=if(confident) a.bpm/b.bpm else 1.0
            val compatible=confident && ratio in 0.96..1.04
            val outVoice=voice(region(outgoing,exit.time,exit.time+10000))
            val inVoice=voice(region(incoming,cue,cue+10000))
            val clash=outVoice>0.55 && inVoice>0.55
            val rhythmic=compatible && harmonic && !clash
            if(exit.early && (!rhythmic || outVoice>0.45 || inVoice>0.45)) { reject("early-pair-lacks-rhythm-harmony-or-vocal-clearance"); continue }
            val instrumentalEnd=incoming.energy.firstOrNull { it.positionMs>=cue+1000 && it.vocalRisk>0.55 }?.positionMs
                ?: min(incomingDurationMs,incoming.startMs+incoming.lengthMs)
            val desired=when {
                rhythmic && exit.early -> ((if(exit.evidence>=0.85 && instrumentalEnd-cue>=18000) 32 else 16)*60_000/a.bpm).roundToLong().coerceIn(6000,24000)
                rhythmic -> (8*60_000/a.bpm).roundToLong().coerceIn(4000,10000)
                clash -> 1500L
                !harmonic -> 2000L
                else -> 3500L
            }
            val start=if(exit.early) exit.time else outgoingDurationMs-desired
            val length=if(exit.early) minOf(desired,outgoingDurationMs-start,instrumentalEnd-cue) else desired
            if(length<1500 || (exit.early && length<6000) || cue+(length*ratio).toLong()+500>=incomingDurationMs) {
                reject("insufficient-safe-overlap"); continue
            }
            fun covers(track: TrackFeatures, position: Long, length: Long) = track.sections.isEmpty() ||
                run {
                    var edge=position
                    for(section in track.sections.filter { it.detailed }.sortedBy { it.startMs }) {
                        if(section.endMs<=edge) continue
                        if(section.startMs>edge+250) break
                        edge=max(edge,section.endMs)
                    }
                    edge>=position+length-250
                }
            if(requireDetailed && (!covers(outgoing,start,length) || !covers(incoming,cue,(length*ratio).toLong()))) {
                reject("selected-region-not-refined"); continue
            }
            // Refuse a tempo-varying overlap even when one local window has a good grid.
            val tempos=(outgoing.sections.filter { it.endMs>start && it.startMs<start+length } +
                incoming.sections.filter { it.endMs>cue && it.startMs<cue+length }).filter { it.confidence>=0.6 }
            if(exit.early && tempos.any { abs(it.bpm-a.bpm)/a.bpm>0.06 }) { reject("tempo-varies-across-overlap"); continue }
            val localOut=rms(region(outgoing,start,start+length),outgoing.rms).coerceAtLeast(0.005)
            val localIn=rms(region(incoming,cue,cue+length),incoming.rms).coerceAtLeast(0.005)
            val energyFit=min(localOut,localIn)/max(localOut,localIn)
            val rhythmScore=if(rhythmic) min(a.confidence,b.confidence)*min(a.phase,b.phase) else 0.0
            val score=(if(exit.early) 0.3+exit.evidence*0.4 else 0.35)+rhythmScore*0.2+
                (1-max(outVoice,inVoice))*0.05+energyFit*0.05-cue/1000.0*0.01
            val technique=when {
                rhythmic && min(localOut,localIn)>0.025 -> MixTechnique.BASS_HANDOFF
                rhythmic -> MixTechnique.BEAT_BLEND
                !harmonic && !clash -> MixTechnique.GENTLE_FILTER
                else -> MixTechnique.ENERGY_FADE
            }
            val quiet=min(localOut,localIn)
            plans+=AutoMixPlan(start,cue,length,if(rhythmic) ratio.toFloat() else 1f,technique,
                quiet/localOut,quiet/localIn,score,
                if(exit.early) "measured-sustained-outro" else if(!confident) "LOW_BEAT_CONFIDENCE" else if(!compatible) "INCOMPATIBLE_TEMPO" else if(!harmonic) "INCOMPATIBLE_KEY" else if(clash) "VOCAL_COLLISION" else "end-window",
                min(a.phase,b.phase))
        }
        return plans.maxByOrNull { it.score } ?: fallback(outgoingDurationMs,incomingDurationMs)?.copy(reason=rejects.maxByOrNull { it.value }?.key ?: "no-compatible-candidate")
    }
    fun compatibleKeys(a: MusicalKey?, b: MusicalKey?): Boolean {
        if(a==null || b==null || min(a.confidence,b.confidence)<0.2) return true
        val distance=(b.tonic-a.tonic+12)%12
        return if(a.minor==b.minor) distance in setOf(0,5,7) else if(a.minor) distance==3 else distance==9
    }
    private fun Double.finite() = if(isFinite()) this else 0.0
}
