package com.music.vivi.vivimusic.updater

/** All update decisions use Android's numeric installation version, never tag ordering. */
data class InstalledVersion(val name: String, val code: Long, val variant: String, val sdk: Int, val abis: List<String>)
data class ReleaseAsset(val name: String, val size: Long, val url: String)
data class PublishedRelease(val tag: String, val prerelease: Boolean, val body: String, val assets: List<ReleaseAsset>)
data class TiniumUpdate(val versionName: String, val versionCode: Long, val channel: String, val tag: String,
    val asset: ReleaseAsset, val notes: String)

enum class UpdateProblem { NETWORK, RATE_LIMIT, INVALID_RELEASE, HTTP }
class UpdateFailure(val problem: UpdateProblem, val retryAt: Long = 0) : Exception(problem.name)
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object NoPublishedReleases : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val update: TiniumUpdate) : UpdateStatus
    data class Error(val problem: UpdateProblem) : UpdateStatus
}
