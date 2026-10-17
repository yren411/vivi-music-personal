// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Holds the analyzed incoming rendition only during transition ownership. Release on successful
 * promotion (or standby disposal) so subsequent network opens can refresh normally. The loading
 * thread observes release without reading service/player state. Never stringify source handles. */
internal class NativePlaybackSourcePin<S>(private val recordingId:String,source:S) {
    @Volatile private var source:S?=source
    fun sourceFor(id:String):S?=if(id==recordingId)source else null
    fun release(){source=null}
    override fun toString()="NativePlaybackSourcePin(redacted)"
}
