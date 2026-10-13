package com.music.vivi.vivimusic.updater

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.commonmark.node.Heading

class GitHubUpdaterTest {
    private val installed = InstalledVersion("6.0.8", 78, "gms", 37, listOf("arm64-v8a"))
    private fun resource(name: String) = javaClass.getResource("/updater/$name")!!.readText()
    private data class Fixture(val release: JsonObject, val metadata: JsonObject)
    private fun fixture(name: String = "6.0.9", code: Long = 79): Fixture {
        val beta = name.contains("-beta.")
        fun input(file: String): JsonObject = Json.parseToJsonElement(resource(file).replace("6.0.9", name)
            .let { if (beta) it.replace("stable", "beta") else it }).jsonObject
        val release = input("github-release.json").change("prerelease", JsonPrimitive(beta))
        val metadata = input("release.json").change("release", input("release.json").getValue("release").jsonObject.change("versionCode", JsonPrimitive(code)))
        return Fixture(release, metadata)
    }
    private fun JsonObject.change(key: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(key, value) })
    private fun Fixture.metadataField(key: String, value: JsonElement) = copy(metadata = metadata.change(key, value))
    private fun Fixture.releaseField(key: String, value: JsonElement) = copy(release = release.change(key, value))
    private class Transport(val fixtures: List<Fixture>) : ReleaseTransport {
        var requests = 0
        var failure: UpdateFailure? = null
        var invalidMetadata = false
        override suspend fun get(url: String): String {
            requests++
            failure?.let { throw it }
            if (url.startsWith(GitHubReleaseSource.API)) return JsonArray(fixtures.map { it.release }).toString()
            if (invalidMetadata) return "{broken json"
            return fixtures.single { url == it.release.getValue("assets").jsonArray.first { a -> a.jsonObject["name"]?.jsonPrimitive?.content == "release.json" }.jsonObject["browser_download_url"]?.jsonPrimitive?.content }.metadata.toString()
        }
    }
    private fun check(vararg fixtures: Fixture, current: InstalledVersion = installed, beta: Boolean = false) = runBlocking {
        GitHubReleaseSource(Transport(fixtures.toList())).check(current, beta)
    }
    private fun invalid(f: Fixture, current: InstalledVersion = installed) {
        try { check(f, current = current); fail("Unverified release was accepted") }
        catch (e: UpdateFailure) { assertEquals(UpdateProblem.INVALID_RELEASE, e.problem) }
    }

    @Test fun newerStableIsAvailable() { assertEquals(79, (check(fixture()) as UpdateStatus.Available).update.versionCode) }
    @Test fun equalCodeIsUpToDate() { assertEquals(UpdateStatus.UpToDate, check(fixture(), current = installed.copy(name = "6.0.9", code = 79))) }
    @Test fun equalCodeDifferentNameIsNotUpdate() { assertEquals(UpdateStatus.UpToDate, check(fixture(), current = installed.copy(name = "local-build", code = 79))) }
    @Test fun olderCodeNeverOffered() { assertEquals(UpdateStatus.UpToDate, check(fixture(), current = installed.copy(code = 80))) }
    @Test fun betaDisabledIgnoresNewerPrerelease() { assertEquals(UpdateStatus.UpToDate, check(fixture(code = 78), fixture("6.1.0-beta.1", 80))) }
    @Test fun betaEnabledOffersNewerPrerelease() { assertEquals(80, (check(fixture(), fixture("6.1.0-beta.1", 80), beta = true) as UpdateStatus.Available).update.versionCode) }
    @Test fun newerInstalledBetaDoesNotDowngradeToStable() { assertEquals(UpdateStatus.UpToDate, check(fixture(), current = installed.copy(name = "6.1.0-beta.1", code = 80))) }
    @Test fun highestCodeWinsRegardlessOfOrderOrChannel() { assertEquals("stable", (check(fixture("6.1.0", 82), fixture("6.2.0-beta.1", 81), fixture(), beta = true) as UpdateStatus.Available).update.channel) }
    @Test fun duplicateCodesRejected() { try { check(fixture(), fixture("6.1.0", 79)); fail() } catch (e: UpdateFailure) { assertEquals(UpdateProblem.INVALID_RELEASE, e.problem) } }
    @Test fun gmsAssetSelected() { assertTrue((check(fixture()) as UpdateStatus.Available).update.asset.name.endsWith("-gms.apk")) }
    @Test fun fossAssetSelectedWithItsActualSize() { val u = (check(fixture(), current = installed.copy(variant = "foss")) as UpdateStatus.Available).update; assertTrue(u.asset.name.endsWith("-foss.apk")); assertEquals(2048L, u.asset.size) }
    @Test fun noPublishedReleases() { assertEquals(UpdateStatus.NoPublishedReleases, check()) }
    @Test fun draftsAndReservedTagsIgnored() {
        assertEquals(UpdateStatus.NoPublishedReleases, check(fixture().releaseField("draft", JsonPrimitive(true)), fixture().releaseField("tag_name", JsonPrimitive("tinium-code/0000000079"))))
    }
    @Test fun unpublishedReleaseIgnored() { assertEquals(UpdateStatus.NoPublishedReleases, check(fixture().releaseField("published_at", JsonNull))) }
    @Test fun missingApkRejected() { val f = fixture(); invalid(f.releaseField("assets", JsonArray(f.release.getValue("assets").jsonArray.filterNot { it.jsonObject["name"]?.jsonPrimitive?.content?.endsWith("-gms.apk") == true }))) }
    @Test fun missingMetadataRejected() { val f = fixture(); invalid(f.releaseField("assets", JsonArray(f.release.getValue("assets").jsonArray.filterNot { it.jsonObject["name"]?.jsonPrimitive?.content == "release.json" }))) }
    @Test fun corruptedMetadataRejected() = runBlocking {
        val transport = Transport(listOf(fixture())).apply { invalidMetadata = true }
        try { GitHubReleaseSource(transport).check(installed, false); fail() } catch (e: UpdateFailure) { assertEquals(UpdateProblem.INVALID_RELEASE, e.problem) }
    }
    @Test fun wrongRepositoryRejected() { invalid(fixture().metadataField("repository", JsonPrimitive("vivizzz007/vivi-music"))) }
    @Test fun wrongPackageRejected() { val f = fixture(); invalid(f.metadataField("release", f.metadata.getValue("release").jsonObject.change("applicationId", JsonPrimitive("wrong.package")))) }
    @Test fun wrongChannelRejected() { val f = fixture(); invalid(f.metadataField("release", f.metadata.getValue("release").jsonObject.change("channel", JsonPrimitive("beta")))) }
    @Test fun unsupportedSchemaRejected() { invalid(fixture().metadataField("schemaVersion", JsonPrimitive(2))) }
    @Test fun missingCertificateRejected() { invalid(fixture().metadataField("certificateSha256", JsonPrimitive(""))) }
    @Test fun inconsistentSizeAndArbitraryUrlRejected() {
        val f = fixture(); val assets = f.release.getValue("assets").jsonArray
        invalid(f.releaseField("assets", JsonArray(assets.mapIndexed { i,a -> if (i == 0) a.jsonObject.change("size", JsonPrimitive(1)) else a })))
        invalid(f.releaseField("assets", JsonArray(assets.mapIndexed { i,a -> if (i == 0) a.jsonObject.change("browser_download_url", JsonPrimitive("https://evil.example/update.apk")) else a })))
    }
    @Test fun incompatibleSdkOrAbiNotOffered() { assertEquals(UpdateStatus.UpToDate, check(fixture(), current = installed.copy(sdk = 25))); assertEquals(UpdateStatus.UpToDate, check(fixture(), current = installed.copy(abis = listOf("unknown")))) }
    @Test fun commentsRemovedAndNotesComeFromBody() {
        val f = fixture().releaseField("body", JsonPrimitive("Edited notes\n<!-- tinium-release:{\"hidden\":true} -->\n<!-- another hidden comment -->"))
        assertEquals("Edited notes", (check(f) as UpdateStatus.Available).update.notes)
        assertEquals("visible", cleanReleaseNotes("visible<!-- unclosed comment"))
    }
    @Test fun emptyDescriptionIsSafe() { assertEquals("", (check(fixture().releaseField("body", JsonNull)) as UpdateStatus.Available).update.notes) }
    @Test fun markdownSupportsHeadingsListsEmphasisLinksAndCode() {
        val blocks = noteBlocks(fixture().release["body"]!!.jsonPrimitive.content)
        assertTrue(blocks.first().node is Heading)
        assertTrue(blocks.any { it.prefix == "• " })
        assertTrue(blocks.any { it.prefix == "1. " })
        val text = blocks.map { annotatedNote(it, Color.Blue) }
        assertTrue(text.any { it.spanStyles.any { span -> span.item.fontWeight != null } })
        assertTrue(text.any { it.spanStyles.any { span -> span.item.fontStyle != null } })
        assertTrue(text.any { it.spanStyles.any { span -> span.item.fontFamily != null } })
        assertTrue(text.any { it.getStringAnnotations("URL", 0, it.length).isNotEmpty() })
        assertFalse(text.any { "tinium-release" in it.text })
    }
    @Test fun htmlImagesAndUnsafeLinksNeverRenderedOrFetched() {
        val blocks = noteBlocks("<script>alert('bad')</script>\n\n![image](https://evil.example/image.png) [unsafe](javascript:alert) [safe](https://github.com)")
        val text = blocks.map { annotatedNote(it, Color.Blue) }
        assertFalse(text.any { "alert('bad')" in it.text || "image" in it.text })
        assertEquals(listOf("https://github.com"), text.flatMap { it.getStringAnnotations("URL", 0, it.length).map { a -> a.item } })
    }
    @Test fun browserActionOpensOnlyExactCompatibleAsset() {
        val update = (check(fixture()) as UpdateStatus.Available).update
        val urls = mutableListOf<String>()
        assertTrue(launchUpdateDownload(update, installed) { urls += it; true })
        assertEquals(listOf(update.asset.url), urls)
        assertFalse(launchUpdateDownload(update, installed.copy(variant = "foss")) { fail(); true })
        assertFalse(launchUpdateDownload(update, installed.copy(code = 79)) { fail(); true })
        assertFalse(launchUpdateDownload(update.copy(asset = update.asset.copy(url = "https://evil.example/file.apk")), installed) { fail(); true })
        assertFalse(launchUpdateDownload(update, installed) { false }) // no browser
    }
    @Test fun betaTitleUsesPublishedName() { assertEquals("6.1.0 Beta 2", releaseTitle("6.1.0-beta.2")) }

    private class Store : UpdateStore {
        override var lastSuccess = 0L
        override var availableCode = 0L
        override var notifiedCode = 0L
        override var retryAt = 0L
        override var lastBeta: Boolean? = null
    }
    @Test fun successfulTimePersistsAndFailureDoesNotChangeIt() = runBlocking {
        val store = Store(); val transport = Transport(listOf(fixture())); var time = 1_000_000L
        val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, store, { false }, { time })
        checker.check(force = true)
        assertEquals(time, store.lastSuccess); assertEquals(79, store.availableCode)
        time += 60_000
        transport.failure = UpdateFailure(UpdateProblem.NETWORK)
        checker.check(force = true)
        assertEquals(UpdateStatus.Error(UpdateProblem.NETWORK), checker.state.value)
        assertEquals(1_000_000, store.lastSuccess)
        assertEquals(1_000_000, UpdateCoordinator(GitHubReleaseSource(transport), installed, store, { false }).lastSuccess.value)
    }
    @Test fun rateLimitAndInvalidMetadataDoNotStampSuccess() = runBlocking {
        for (problem in listOf(UpdateProblem.RATE_LIMIT, UpdateProblem.INVALID_RELEASE, UpdateProblem.HTTP)) {
            val transport = Transport(listOf(fixture())).apply { failure = UpdateFailure(problem) }
            val store = Store(); val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, store, { false }, { 1_000_000 })
            checker.check(force = true)
            assertEquals(UpdateStatus.Error(problem), checker.state.value); assertEquals(0, store.lastSuccess)
            if (problem == UpdateProblem.RATE_LIMIT) { assertTrue(store.retryAt > 1_000_000); checker.check(force = true); assertEquals(1, transport.requests) }
        }
    }
    @Test fun noReleasesIsSuccessfulAndClearsStaleIndicator() = runBlocking {
        val store = Store().apply { availableCode = 80 }; var cancelled = 0
        val checker = UpdateCoordinator(GitHubReleaseSource(Transport(emptyList())), installed, store, { false }, { 1_000_000 }, cancelNotification = { cancelled++ })
        checker.check(force = true)
        assertEquals(UpdateStatus.NoPublishedReleases, checker.state.value); assertEquals(0, store.availableCode); assertEquals(1_000_000, store.lastSuccess); assertEquals(1, cancelled)
    }
    @Test fun notificationDeduplicatesAcrossRestartsAndInstalledUpdateClearsIt() = runBlocking {
        val store = Store(); var posted = 0; var cleared = 0; var time = 1_000_000L
        fun checker(code: Long = 78) = UpdateCoordinator(GitHubReleaseSource(Transport(listOf(fixture()))), installed.copy(code = code), store,
            { false }, { time }, { posted++; true }, { cleared++ })
        checker().check(force = true, automatic = true)
        time += 13 * 60 * 60_000L
        checker().check(force = true, automatic = true)
        assertEquals(1, posted)
        checker(79).check(force = true)
        assertEquals(0, store.availableCode); assertTrue(cleared > 0)
    }
    @Test fun automaticChecksThrottledButManualCheckAlwaysFresh() = runBlocking {
        val store = Store(); val transport = Transport(listOf(fixture()))
        fun checker() = UpdateCoordinator(GitHubReleaseSource(transport), installed, store, { false }, { 1_000_000 })
        checker().check(automatic = true); val count = transport.requests
        checker().check(automatic = true); assertEquals(count, transport.requests)
        checker().check(force = true); assertTrue(transport.requests > count)
    }
    @Test fun duplicateChecksShareOneRequestAndCancellationResetsState() = runBlocking {
        val started = CompletableDeferred<Unit>(); val blocked = CompletableDeferred<Unit>(); var calls = 0
        val transport = ReleaseTransport { calls++; started.complete(Unit); blocked.await(); "[]" }
        val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, Store(), { false })
        val job = launch { checker.check(force = true) }; started.await()
        assertEquals(UpdateStatus.Checking, checker.state.value)
        checker.check(force = true); assertEquals(1, calls)
        job.cancelAndJoin(); assertEquals(UpdateStatus.Idle, checker.state.value)
    }
    @Test fun changedBetaPolicyRefreshesSelection() = runBlocking {
        val transport = Transport(listOf(fixture(code = 78), fixture("6.1.0-beta.1", 80))); var beta = true
        val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, Store(), { beta })
        checker.check(force = true); assertTrue(checker.state.value is UpdateStatus.Available)
        beta = false; checker.invalidateChannel(); assertEquals(UpdateStatus.Idle, checker.state.value)
        checker.check(force = true); assertEquals(UpdateStatus.UpToDate, checker.state.value)
    }
    @Test fun manualChecksWorkWithAutomaticChecksDisabled() = runBlocking {
        val transport = Transport(listOf(fixture()))
        val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, Store(), { false }, automaticEnabled = { false })
        checker.check(automatic = true); assertEquals(0, transport.requests); assertEquals(UpdateStatus.Idle, checker.state.value)
        checker.check(force = true); assertTrue(checker.state.value is UpdateStatus.Available)
    }
    @Test fun viewingReleaseMarksItSeenWithoutRepeatNotification() = runBlocking {
        val store = Store(); var posted = 0; var time = 1_000_000L
        val transport = Transport(listOf(fixture()))
        val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, store, { false }, { time }, notify = { posted++; true })
        checker.check(force = true); checker.markSeen(); assertEquals(79L, store.notifiedCode)
        time += 13 * 60 * 60_000L
        checker.check(force = true, automatic = true); assertEquals(0, posted); assertEquals(4, transport.requests)
    }
    @Test fun http403And429RespectServerRetryTime() {
        assertEquals(UpdateProblem.RATE_LIMIT, httpFailure(403, "2000", null, 1_000_000)!!.problem)
        assertEquals(2_000_000L, httpFailure(429, "2000", "60", 1_000_000)!!.retryAt)
        assertEquals(UpdateProblem.HTTP, httpFailure(503, null, null, 0)!!.problem)
        assertNull(httpFailure(200, null, null, 0))
    }
    @Test fun allPagesAreReadInsteadOfPublicationTimeSorting() = runBlocking {
        var requests = 0
        val draft = fixture().releaseField("draft", JsonPrimitive(true)).release
        val f = fixture()
        val transport = ReleaseTransport { url ->
            requests++
            when {
                url.endsWith("page=1") -> JsonArray(List(100) { draft }).toString()
                url.endsWith("page=2") -> JsonArray(listOf(f.release)).toString()
                else -> f.metadata.toString()
            }
        }
        assertTrue(GitHubReleaseSource(transport).check(installed, false) is UpdateStatus.Available)
        assertEquals(3, requests)
    }
    @Test fun malformedMetadataDoesNotAdvancePreviousSuccessfulTime() = runBlocking {
        val store = Store().apply { lastSuccess = 1000 }
        val transport = Transport(listOf(fixture())).apply { invalidMetadata = true }
        val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, store, { false })
        checker.check(force = true); assertEquals(1000L, store.lastSuccess); assertTrue(checker.state.value is UpdateStatus.Error)
    }

    @Test fun highestCompatibleCodeWinsWhenNewerReleaseRequiresNewerAndroid() {
        val newer = fixture("6.2.0", 82)
        val assets = newer.metadata.getValue("assets").jsonArray.map { it.jsonObject.change("minSdk", JsonPrimitive(38)).change("targetSdk", JsonPrimitive(38)) }
        val unavailable = newer.metadataField("assets", JsonArray(assets))
        assertEquals(79L, (check(fixture(), unavailable) as UpdateStatus.Available).update.versionCode)
    }

    @Test fun missingDeclaredNotesAssetIsInconsistent() {
        val f = fixture(); invalid(f.releaseField("assets", JsonArray(f.release.getValue("assets").jsonArray.filterNot { it.jsonObject["name"]?.jsonPrimitive?.content == "release-notes.md" })))
    }
    @Test fun failedAutomaticAttemptsAreThrottled() = runBlocking {
        val transport = Transport(emptyList()).apply { failure = UpdateFailure(UpdateProblem.NETWORK) }
        val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, Store(), { false }, { 1_000_000 })
        checker.check(automatic = true); checker.check(automatic = true)
        assertEquals(1, transport.requests)
        checker.check(force = true); assertEquals(2, transport.requests)
    }
    @Test fun channelChangeDuringAnInvalidOldResponseStillChecksNewChannel() = runBlocking {
        var beta = true
        val started = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        val stable = fixture(); val prerelease = fixture("6.1.0-beta.1", 80)
        val normal = Transport(listOf(stable, prerelease))
        val transport = ReleaseTransport { url ->
            if ("6.1.0-beta.1/release.json" in url) { started.complete(Unit); proceed.await(); throw UpdateFailure(UpdateProblem.INVALID_RELEASE) }
            normal.get(url)
        }
        val store = Store(); val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, store, { beta })
        val job = launch { checker.check(force = true) }; started.await()
        beta = false; checker.invalidateChannel(); proceed.complete(Unit); job.join()
        assertEquals("stable", (checker.state.value as UpdateStatus.Available).update.channel)
        assertEquals(false, store.lastBeta)
    }
    @Test fun overallTimeoutIsRecoverableAndDoesNotStampSuccess() = runBlocking {
        val store = Store(); val transport = ReleaseTransport { awaitCancellation() }
        val checker = UpdateCoordinator(GitHubReleaseSource(transport), installed, store, { false }, timeoutMillis = 10)
        checker.check(force = true)
        assertEquals(UpdateStatus.Error(UpdateProblem.NETWORK), checker.state.value)
        assertEquals(0L, store.lastSuccess)
    }

}
