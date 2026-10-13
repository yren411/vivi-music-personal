package com.music.vivi.vivimusic.updater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

interface UpdateStore {
    var lastSuccess: Long
    var availableCode: Long
    var notifiedCode: Long
    var retryAt: Long
    var lastBeta: Boolean?
}

/** Shared by startup, settings and the screen. No Activity or screen is retained here. */
class UpdateCoordinator(
    private val source: GitHubReleaseSource,
    val installed: InstalledVersion,
    private val store: UpdateStore,
    private val beta: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notify: (TiniumUpdate) -> Boolean = { false },
    private val cancelNotification: () -> Unit = {},
    private val automaticEnabled: () -> Boolean = { true },
    private val timeoutMillis: Long = 60_000,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val state = mutableState.asStateFlow()
    private val mutableLastSuccess = MutableStateFlow(store.lastSuccess)
    val lastSuccess = mutableLastSuccess.asStateFlow()
    private var lastAttempt = 0L
    private var lastAttemptPolicy: Boolean? = null
    private var lastPolicy: Boolean? = store.lastBeta

    init {
        if (store.availableCode <= installed.code) { store.availableCode = 0; cancelNotification() }
    }

    fun invalidateChannel() {
        lastPolicy = null
        store.lastBeta = null
        mutableState.value = UpdateStatus.Idle
        store.availableCode = 0
        cancelNotification()
    }

    fun markSeen() {
        (state.value as? UpdateStatus.Available)?.let { store.notifiedCode = it.update.versionCode; cancelNotification() }
    }

    suspend fun check(force: Boolean = false, automatic: Boolean = false) {
        if (automatic && !automaticEnabled()) return
        if (!mutex.tryLock()) return // One request shared by every caller.
        try {
            val now = clock()
            if (now < store.retryAt) { mutableState.value = UpdateStatus.Error(UpdateProblem.RATE_LIMIT); return }
            if (!force && lastAttemptPolicy == beta() && now - lastAttempt < 15 * 60_000L) return
            if (automatic && lastPolicy == beta() && now - store.lastSuccess < 12 * 60 * 60_000L) return
            lastAttempt = now
            lastAttemptPolicy = beta()
            mutableState.value = UpdateStatus.Checking
            var policy: Boolean
            var result: UpdateStatus
            while (true) {
                policy = beta()
                try {
                    result = withTimeoutOrNull(timeoutMillis) { source.check(installed, policy) }
                        ?: throw UpdateFailure(UpdateProblem.NETWORK)
                } catch (e: UpdateFailure) {
                    if (policy != beta()) continue // An obsolete channel's failure must not mask the new channel.
                    throw e
                }
                if (policy == beta()) break
            }
            lastPolicy = policy
            store.lastBeta = policy
            store.lastSuccess = clock()
            mutableLastSuccess.value = store.lastSuccess
            store.retryAt = 0
            val update = (result as? UpdateStatus.Available)?.update
            store.availableCode = update?.versionCode ?: 0
            if (update == null) cancelNotification()
            else if (automatic && automaticEnabled() && store.notifiedCode != update.versionCode && notify(update)) store.notifiedCode = update.versionCode
            mutableState.value = result
        } catch (e: CancellationException) {
            mutableState.value = UpdateStatus.Idle
            throw e
        } catch (e: UpdateFailure) {
            if (e.problem == UpdateProblem.RATE_LIMIT) store.retryAt = maxOf(e.retryAt, clock() + 60_000L)
            mutableState.value = UpdateStatus.Error(e.problem)
        } finally { mutex.unlock() }
    }
}
