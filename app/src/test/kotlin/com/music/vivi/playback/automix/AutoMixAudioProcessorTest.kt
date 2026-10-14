package com.music.vivi.playback.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AutoMixAudioProcessorTest {
    @Test fun neutralProcessorPreservesPcm16BytesIncludingPausedStandbyPrebuffer() {
        val processor=AutoMixAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT)); processor.flush()
        val bytes=byteArrayOf(0,0,1,2,3,4,127,126,-1,-1,0,-128)
        val input=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        input.flip()
        processor.queueInput(input)
        val output=processor.output
        val actual=ByteArray(output.remaining()); output.get(actual)
        assertArrayEquals(bytes,actual) // standby silence comes from deck volume, not zeroed decoded PCM
        processor.reset()
    }
    @Test fun neutralFloatProcessorPreservesSamples() {
        val processor=AutoMixAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100,1,C.ENCODING_PCM_FLOAT)); processor.flush()
        val input=ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).putFloat(-0.8f).putFloat(0f).putFloat(0.7f)
        input.flip()
        processor.queueInput(input)
        val out=processor.output
        assertEquals(-0.8f,out.float,0f); assertEquals(0f,out.float,0f); assertEquals(0.7f,out.float,0f)
        processor.reset()
    }
    @Test fun unsupportedPcmDoesNotAdvertiseAnActiveProcessor() {
        val processor=AutoMixAudioProcessor()
        assertEquals(AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_24BIT)))
        processor.flush(); assertFalse(processor.isActive); processor.reset()
    }
}
