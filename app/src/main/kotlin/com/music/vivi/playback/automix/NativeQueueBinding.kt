// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Source configuration is part of pair validity even when recording IDs stay the same.
 * Future playlist entries/indices are deliberately absent. Never stringify private URIs. */
internal data class NativeQueuedSource(val source:AnalysisSourceIdentity,val uri:String?,val cacheKey:String?,val mimeType:String?) {
    override fun toString()="NativeQueuedSource(redacted)"
}
internal data class NativeQueueBinding(val outgoing:NativeQueuedSource,val incoming:NativeQueuedSource,
    val repeatMode:Int,val shuffle:Boolean) {
    override fun toString()="NativeQueueBinding(redacted)"
}
