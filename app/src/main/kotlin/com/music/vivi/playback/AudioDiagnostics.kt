package com.music.vivi.playback

import androidx.media3.common.C
import androidx.media3.common.Format

/** Source renderer format vs app AudioTrack output. Neither describes Bluetooth hardware output. */
data class AudioDiagnostics(
    val mimeType: String? = null, val sampleRate: Int? = null, val channels: Int? = null,
    val bitrate: Int? = null, val pcmBits: Int? = null,
    val outputSampleRate: Int? = null, val outputChannels: Int? = null, val outputEncoding: Int? = null,
) {
    companion object {
        fun from(format: Format?): AudioDiagnostics = if(format==null) AudioDiagnostics() else AudioDiagnostics(
            format.sampleMimeType, format.sampleRate.takeIf { it>0 }, format.channelCount.takeIf { it>0 },
            format.averageBitrate.takeIf { it>0 },
            if(format.sampleMimeType=="audio/raw") when(format.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> 8; C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT -> 32
                else -> null
            } else null)
    }
    fun fields(): List<String> = listOfNotNull(
        mimeType?.removePrefix("audio/"), pcmBits?.let { "$it-bit PCM" },
        sampleRate?.let { "${it/1000.0} kHz" }, bitrate?.let { "${it/1000} kbps" },
        channels?.let { "$it ch" })
}
