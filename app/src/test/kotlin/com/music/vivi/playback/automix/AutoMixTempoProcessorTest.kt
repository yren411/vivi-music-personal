package com.music.vivi.playback.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AutoMixTempoProcessorTest {
    @Test fun realSonicPcmStretchChangesDurationWithoutChangingTonePitch() {
        val rate=48000
        val speed=0.97f
        val processor=SonicAudioProcessor()
        processor.setSpeed(speed); processor.setPitch(1f)
        processor.configure(AudioProcessor.AudioFormat(rate,1,C.ENCODING_PCM_16BIT)); processor.flush()
        val input=ByteBuffer.allocateDirect(rate*2*2).order(ByteOrder.nativeOrder())
        repeat(rate*2) { i -> input.putShort((sin(2*PI*440*i/rate)*16000).toInt().toShort()) }
        input.flip(); processor.queueInput(input); processor.queueEndOfStream()
        val output=processor.output.order(ByteOrder.nativeOrder())
        val samples=ShortArray(output.remaining()/2) { output.short }
        assertEquals(2.0/speed,samples.size.toDouble()/rate,0.035)
        var crossings=0
        val start=4800
        for(i in start+1 until samples.size-4800) if(samples[i-1]<=0 && samples[i]>0) crossings++
        val hz=crossings.toDouble()*rate/(samples.size-9600)
        assertEquals(440.0,hz,3.0)
        assertTrue(samples.maxOf { abs(it.toInt()) }<20000)
        processor.reset()
    }
}
