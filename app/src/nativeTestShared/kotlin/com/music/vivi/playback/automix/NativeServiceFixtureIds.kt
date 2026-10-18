// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

import java.util.UUID

/** Media3 caches by recording/custom cache key, not the temporary file URI. Every harness
 * supplies different bytes, so it must own a fresh namespace even across process/device runs. */
internal class NativeServiceFixtureIds(scope:String=UUID.randomUUID().toString()) {
    private val keys=List(3){"phase33-generated-$scope-$it"}
    operator fun get(index:Int)=keys[index]
    /** Never clear a shared cache or remove another test's/source's recording. */
    fun removeCachedResources(remove:(String)->Unit)=keys.forEach(remove)
}
