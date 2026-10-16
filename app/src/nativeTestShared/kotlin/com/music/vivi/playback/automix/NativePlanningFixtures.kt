// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlin.math.*

/** Exact synthetic observations for mathematical tests; NOT claimed to be real DSP measurements. */
internal object NativePlanningFixtures {
    fun evidence(bpm: Double=120.0, originUs: Long=150_000_000, id: String="out",
                 phaseUs: Long=137_000, durationUs: Long=180_000_000, lengthUs: Long=30_000_000): PlanningWindowEvidence {
        val span=AudioIntervalUs(originUs,Math.addExact(originUs,lengthUs))
        val source=AnalysisSourceIdentity(id,"generated-planning-v1")
        val beats=(0..128).asSequence().map {phaseUs+(it*60_000_000.0/bpm).roundToLong()}
            .takeWhile {it<lengthUs}.map {Math.addExact(originUs,it)}.toList()
        fun <T> absent()=MusicalValue<T>(null,EvidenceNature.UNAVAILABLE,EvidenceMethod.NOT_AVAILABLE)
        fun <T> native(v:T)=MusicalValue(v,EvidenceNature.INFERRED,EvidenceMethod.BITCHORD_TEMPO_TRACKER)
        val e=MusicalEvidence(native(bpm),native(beats),
            MusicalValue(beats.filterIndexed { i,_ -> i%4==0 },EvidenceNature.INFERRED,EvidenceMethod.BITCHORD_FOUR_BEAT_BASS_ACCENT),
            MusicalValue(.8,EvidenceNature.INFERRED,EvidenceMethod.BITCHORD_UNCALIBRATED_CONFIDENCE),
            absent(),absent(),absent(),
            MusicalValue(.1,EvidenceNature.MEASURED,EvidenceMethod.SUPPLIED_PCM_STATISTIC),
            MusicalValue(.5,EvidenceNature.MEASURED,EvidenceMethod.SUPPLIED_PCM_STATISTIC),
            absent(),absent(),absent(),absent(),"Synthetic native-shaped fixture; not DSP measurements")
        val frames=(lengthUs*22050/1_000_000).toInt()
        val m=AnalysisMetadata(BitChordNativeAnalysisEngine.BACKEND,PcmPreprocessing.BANDLIMITED,span,span,
            AudioCoverage(listOf(span),frames.toLong()),CoverageStatus.FULL,source,AnalysisDetail.DETAIL,
            AudioDuration(durationUs,DurationBasis.FIXTURE),emptyList(),0,false,
            nativeDiagnostic=NativeAnalysisDiagnostic("OK_UNCALIBRATED_EVIDENCE","upstream-sinc11025",frames,frames/2,11025,
                bpm,.8,1.0,0.0,1.0,0.0,0,listOf(bpm/2,bpm,bpm*2)))
        val result=AnalysisResult.Completed(m,e)
        val signal=PlanningSignalObservation(source,BitChordNativeAnalysisEngine.PROFILE,span,
            PlanningSignalObservation.VERSION,if(originUs==0L) 0 else null,beats,true,.1,.5)
        return PlanningWindowEvidence(result,signal)
    }
    fun request(out:PlanningWindowEvidence=evidence(), incoming:PlanningWindowEvidence=evidence(originUs=0,id="in"),
                generation:Long=1):NativePlanningRequest {
        fun track(w:PlanningWindowEvidence):PlanningTrack {
            val m=(w.analysis as AnalysisResult.Completed).metadata
            return PlanningTrack(m.source,requireNotNull(m.duration.durationUs),requireNotNull(m.sampleInterval))
        }
        return NativePlanningRequest(track(out),track(incoming),140_000_000,5_000_000,generation,"fixture-session-v1")
    }
    fun withAnalysis(window:PlanningWindowEvidence, change:(AnalysisResult.Completed)->AnalysisResult.Completed)=
        window.copy(analysis=change(window.analysis as AnalysisResult.Completed))

    /** More than click tracks: kick+tonal bed, deterministic backbeats, eighth hats, drift and silence. */
    fun pcm(bpm:Double=128.0, seconds:Int=30, phase:Double=.137, pattern:String="kick",
            leadingSeconds:Double=0.0, drift:Double=0.0, amplitude:Double=1.0):FloatArray {
        require(seconds in 1..32 && leadingSeconds in 0.0..seconds.toDouble())
        val rate=22050
        val audio=FloatArray(seconds*rate)
        var random=0x12345L
        fun noise():Double { random=(1664525*random+1013904223) and 0xffffffffL;return random/2147483648.0-1 }
        for(i in audio.indices) {
            val t=i.toDouble()/rate
            val value=when(pattern) {
                "noise" -> .15*noise()
                "sweep" -> .15*sin(2*PI*(200*t+80*t*t))
                "chord" -> .05*(sin(2*PI*261.625565*t)+sin(2*PI*329.627557*t)+sin(2*PI*391.995436*t))
                "silence" -> 0.0
                else -> if(t>=leadingSeconds) .008*(sin(2*PI*261.625565*t)+sin(2*PI*329.627557*t)+sin(2*PI*391.995436*t)) else 0.0
            }
            audio[i]=(value*amplitude).toFloat()
        }
        if(pattern in setOf("noise","sweep","chord","silence")) return audio
        var beat=0
        var position=leadingSeconds+phase
        while(position<seconds) {
            fun add(time:Double, kind:String, strength:Double) {
                val start=(time*rate).roundToInt()
                if(start !in audio.indices)return
                val length=(if(kind=="kick") .18 else .05)*rate
                for(j in 0 until min(length.toInt(),audio.size-start)) {
                    val t=j.toDouble()/rate
                    val wave=if(kind=="kick") .6*min(1.0,t/.002)*exp(-t*36)*sin(2*PI*70*t)
                        else .18*noise()*exp(-t*80)
                    audio[start+j]=(audio[start+j]+wave*strength*amplitude).toFloat()
                }
            }
            add(position,"kick",if(beat%4==0) 1.0 else if(pattern=="backbeat" && beat%2==1) .25 else .8)
            if(pattern in setOf("backbeat","hats")) {
                if(beat%2==1)add(position,"snare",1.0)
                add(position+30/bpm,"hat",.5)
            }
            position+=60/(bpm+drift*position/seconds)
            beat++
        }
        return audio
    }
    fun realWindow(pcm:FloatArray,originUs:Long,id:String,durationUs:Long=180_000_000) =
        NativeAnalysisFixtures.window(pcm,originUs).copy(identity=AnalysisSourceIdentity(id,"generated-planning-v1"),
            duration=AudioDuration(durationUs,DurationBasis.FIXTURE))
    fun analyze(engine:AnalysisEngine,pcm:FloatArray,originUs:Long,id:String,durationUs:Long=180_000_000):PlanningWindowEvidence {
        val window=realWindow(pcm,originUs,id,durationUs)
        val control=AnalysisControl()
        return PlanningWindowEvidence(engine.analyze(window,AnalysisDetail.DETAIL,control),PlanningSignalObservation.measure(window,control))
    }
}
