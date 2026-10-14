package com.music.vivi.playback.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Local decoded PCM only. Neutral state is an exact byte copy; no effects while AutoMix is off. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AutoMixAudioProcessor : BaseAudioProcessor() {
    data class Parameters(val gain: Double = 1.0, val bassCut: Double = 0.0, val trebleCut: Double = 0.0)
    @Volatile var parameters = Parameters()
    private var kernel: AutoMixPcm? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) inputAudioFormat
            else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush() {
        kernel = if (isActive) AutoMixPcm(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
            .also { it.reset(parameters.gain) } else null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val p = parameters
        val dsp = kernel
        if (dsp == null || p == Parameters()) {
            output.put(inputBuffer)
            dsp?.reset()
        } else {
            var channel = 0
            val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
            while (inputBuffer.remaining() >= if (float) 4 else 2) {
                val sample = if (float) inputBuffer.float else inputBuffer.short / 32768f
                val value = dsp.sample(sample, channel, p.gain, p.bassCut, p.trebleCut)
                if (float) output.putFloat(value) else output.putShort((value * 32767).toInt().toShort())
                channel = (channel + 1) % inputAudioFormat.channelCount
            }
        }
        output.flip()
    }

    override fun onReset() { kernel = null; parameters = Parameters() }
}
