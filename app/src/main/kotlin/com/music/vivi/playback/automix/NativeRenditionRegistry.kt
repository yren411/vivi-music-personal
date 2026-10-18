// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** Bounded authorized source handles only. A late resolver cannot repopulate a cleared mode or
 * quality epoch. Values may contain private URIs; never stringify/export them. */
class NativeRenditionRegistry<S>(private val capacity:Int=8) {
    init {require(capacity in 1..64)}
    private var generation=0L
    private val values=object:LinkedHashMap<String,S>(capacity,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,S>)=size>capacity
    }
    val epoch:Long @Synchronized get()=generation
    @Synchronized operator fun get(id:String?):S?=id?.let {values[it]}
    @Synchronized fun containsKey(id:String)=values.containsKey(id)
    @Synchronized fun remember(id:String,source:S,expectedEpoch:Long):Boolean {
        if(expectedEpoch!=generation)return false
        values[id]=source;return true
    }
    @Synchronized fun remove(id:String) {generation++;values.remove(id)}
    @Synchronized fun clear() {generation++;values.clear()}
    override fun toString()="NativeRenditionRegistry(redacted)"
}
