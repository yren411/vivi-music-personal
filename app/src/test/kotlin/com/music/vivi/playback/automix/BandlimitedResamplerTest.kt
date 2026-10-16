package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BandlimitedResamplerTest {
    private fun tone(rate: Int, hz: Double, seconds: Double = 1.0, phase: Double = 0.31) =
        FloatArray((rate * seconds).roundToInt()) { (0.5 * sin(2 * PI * hz * it / rate + phase)).toFloat() }
    private fun rms(x: FloatArray, margin: Int = 1024): Double =
        sqrt((margin until x.size - margin).sumOf { x[it].toDouble().pow(2) } / (x.size - 2 * margin))
    private fun convert(rate: Int, samples: FloatArray) = BandlimitedResampler(rate).resample(samples).samples
    @Test fun preservesPassbandAndTimingAcrossSupportedRates() {
        for (rate in listOf(8000,11025,16000,22050,32000,44100,48000,96000,192000)) {
            val hz = min(1000.0, rate * 0.1)
            val actual = convert(rate, tone(rate, hz))
            assertEquals(22050, actual.size)
            val expected = tone(22050, hz)
            val error = sqrt((1024 until actual.size-1024).sumOf { (actual[it] - expected[it]).toDouble().pow(2) } / (actual.size-2048))
            assertTrue("rate=" + rate + " error=" + error, error < 0.0003)
            assertEquals(0.5 / sqrt(2.0), rms(actual), 0.0005)
        }
    }
    @Test fun downsamplingRejectsAboveNyquistInsteadOfAliasing() {
        for (rate in listOf(32000,44100,48000,96000)) {
            val pass = convert(rate, tone(rate,8000.0))
            val stop = convert(rate, tone(rate,14000.0))
            val attenuation = 20 * log10(rms(stop) / (0.5 / sqrt(2.0)))
            println("RESAMPLER rate=" + rate + " pass8000Rms=" + rms(pass) + " stop14000dB=" + attenuation)
            assertEquals(0.5 / sqrt(2.0), rms(pass),0.001)
            assertTrue("stopband " + rate + " dB=" + attenuation, attenuation < -60)
        }
    }
    @Test fun upsamplingSuppressesImages() {
        val output = convert(8000,tone(8000,1000.0))
        fun amplitude(hz: Double): Double {
            var a=0.0;var b=0.0
            for (i in 1024 until output.size-1024) { a+=output[i]*cos(2*PI*hz*i/22050);b+=output[i]*sin(2*PI*hz*i/22050) }
            return 2*hypot(a,b)/(output.size-2048)
        }
        assertEquals(0.5,amplitude(1000.0),0.002)
        assertTrue(amplitude(7000.0)<0.0003)
    }
    @Test fun dcSilenceAndBoundaryExtensionAreStable() {
        for(rate in listOf(8000,22050,44100,48000,96000)) {
            val dc=convert(rate,FloatArray(rate){0.375f})
            assertTrue(dc.all { abs(it-0.375f)<0.000002 })
            assertTrue(convert(rate,FloatArray(rate)).all { it==0f })
        }
    }
    @Test fun identityRateIsBitExactAndFractionalOriginIsSupported() {
        val signal=tone(22050,930.0)
        assertArrayEquals(signal,convert(22050,signal),0f)
        val shifted=BandlimitedResampler(22050).resample(signal,firstInputFrame=0.5,outputFrames=22049).samples
        for(i in 1024 until shifted.size-1024) assertEquals(0.5*sin(2*PI*930*(i+0.5)/22050+0.31),shifted[i].toDouble(),0.0001)
    }
    @Test fun impulsesHaveNoUnreportedFilterDelay() {
        for(rate in listOf(32000,44100,48000,96000)) {
            val input=FloatArray(rate);input[rate/2]=1f
            val output=convert(rate,input)
            val peak=output.indices.maxBy { abs(output[it]) }
            assertEquals(11025,peak)
            assertTrue(output.all { it.isFinite() })
        }
    }
    @Test fun multitoneSweepAndDeterministicNoiseRemainFiniteAndBounded() {
        var seed=411L
        val input=FloatArray(48000) { i ->
            seed=(seed*1664525+1013904223) and 0xffffffffL
            val t=i/48000.0
            (0.15*sin(2*PI*500*t)+0.15*sin(2*PI*7000*t)+0.1*sin(2*PI*(100*t+9000*t*t))+
                0.05*(seed/4294967296.0*2-1)).toFloat()
        }
        val a=convert(48000,input);val b=convert(48000,input)
        assertArrayEquals(a,b,0f);assertTrue(a.all { it.isFinite() && abs(it)<0.6 })
    }
    @Test fun nonIntegerLengthsUseFloorFrameCountWithoutAccumulatedRounding() {
        for(rate in listOf(11025,44100,48000,96000)) {
            val input=tone(rate,1000.0,0.731)
            val output=convert(rate,input)
            assertEquals(input.size.toLong()*22050/rate,output.size.toLong())
        }
    }
    @Test fun leadingAndTrailingSilenceAreNotShifted() {
        val input=FloatArray(48000);tone(48000,500.0,0.5).copyInto(input,12000)
        val out=convert(48000,input)
        assertTrue(out.take(5000).all { abs(it)<1e-8 });assertTrue(out.takeLast(5000).all { abs(it)<1e-8 })
        assertTrue(out.sliceArray(6000..16000).any { abs(it)>0.4 })
    }
    @Test fun cancellationAndDeadlineAreCheckedDuringKernelAndConversion() {
        val expired=AnalysisControl(System.nanoTime()-1)
        try { BandlimitedResampler(48000,control=expired);fail() } catch(e:AnalysisUnavailable) { assertEquals("ANALYSIS_TIMEOUT",e.reason) }
        val converter=BandlimitedResampler(48000)
        var checks=0
        val token=AnalysisCancellation { if(++checks==5)throw java.util.concurrent.CancellationException() }
        try { converter.resample(FloatArray(48000),control=AnalysisControl(cancellation=token));fail() }
        catch(_:java.util.concurrent.CancellationException) { assertEquals(5,checks) }
    }
    @Test fun rejectsUnboundedOrMalformedRequests() {
        for(rate in listOf(0,7999,192001))try { BandlimitedResampler(rate);fail() }catch(_:IllegalArgumentException){}
        val converter=BandlimitedResampler(48000)
        try { converter.resample(FloatArray(0));fail() }catch(_:IllegalArgumentException){}
        try { converter.resample(FloatArray(1),outputFrames=22050*33);fail() }catch(_:IllegalArgumentException){}
    }
}
