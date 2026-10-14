package com.music.vivi.playback.automix

import kotlin.math.*

/** Independent paired-section evaluator. Active music needs measured rhythm/spectrum,
 * not an RMS cliff. Bar accents are evidence of bars, never a claim of semantic phrases. */
internal object DjCandidateSelector {
    data class Result(val plans: List<AutoMixPlan>, val rejections: Map<String,Int>)
    fun select(out: TrackFeatures, incoming: TrackFeatures, duration: Long, incomingDuration: Long,
               earliest: Long, detailed: Boolean, harmonic: Boolean, allowFull: Boolean): Result {
        val rejected=mutableMapOf<String,Int>()
        fun reject(code: String) { rejected[code]=(rejected[code] ?: 0)+1 }
        fun points(t: TrackFeatures, start: Long, end: Long)=t.energy.filter { it.positionMs in start..end }
        fun mean(p: List<EnergyPoint>, get: (EnergyPoint)->Double)=p.map(get).average()
        fun rhythm(t: TrackFeatures,time: Long)=t.sections.filter { time in it.startMs until it.endMs }
            .maxByOrNull { (if(it.detailed) 2.0 else 0.0)+it.confidence }
        fun covered(t: TrackFeatures,start: Long,end: Long,requireDetail: Boolean): Boolean {
            var edge=start
            for(s in t.sections.filter { !requireDetail || it.detailed }.sortedBy { it.startMs }) {
                if(s.endMs<=edge) continue
                if(s.startMs>edge+250) return false
                edge=max(edge,s.endMs)
                if(edge>=end-250) return true
            }
            return false
        }
        if(!harmonic) return Result(emptyList(),mapOf("INCOMPATIBLE_KEY" to 1))
        if(out.energy.none { it.percussion!=null } || incoming.energy.none { it.percussion!=null }) return Result(emptyList(),emptyMap())
        val first=max(earliest,max((duration*0.67).toLong(),duration-72_000))
        val outBars=out.sections.filter { it.downbeatConfidence>=0.4 }.flatMap { it.downbeatsMs }.distinct().sorted()
        val outBeats=out.beatsMs.distinct().sorted()
        val inBars=incoming.sections.filter { it.downbeatConfidence>=0.4 }.flatMap { it.downbeatsMs }.distinct().sorted()
        val introLimit=min(32_000L,incomingDuration/5)
        val firstSound=incoming.energy.firstOrNull { it.rms>=max(0.005,incoming.rms*0.12) }?.positionMs ?: 0
        val entries=(listOf(firstSound,0L)+incoming.boundaries.map { it.positionMs }+
            incoming.sections.map { it.startMs }+inBars.filter { it>=firstSound }).map { raw ->
            (inBars.ifEmpty { incoming.beatsMs }).minByOrNull { abs(it-raw) }?.takeIf { abs(it-raw)<=600 } ?: raw
        }.filter { it in 0..introLimit }.distinct().take(16)
        val exits=(outBars+outBeats.filterIndexed { index,_ -> index%8==0 }+out.boundaries.map { it.positionMs })
            .filter { it in first..duration-6000 }.distinct().sortedDescending().take(40)
        val plans=mutableListOf<AutoMixPlan>()
        for(start in exits) for(cue in entries) {
            if(incoming.beatsMs.none { abs(it-cue)<=150 }) { reject("LOW_BEAT_CONFIDENCE");continue }
            val a=rhythm(out,start+500);val b=rhythm(incoming,cue+1000)
            if(a==null || b==null || min(a.confidence,b.confidence)<0.65 || min(a.phaseConfidence,b.phaseConfidence)<0.5 ||
                min(a.stability,b.stability)<0.8 || a.bpm !in 60.0..182.0 || b.bpm !in 60.0..182.0) { reject("LOW_BEAT_CONFIDENCE");continue }
            val rate=a.bpm/b.bpm
            if(rate !in 0.96..1.04) { reject("INCOMPATIBLE_TEMPO");continue }
            val before=points(incoming,0,cue-250)
            val entry=points(incoming,cue,cue+3000)
            if(entry.isEmpty()) continue
            val entryRms=mean(entry) { it.rms }
            val important=before.any { it.vocalRisk>0.6 } || mean(before) { it.rms }.let { it.isFinite() && it>max(0.005,entryRms*0.45) }
            if(cue>2000 && (!covered(incoming,0,cue,false) || important)) { reject("IMPORTANT_INTRO");continue }
            for(beats in listOf(64,32,16,8)) {
                val overlap=(beats*60_000/a.bpm).roundToLong()
                if(overlap !in 6000..48_000 || start+overlap+500>duration || cue+overlap*rate+500>incomingDuration) continue
                if(!covered(out,start,start+overlap,detailed) || !covered(incoming,cue,cue+(overlap*rate).toLong(),detailed)) { reject("REFINEMENT_FAILED");continue }
                val x=points(out,start,start+overlap);val y=points(incoming,cue,cue+(overlap*rate).toLong())
                val remainder=points(out,start,duration)
                if(x.size<8 || y.size<8 || remainder.lastOrNull()?.positionMs?.let { it>=duration-1500 }!=true) continue
                // Check the remainder too: do not cut a later lead vocal or a rising buildup.
                if(remainder.count { it.vocalRisk>0.65 }.toDouble()/remainder.size>0.15 || mean(x) { it.vocalRisk }>0.5 ||
                    mean(y) { it.vocalRisk }>0.5) { reject("VOCAL_COLLISION");continue }
                val rmsA=mean(x) { it.rms };val rmsB=mean(y) { it.rms }
                if(min(rmsA,rmsB)<0.02) continue // this path describes audibly active material
                val variation=sqrt(x.sumOf { (it.rms-rmsA).pow(2) }/x.size)/rmsA
                val ending=points(out,duration-8000,duration)
                if(variation>0.4 || mean(ending) { it.rms }>rmsA*1.3) { reject("UNSTABLE_ENERGY");continue }
                val localSections=out.sections.filter { it.endMs>start && it.startMs<start+overlap }+
                    incoming.sections.filter { it.endMs>cue && it.startMs<cue+overlap*rate }
                if(localSections.filter { it.confidence>=0.6 }.any { abs(it.bpm-a.bpm)/a.bpm>0.05 }) { reject("INCOMPATIBLE_TEMPO");continue }
                val percussion=min(mean(x) { it.percussion ?: 0.0 },mean(y) { it.percussion ?: 0.0 })
                val density=mean(x) { it.spectralDensity ?: 1.0 }+mean(y) { it.spectralDensity ?: 1.0 }
                if(percussion<0.2 || density>1.5) { reject("SPECTRAL_COLLISION");continue }
                val bars=min(a.downbeatConfidence,b.downbeatConfidence)
                val full=allowFull && bars>=0.4 && min(a.confidence,b.confidence)>=0.7 && min(a.phaseConfidence,b.phaseConfidence)>=0.65 &&
                    min(a.stability,b.stability)>=0.9 && percussion>=0.3 && start in outBars && cue in inBars
                if(beats==64 && !full) continue
                if(!allowFull && overlap>16000) continue
                val bass=min(mean(x) { it.bassFraction ?: 0.0 },mean(y) { it.bassFraction ?: 0.0 })
                val fit=min(rmsA,rmsB)/max(rmsA,rmsB)
                val discard=(duration-start-overlap).toDouble()/duration
                val score=(if(full)0.58 else 0.45)+min(a.confidence,b.confidence)*0.15+fit*0.1+
                    bars*0.06+percussion*0.06-min(0.15,discard*2)-cue.toDouble()/max(1,incomingDuration)*0.12+
                    (if(beats==64 && full)0.04 else if(beats==32 && full)0.02 else 0.0)
                val levelA=sqrt(min(1.0,rmsB/rmsA)).coerceAtLeast(0.65)
                val levelB=sqrt(min(1.0,rmsA/rmsB)).coerceAtLeast(0.65)
                plans+=AutoMixPlan(start,cue,overlap,rate.toFloat(),if(bass>0.25) MixTechnique.BASS_HANDOFF else MixTechnique.BEAT_BLEND,
                    levelA,levelB,score,"active-rhythmic-section",min(a.phaseConfidence,b.phaseConfidence),
                    if(full)AutoMixTier.A else AutoMixTier.B,beats,
                    if(cue==0L)CueReason.TRACK_START else if(before.all { it.rms<0.005 })CueReason.LEADING_SILENCE else CueReason.RHYTHMIC_ENTRY,
                    bass,density)
            }
        }
        return Result(plans.sortedByDescending { it.score }.take(64),rejected)
    }
}
