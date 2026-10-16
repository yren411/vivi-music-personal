// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import kotlin.math.*

/** Same kick recipe/phase as prototype corpus v1; generated PCM, never production code. */
internal object NativeAnalysisFixtures {
    fun groove(bpm: Double = 128.0, seconds: Int = 30, rate: Int = 22050, phase: Double = .137): FloatArray {
        val pcm = FloatArray(seconds * rate) { i ->
            val t = i.toDouble()/rate
            (.008 * (sin(2*PI*261.625565*t)+sin(2*PI*329.627557*t)+sin(2*PI*391.995436*t))).toFloat()
        }
        var beat=0
        while (phase+beat*60/bpm < seconds) {
            val start=((phase+beat*60/bpm)*rate).roundToInt()
            val accent=if(beat%4==0) 1.0 else .8
            for (j in 0 until min((.18*rate).roundToInt(),pcm.size-start)) {
                val t=j.toDouble()/rate
                pcm[start+j]=(pcm[start+j]+.6*accent*min(1.0,t/.002)*exp(-t*36)*sin(2*PI*70*t)).toFloat()
            }
            beat++
        }
        return pcm
    }
    fun window(samples: FloatArray = groove(seconds=8), origin: Long = 0): DecodedWindow {
        val interval=AudioIntervalUs(origin,Math.addExact(origin,samples.size*1_000_000L/22050))
        return DecodedWindow(samples,PcmFormat(22050,1,PcmEncoding.FLOAT32),interval,interval,origin,
            AudioCoverage(listOf(interval),samples.size.toLong()),emptyList(),
            AudioDuration(interval.endUs,DurationBasis.FIXTURE),false,
            AnalysisSourceIdentity("generated-native-fixture","recipe-v1"),PcmPreprocessing.BANDLIMITED,0)
    }
    /** Explicit fake transport for mapping/validation unit tests ONLY. Real JNI checks never use it. */
    fun packet(window: DecodedWindow, bpm: Double = 120.0, status: String = "OK_UNCALIBRATED_EVIDENCE"): NativeDspPacket {
        val seconds=(window.samples.size/2)/11025.0
        val beats=generateSequence(.137) { it+60/bpm }.takeWhile { it<seconds }.toList().toDoubleArray()
        return NativeDspPacket(1,status,doubleArrayOf(window.samples.size.toDouble(),22050.0,
            (window.samples.size/2).toDouble(),11025.0,window.samples.size/22050.0,seconds,bpm,60/bpm,.137,.8,.1,.5,100.0,20.0,70.0,0.0,10.0),
            beats,beats.filterIndexed { i,_ -> i%4==0 }.toDoubleArray())
    }
}
