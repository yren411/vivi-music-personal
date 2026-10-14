package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AutoMixPcmTest {
    @Test fun actualRenderedMixContainsBothAudioSignals() {
        val rate=48000
        val gains=mixGains(0.5)
        val outgoing=AutoMixPcm(rate,1).apply { reset(gains.outgoing) }
        val incoming=AutoMixPcm(rate,1).apply { reset(gains.incoming) }
        val output=FloatArray(rate) { i ->
            val a=(0.8*sin(2*PI*440*i/rate)).toFloat()
            val b=(0.8*sin(2*PI*660*i/rate)).toFloat()
            outgoing.sample(a,0,gains.outgoing,0.0,0.0)+incoming.sample(b,0,gains.incoming,0.0,0.0)
        }
        fun component(hz:Int)=abs(output.indices.sumOf { output[it]*sin(2*PI*hz*it/rate) }/rate)
        assertTrue(component(440)>0.15)
        assertTrue(component(660)>0.15)
        assertTrue(component(880)<0.005)
        assertTrue(output.maxOf { abs(it) }<=0.94f)
    }
    @Test fun fullyCorrelatedSignalsDoNotClipThroughoutGainRamp() {
        val rate=48000
        val a=AutoMixPcm(rate,1)
        val b=AutoMixPcm(rate,1).apply { reset(0.0) }
        var peak=0f
        var previous=0f
        var largestStep=0f
        for (i in 0 until rate*2) {
            val p=i.toDouble()/(rate*2)
            val g=mixGains(p)
            val sample=sin(2*PI*440*i/rate).toFloat()
            val out=a.sample(sample,0,g.outgoing,0.0,0.0)+b.sample(sample,0,g.incoming,0.0,0.0)
            peak=max(peak,abs(out)); largestStep=max(largestStep,abs(out-previous)); previous=out
        }
        assertTrue(peak<=1f)
        assertTrue(largestStep<0.07f)
    }
    @Test fun bassHandoffReducesLowFrequencyWithoutRemovingHighs() {
        fun power(hz:Int,cut:Double):Double {
            val dsp=AutoMixPcm(48000,1)
            return (0 until 48000).sumOf { i ->
                val out=dsp.sample((0.5*sin(2*PI*hz*i/48000)).toFloat(),0,1.0,cut,0.0)
                if (i<4800) 0.0 else out.toDouble()*out
            }
        }
        assertTrue(power(60,0.85)<power(60,0.0)*0.3)
        assertTrue(power(2000,0.85)>power(2000,0.0)*0.8)
    }
    @Test fun stereoChannelsKeepIndependentFilterHistory() {
        val stereo=AutoMixPcm(48000,2)
        repeat(1000) {
            stereo.sample(0.5f,0,1.0,0.8,0.0)
            assertEquals(0f,stereo.sample(0f,1,1.0,0.8,0.0),0f)
        }
    }
}
