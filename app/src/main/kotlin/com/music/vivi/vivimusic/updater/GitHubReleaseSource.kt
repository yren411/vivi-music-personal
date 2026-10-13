package com.music.vivi.vivimusic.updater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Transport is injectable so tests never need public releases or GitHub credentials. */
fun interface ReleaseTransport { suspend fun get(url: String): String }

class GitHubReleaseSource(private val transport: ReleaseTransport) {
    companion object {
        const val REPOSITORY = "yren411/vivi-music-personal"
        const val API = "https://api.github.com/repos/$REPOSITORY/releases"
        const val APP_ID = "com.yren411.tinium"
        private val version = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-beta\\.[1-9]\\d*)?")
        private val hash = Regex("[0-9a-f]{64}")
        fun assetUrl(tag: String, filename: String) = "https://github.com/$REPOSITORY/releases/download/$tag/$filename"
        fun validDownload(update: TiniumUpdate): Boolean {
            val name = update.versionName
            return version.matches(name) && update.channel in setOf("beta", "stable") &&
                (name.contains("-beta.") == (update.channel == "beta")) && update.tag == "tinium-v$name" &&
                update.asset.name in listOf("gms", "foss").map { "tinium-${update.channel}-$name-universal-$it.apk" } &&
                update.asset.size > 0 && update.asset.url == assetUrl(update.tag, update.asset.name)
        }
    }

    suspend fun releases(): List<PublishedRelease> = guarded {
        val result = mutableListOf<PublishedRelease>()
        // GitHub returns publication order, which is not version-code order. Scan all pages.
        for (page in 1..10) {
            val array = Json.parseToJsonElement(transport.get("$API?per_page=100&page=$page")).jsonArray
            require(array.size <= 100)
            for (element in array) {
                val item = element.jsonObject
                if (item.bool("draft")) continue
                val tag = item.string("tag_name")
                // Reservation tags/candidates/foreign release naming are never updates.
                if (!tag.startsWith("tinium-v") || !version.matches(tag.removePrefix("tinium-v"))) continue
                if (item["published_at"] == null || item["published_at"] == JsonNull) continue
                val assets = item.getValue("assets").jsonArray.map {
                    val a = it.jsonObject
                    require(a.string("state") == "uploaded")
                    ReleaseAsset(a.string("name"), a.number("size"), a.string("browser_download_url"))
                }
                require(assets.map { it.name }.distinct().size == assets.size)
                val body = item["body"]?.let { if (it == JsonNull) "" else { require(it.jsonPrimitive.isString); it.jsonPrimitive.content } } ?: ""
                require(body.length <= 100_000)
                result += PublishedRelease(tag, item.bool("prerelease"), body, assets)
            }
            if (array.size < 100) return@guarded result
        }
        // Never declare up-to-date with a truncated release history.
        throw UpdateFailure(UpdateProblem.INVALID_RELEASE)
    }

    suspend fun check(installed: InstalledVersion, beta: Boolean): UpdateStatus = guarded {
        val releases = releases().filter { beta || !it.prerelease }
        if (releases.isEmpty()) return@guarded UpdateStatus.NoPublishedReleases
        val updates = releases.map { validate(it, installed) }
        require(updates.map { it.first.versionCode }.distinct().size == updates.size)
        updates.filter { it.second && it.first.versionCode > installed.code }.maxByOrNull { it.first.versionCode }
            ?.let { UpdateStatus.Available(it.first) } ?: UpdateStatus.UpToDate
    }

    private suspend fun validate(published: PublishedRelease, installed: InstalledVersion): Pair<TiniumUpdate, Boolean> {
        val metadataAsset = published.assets.single { it.name == "release.json" }
        require(metadataAsset.size in 1..50_000 && metadataAsset.url == assetUrl(published.tag, "release.json"))
        val root = Json.parseToJsonElement(transport.get(metadataAsset.url)).jsonObject
        require(root.number("schemaVersion") == 1L && root.string("repository") == REPOSITORY)
        val release = root.getValue("release").jsonObject
        val name = release.string("versionName")
        val channel = release.string("channel")
        val code = release.number("versionCode")
        require(release.number("schemaVersion") == 1L && release.string("applicationId") == APP_ID)
        require(version.matches(name) && channel in setOf("stable", "beta") && code in 78..2_100_000_000L)
        require(name.contains("-beta.") == (channel == "beta") && published.prerelease == (channel == "beta"))
        require(release.string("tag") == published.tag && published.tag == "tinium-v$name")
        val commit = release.string("commit")
        require(Regex("[0-9a-f]{40}").matches(commit) && hash.matches(root.string("certificateSha256")))
        val build = root.getValue("build").jsonObject
        require(build.string("commit") == commit && build.string("channel") == channel)
        require(build.number("buildRunId") > 0 && build.number("buildAttempt") > 0)
        val assets = root.getValue("assets").jsonArray.map { it.jsonObject }
        require(assets.size == 2 && assets.map { it.string("variant") }.toSet() == setOf("gms", "foss"))
        var selected: ReleaseAsset? = null
        var compatible = false
        for (entry in assets) {
            val variant = entry.string("variant")
            val filename = "tinium-$channel-$name-universal-$variant.apk"
            require(entry.string("abi") == "universal" && entry.string("filename") == filename)
            require(hash.matches(entry.string("sha256")))
            val asset = published.assets.single { it.name == filename }
            require(asset.size > 0 && asset.size == entry.number("size"))
            require(asset.url == assetUrl(published.tag, filename) && asset.url == entry.string("url"))
            val minimum = entry.number("minSdk")
            val target = entry.number("targetSdk")
            require(minimum > 0 && target >= minimum)
            val abis = entry.getValue("supportedAbis").jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
            require(abis.distinct().size == abis.size && abis.all { Regex("[a-z0-9_-]{1,32}").matches(it) })
            if (variant == installed.variant) {
                compatible = installed.sdk >= minimum && (abis.isEmpty() || installed.abis.any { it in abis })
                selected = asset
            }
        }
        val notes = root.getValue("notes").jsonObject
        require(notes.string("filename") == "release-notes.md" && hash.matches(notes.string("sha256")))
        val notesAsset = published.assets.single { it.name == "release-notes.md" }
        require(notesAsset.size > 0 && notesAsset.url == assetUrl(published.tag, "release-notes.md"))
        return TiniumUpdate(name, code, channel, published.tag, requireNotNull(selected), cleanReleaseNotes(published.body))
            .also { require(validDownload(it)) } to compatible
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try { withContext(Dispatchers.Default) { block() } }
    catch (e: CancellationException) { throw e }
    catch (e: UpdateFailure) { throw e }
    catch (_: Exception) { throw UpdateFailure(UpdateProblem.INVALID_RELEASE) }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }

/** No HTML is rendered. Strip comments, including unclosed comments, before Markdown parsing. */
fun cleanReleaseNotes(body: String): String = body.replace(Regex("<!--[\\s\\S]*?(?:-->|$)"), "").trim()
fun releaseTitle(name: String): String = name.replace(Regex("-beta\\.(\\d+)$"), " Beta $1")

/** Injectable browser boundary; never accepts arbitrary URLs or an older/equal installation. */
internal fun launchUpdateDownload(update: TiniumUpdate, installed: InstalledVersion, open: (String) -> Boolean): Boolean =
    GitHubReleaseSource.validDownload(update) && update.versionCode > installed.code &&
        update.asset.name == "tinium-${update.channel}-${update.versionName}-universal-${installed.variant}.apk" && open(update.asset.url)
/** Public HTTP failures, without logging response bodies or redirect URLs. */
internal fun httpFailure(code: Int, reset: String?, retry: String?, now: Long): UpdateFailure? = when {
    code == 403 || code == 429 -> UpdateFailure(UpdateProblem.RATE_LIMIT,
        maxOf(reset?.toLongOrNull()?.times(1000) ?: 0, retry?.toLongOrNull()?.let { now + it * 1000 } ?: 0))
    code !in 200..299 -> UpdateFailure(UpdateProblem.HTTP)
    else -> null
}
