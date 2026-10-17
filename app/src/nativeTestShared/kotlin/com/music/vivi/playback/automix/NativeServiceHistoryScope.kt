// SPDX-License-Identifier: AGPL-3.0-or-later
package com.music.vivi.playback.automix

/** A bound MusicService may outlive one test. Preserve its real diagnostic history, while
 * assertions observe only attempts begun after this harness disabled/cleared the old queue.
 * Late records added to an old attempt cannot satisfy a new test's wait or completion count. */
internal class NativeServiceHistoryScope(baseline:MixHistory) {
    private val session=baseline.session
    private val lastAttempt=baseline.attempts.maxOfOrNull {it.number} ?: 0L
    fun since(history:MixHistory):MixHistory {
        check(history.session==session){"MusicService diagnostic session changed during test"}
        return history.copy(attempts=history.attempts.filter {it.number>lastAttempt})
    }
}
