package com.music.vivi.vivimusic.updater

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.WorkManager
import com.music.vivi.BuildConfig
import com.music.vivi.vivimusic.UpdateNotificationHelper
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val PREFS_NAME = "settings"
const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
const val KEY_BETA_UPDATES = "beta_updates"
const val KEY_UPDATE_AVAILABLE = "update_available"

fun installedVersion(context: Context): InstalledVersion {
    val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    return InstalledVersion(info.versionName.orEmpty(), code, if (BuildConfig.CAST_AVAILABLE) "gms" else "foss", Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
}

private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
fun getAutoUpdateCheckSetting(context: Context) = prefs(context).getBoolean(KEY_AUTO_UPDATE_CHECK, true)
fun saveAutoUpdateCheckSetting(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_CHECK, enabled).apply() }
fun getBetaUpdatesSetting(context: Context) = prefs(context).getBoolean(KEY_BETA_UPDATES, false)
fun saveBetaUpdatesSetting(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_BETA_UPDATES, enabled).apply()
    TiniumUpdater.channelChanged(context)
}
fun getUpdateAvailableState(context: Context): Boolean =
    prefs(context).getLong("tinium_available_code", 0) > installedVersion(context).code
fun getUpdateNotificationsSetting(context: Context) = prefs(context).getBoolean("update_notifications", true)
fun saveUpdateNotificationsSetting(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean("update_notifications", enabled).apply()
    if (!enabled) UpdateNotificationHelper.cancel(context)
}

private class PreferencesUpdateStore(private val preferences: SharedPreferences) : UpdateStore {
    override var lastSuccess: Long
        get() = preferences.getLong("tinium_last_success_ms", 0)
        set(value) { preferences.edit().putLong("tinium_last_success_ms", value).apply() }
    override var availableCode: Long
        get() = preferences.getLong("tinium_available_code", 0)
        set(value) { preferences.edit().putLong("tinium_available_code", value).putBoolean(KEY_UPDATE_AVAILABLE, value > 0).apply() }
    override var notifiedCode: Long
        get() = preferences.getLong("tinium_notified_code", 0)
        set(value) { preferences.edit().putLong("tinium_notified_code", value).apply() }
    override var retryAt: Long
        get() = preferences.getLong("tinium_retry_at", 0)
        set(value) { preferences.edit().putLong("tinium_retry_at", value).apply() }
    override var lastBeta: Boolean?
        get() = if (preferences.contains("tinium_last_beta")) preferences.getBoolean("tinium_last_beta", false) else null
        set(value) { preferences.edit().apply { if (value == null) remove("tinium_last_beta") else putBoolean("tinium_last_beta", value) }.apply() }
}

/** Only a small public JSON document is fetched, never an APK. No authentication header. */
internal class OkHttpReleaseTransport(context: Context) : ReleaseTransport {
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "tinium-release-http"), 5L * 1024 * 1024))
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()

    override suspend fun get(url: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Tinium-update-checker")
            // Revalidate HTTP cache so edited release bodies are visible on the next successful check.
            .header("Cache-Control", "no-cache").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(UpdateFailure(UpdateProblem.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        httpFailure(response.code, response.header("X-RateLimit-Reset"), response.header("Retry-After"), System.currentTimeMillis())?.let { throw it }
                        val maximum = if (url.startsWith(GitHubReleaseSource.API)) 8 * 1024 * 1024 else 50_000
                        val body = response.body
                        require(body.contentLength() <= maximum)
                        val bytes = body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (output.size() <= maximum) {
                                val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
                                if (count == -1) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        require(bytes.size <= maximum)
                        if (!continuation.isCancelled) continuation.resume(bytes.toString(Charsets.UTF_8))
                    } catch (e: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(
                            if (e is UpdateFailure) e else UpdateFailure(if (e is IOException) UpdateProblem.NETWORK else UpdateProblem.INVALID_RELEASE))
                    }
                }
            }
        })
    }
}

object TiniumUpdater {
    @Volatile private var instance: UpdateCoordinator? = null
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun get(context: Context): UpdateCoordinator = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let { app ->
            // Retire persisted jobs from earlier Tinium versions. Music download work is untouched.
            WorkManager.getInstance(app).cancelUniqueWork("update_download")
            androidx.core.app.NotificationManagerCompat.from(app).cancel(5678) // retired APK download notification
            UpdateCoordinator(GitHubReleaseSource(OkHttpReleaseTransport(app)), installedVersion(app), PreferencesUpdateStore(prefs(app)),
                { getBetaUpdatesSetting(app) }, notify = {
                    getUpdateNotificationsSetting(app) && UpdateNotificationHelper.showUpdateNotification(app, it.versionName)
                }, cancelNotification = { UpdateNotificationHelper.cancel(app) }, automaticEnabled = { getAutoUpdateCheckSetting(app) })
                .also { instance = it }
        }
    }
    fun channelChanged(context: Context) {
        val checker = get(context)
        checker.invalidateChannel()
        settingsScope.launch { checker.check(force = true) }
    }
    suspend fun automaticCheck(context: Context) {
        if (getAutoUpdateCheckSetting(context)) get(context).check(automatic = true)
    }
}

/** Download action has no WorkManager, file, installer, or permission side effects. */
fun openUpdateInBrowser(context: Context, update: TiniumUpdate): Boolean {
    return launchUpdateDownload(update, installedVersion(context)) { openHttpsInBrowser(context, it) }
}

fun openHttpsInBrowser(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}
