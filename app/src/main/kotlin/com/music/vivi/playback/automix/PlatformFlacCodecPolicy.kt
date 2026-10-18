package com.music.vivi.playback.automix

/** Corrected FLAC uses frame-aligned packets and a known platform software decoder.
 * Never silently select a vendor implementation with different chunk/timestamp behavior.
 * Missing platform support is an ordinary analysis failure; legacy playback is unaffected. */
internal object PlatformFlacCodecPolicy {
    fun select(availableSoftwareNames:List<String>):String? =
        listOf("c2.android.flac.decoder","OMX.google.flac.decoder").firstOrNull {it in availableSoftwareNames}
}
