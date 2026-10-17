/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("DEPRECATION")

package com.music.vivi.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.SQLException
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.pages.RadioChip
import com.music.lastfm.LastFM
import com.music.vivi.MainActivity
import com.music.vivi.R
import com.music.vivi.constants.AudioNormalizationKey
import com.music.vivi.constants.AudioOffload
import com.music.vivi.constants.AudioQualityKey
import com.music.vivi.constants.AutoDownloadOnLikeKey
import com.music.vivi.constants.AutoLoadMoreKey
import com.music.vivi.constants.AutoSkipNextOnErrorKey
import com.music.vivi.constants.CrossfadeCurve
import com.music.vivi.constants.CrossfadeCurveKey
import com.music.vivi.constants.CrossfadeDurationKey
import com.music.vivi.constants.CrossfadeEnabledKey
import com.music.vivi.constants.CrossfadeGaplessKey
import com.music.vivi.constants.CrossfadeManualSkipKey
import com.music.vivi.constants.AutoMixEnabledKey
import com.music.vivi.constants.AutoMixPerformanceKey
import com.music.vivi.playback.automix.*
import androidx.media3.datasource.DataSpec
import com.music.vivi.constants.DisableLoadMoreWhenRepeatAllKey
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.music.vivi.constants.DiscordActivityNameKey
import com.music.vivi.constants.DiscordActivityTypeKey
import com.music.vivi.constants.DiscordAdvancedModeKey
import com.music.vivi.constants.DiscordButton1TextKey
import com.music.vivi.constants.DiscordButton1VisibleKey
import com.music.vivi.constants.DiscordButton2TextKey
import com.music.vivi.constants.DiscordButton2VisibleKey
import com.music.vivi.constants.DiscordStatusKey
import com.music.vivi.constants.DiscordTokenKey
import com.music.vivi.constants.DiscordUseDetailsKey
import com.music.vivi.constants.EnableDiscordRPCKey
import com.music.vivi.constants.EnableLastFMScrobblingKey
import com.music.vivi.constants.HideExplicitKey
import com.music.vivi.constants.HideVideoSongsKey
import com.music.vivi.constants.HistoryDuration
import com.music.vivi.constants.LastFMUseNowPlaying
import com.music.vivi.constants.MediaSessionConstants.CommandToggleLike
import com.music.vivi.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.music.vivi.constants.MediaSessionConstants.CommandToggleShuffle
import com.music.vivi.constants.MediaSessionConstants.CommandToggleStartRadio
import com.music.vivi.constants.PauseListenHistoryKey
import com.music.vivi.constants.PauseOnMute
import com.music.vivi.constants.PersistentQueueKey
import com.music.vivi.constants.PersistentShuffleAcrossQueuesKey
import com.music.vivi.constants.PlayerVolumeKey
import com.music.vivi.constants.RememberShuffleAndRepeatKey
import com.music.vivi.constants.RepeatModeKey
import com.music.vivi.constants.ResumeOnBluetoothConnectKey
import com.music.vivi.constants.ScrobbleDelayPercentKey
import com.music.vivi.constants.ScrobbleDelaySecondsKey
import com.music.vivi.constants.ScrobbleMinSongDurationKey
import com.music.vivi.constants.ShowLyricsKey
import com.music.vivi.constants.ShuffleModeKey
import com.music.vivi.constants.ShufflePlaylistFirstKey
import com.music.vivi.constants.PreventDuplicateTracksInQueueKey
import com.music.vivi.constants.SimilarContent
import com.music.vivi.constants.SkipSilenceInstantKey
import com.music.vivi.constants.SkipSilenceKey
import com.music.vivi.constants.EnableSponsorBlockKey
import com.music.vivi.constants.SponsorBlockServerUrlKey
import com.music.vivi.constants.SponsorBlockSkipNonMusicKey
import com.music.vivi.constants.SponsorBlockSkipSponsorKey
import com.music.vivi.constants.SponsorBlockSkipSelfPromoKey
import com.music.vivi.constants.SponsorBlockSkipInteractionKey
import com.music.vivi.constants.SponsorBlockSkipIntroOutroKey
import com.music.vivi.constants.SponsorBlockSkipPreviewFillerKey
import com.music.vivi.constants.SponsorBlockShowToastKey
import com.music.vivi.sponsorblock.SponsorBlockApi
import com.music.vivi.sponsorblock.SponsorSegment
import com.music.vivi.constants.IpVersionKey
import com.music.innertube.models.IpVersion
import okhttp3.Dns
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.Event
import com.music.vivi.db.entities.FormatEntity
import com.music.vivi.db.entities.LyricsEntity
import com.music.vivi.db.entities.RelatedSongMap
import com.music.vivi.db.entities.Song
import com.music.vivi.di.DownloadCache
import com.music.vivi.di.PlayerCache
import com.music.vivi.eq.EqualizerService
import com.music.vivi.eq.audio.CustomEqualizerAudioProcessor
import com.music.vivi.eq.data.EQProfileRepository
import com.music.vivi.extensions.SilentHandler
import com.music.vivi.extensions.collect
import com.music.vivi.extensions.collectLatest
import com.music.vivi.extensions.currentMetadata
import com.music.vivi.extensions.findNextMediaItemById
import com.music.vivi.extensions.mediaItems
import com.music.vivi.extensions.metadata
import com.music.vivi.extensions.setOffloadEnabled
import com.music.vivi.extensions.toEnum
import com.music.vivi.extensions.toMediaItem
import com.music.vivi.extensions.toPersistQueue
import com.music.vivi.extensions.toQueue
import com.music.vivi.lyrics.LyricsHelper
import com.music.vivi.models.PersistPlayerState
import com.music.vivi.models.PersistQueue
import com.music.vivi.models.toMediaMetadata
import com.music.vivi.playback.audio.SilenceDetectorAudioProcessor
import com.music.vivi.playback.queues.EmptyQueue
import com.music.vivi.playback.queues.ListQueue
import com.music.vivi.playback.queues.Queue
import com.music.vivi.playback.queues.YouTubeQueue
import com.music.vivi.playback.queues.filterExplicit
import com.music.vivi.playback.queues.filterVideoSongs
import com.music.vivi.utils.CoilBitmapLoader
import com.music.vivi.utils.DiscordRPC
import com.music.vivi.utils.InnerTubeXPlayer
import com.music.vivi.utils.NetworkConnectivityObserver
import com.music.vivi.utils.ScrobbleManager
import com.music.vivi.utils.SyncUtils
import com.music.vivi.utils.YTPlayerUtils
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get
import com.music.vivi.utils.reportException
import com.music.vivi.widget.vivimusicWidgetManager
import com.music.vivi.widget.MusicWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

private const val INSTANT_SILENCE_SKIP_STEP_MS = 15_000L
private const val INSTANT_SILENCE_SKIP_SETTLE_MS = 350L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var equalizerService: EqualizerService

    @Inject
    lateinit var eqProfileRepository: EQProfileRepository

    @Inject
    lateinit var widgetManager: vivimusicWidgetManager

    @Inject
    lateinit var listenTogetherManager: com.music.vivi.listentogether.ListenTogetherManager

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var hasAudioFocus = false
    private var reentrantFocusGain = false
    private var wasPlayingBeforeVolumeMute = false
    private var isPausedByVolumeMute = false
    var preferredDeviceId: Int? = null //added for audio device switching
        private set//improvement

    private var crossfadeEnabled = false
    private var crossfadeDuration = 5000f
    private var crossfadeGapless = true
    private var crossfadeManualSkipEnabled = false
    private var crossfadeCurve = CrossfadeCurve.EASE_OUT_QUAD
    private var crossfadeTriggerJob: Job? = null
    @Volatile private var autoMixEngineMode = AutoMixEngineMode.LEGACY
    private var nativeAutoMix:NativeAutoMixServiceController<AnalysisSource>?=null
    private var nativePlayback:Media3NativeDjPlayback?=null
    private var nativePromoting=false
    private val analysisConcurrencyBudget=AnalysisConcurrencyBudget()
    private var nativeForwardingPlayback=false
    private var nativePerformance=AutoMixPerformance.BALANCED
    private var nativeStartup:NativeDjStartupObservation?=null
    private var nativeStartCommand:Triple<ExoPlayer,Long,Long>?=null
    private var nativePairGeneration=0L
    private var nativePairKey:NativeQueueBinding?=null
    private val nativeSources=NativeRenditionRegistry<AnalysisSource>()
    private var autoMixRequested = false
    private var autoMixFocusGain = 1f
    val autoMixRecorder = com.music.vivi.playback.automix.AutoMixRecorder()
    private val _autoMixStatus = MutableStateFlow(com.music.vivi.playback.automix.AutoMixStatus())
    val autoMixStatus: kotlinx.coroutines.flow.StateFlow<com.music.vivi.playback.automix.AutoMixStatus> = _autoMixStatus.asStateFlow()
    private val _audioDiagnostics = MutableStateFlow(AudioDiagnostics())
    val audioDiagnostics: kotlinx.coroutines.flow.StateFlow<AudioDiagnostics> = _audioDiagnostics.asStateFlow()
    private val outputFormats = mutableMapOf<ExoPlayer, AudioDiagnostics>()
    private val formatTrackIds = mutableMapOf<ExoPlayer, String?>()
    private val diagnosticTrackCounts = mutableMapOf<ExoPlayer, Int>()
    private var autoMixResolveNextId: String? = null
    private var autoMixResolveRetryAt = 0L
    private var intelligentAutoMix: AutoMixController? = null
    private var legacyAutoMixEnabled=false
    private var nativeFallbackReason:String?=null
    private var autoMixTicker: Job? = null
    private var autoMixQueueEpoch = 0L
    private val autoMixProcessors = HashMap<ExoPlayer, AutoMixAudioProcessor>()
    private val playerEqProcessors = HashMap<ExoPlayer, CustomEqualizerAudioProcessor>()
    private val autoMixDecks = mutableMapOf<ExoPlayer, ServiceMixDeck>()
    private val analysisDataSourceFactory by lazy { createCacheDataSource(writeCache = false) }

    /** Holds the combined crossfade-related settings emitted from DataStore. */
    private data class CrossfadeSettings(
        val enabled: Boolean,
        val durationSeconds: Float,
        val gapless: Boolean,
        val manualSkip: Boolean,
        val curve: CrossfadeCurve,
        val autoMix: Boolean,
        val performance: AutoMixPerformance,
        val engineMode:AutoMixEngineMode,
    )

    /**
     * Distinguishes *why* a crossfade transition is starting so [startCrossfade]
     * knows which media item to target (natural end-of-track vs. a manual
     * next/previous press).
     */
    private enum class CrossfadeTrigger {
        AUTO,
        MANUAL_NEXT,
        MANUAL_PREVIOUS,
    }

    private val secondaryPlayerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Timber.tag(TAG).e(error, "Secondary player error")
            secondaryPlayer?.let { pending ->
                pending.removeListener(this)
                autoMixDecks.remove(pending)
                playerEqProcessors.remove(pending)?.let(equalizerService::removeAudioProcessor)
                playerSilenceProcessors.remove(pending)
                autoMixProcessors.remove(pending)
                pending.stop()
                pending.clearMediaItems()
                releaseAudioOutput(pending)
            }
            secondaryPlayer = null
        }
    }

    private var scope = CoroutineScope(Dispatchers.Main) + Job()
    private var serviceDestroyed = false

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private lateinit var audioQuality: com.music.vivi.constants.AudioQuality
    private lateinit var ipVersion: IpVersion

    var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null
    val radioChips = MutableStateFlow<List<RadioChip>>(emptyList())
    private var radioChipsJob: Job? = null

    val currentMediaMetadata = MutableStateFlow<com.music.vivi.models.MediaMetadata?>(null)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    lateinit var playerVolume: MutableStateFlow<Float>
    val isMuted = MutableStateFlow(false)

    fun toggleMute() {
        val newMutedState = !isMuted.value
        isMuted.value = newMutedState
        // Immediately update player volume to ensure it takes effect
        player.volume = if (newMutedState) 0f else playerVolume.value
        autoMixDecks.values.forEach { it.applyUserVolume() }
        nativeAutoMix?.volumeChanged()
    }

    fun setMuted(muted: Boolean) {
        isMuted.value = muted
        // Immediately update player volume to ensure it takes effect
        // This handles cases where the player reference may have changed
        player.volume = if (muted) 0f else playerVolume.value
        autoMixDecks.values.forEach { it.applyUserVolume() }
        nativeAutoMix?.volumeChanged()
    }

    fun setPreferredAudioDevice(deviceId: Int?) {
        invalidateNative(NativeDjExecutionReason.ROUTE_CHANGED) // this helps us to change between devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val deviceInfo = devices.find { it.id == deviceId }
            outputIdentities.keys.toList().forEach { it.setPreferredAudioDevice(deviceInfo) }
            Timber.tag("TiniumAudioSession").i("event=preferred-route type=%s",deviceInfo?.type)
            preferredDeviceId = deviceId
        }
    }
//

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
        private set
    private var secondaryPlayer: ExoPlayer? = null
    private var fadingPlayer: ExoPlayer? = null
    private var crossfadeTargetId: String? = null
    private var isCrossfading = false
    private var crossfadeJob: Job? = null

    private lateinit var mediaSession: MediaLibrarySession

    // Tracks if player has been properly initilized
    private val playerInitialized = MutableStateFlow(false)
    val isPlayerReady: kotlinx.coroutines.flow.StateFlow<Boolean> = playerInitialized.asStateFlow()

    // Expose active player flow for UI/Connection updates
    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow = _playerFlow.asStateFlow()

    private val playerSilenceProcessors = HashMap<Player, SilenceDetectorAudioProcessor>()


    private val instantSilenceSkipEnabled = MutableStateFlow(false)

    private var outputSequence = 0
    private val outputIdentities = mutableMapOf<ExoPlayer, String>()
    private val outputListeners = mutableMapOf<ExoPlayer, Player.Listener>()
    private val outputLifecycles = mutableMapOf<ExoPlayer, com.music.vivi.playback.effects.AudioOutputLifecycle>()
    private val effectSessions = com.music.vivi.playback.effects.AudioEffectSessions { event ->
        Timber.tag("TiniumAudioSession").i("event=%s deck=%s session=%d role=%s", event.kind, event.deck, event.sessionId, event.role)
        if (event.kind == com.music.vivi.playback.effects.AudioEffectSessions.Kind.OPEN ||
            event.kind == com.music.vivi.playback.effects.AudioEffectSessions.Kind.CLOSE) {
            sendBroadcast(Intent(if (event.kind == com.music.vivi.playback.effects.AudioEffectSessions.Kind.OPEN)
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, event.sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
        }
    }
    private var loudnessEnhancerSessionId = 0
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var discordRpc: DiscordRPC? = null
    private var lastPlaybackSpeed = 1.0f
    private var discordUpdateJob: kotlinx.coroutines.Job? = null

    private var scrobbleManager: ScrobbleManager? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())

    /**
     * Emits the video ID of a song immediately after [YouTube.registerPlayback] succeeds.
     * [HistoryViewModel] collects this to auto-refresh remote history without any
     * timing-based delays or tab-state checks.
     */
    val playbackRegistered = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // Tracks the original queue size to distinguish original items from auto-added ones
    private var originalQueueSize: Int = 0

    private var consecutivePlaybackErr = 0
    private var retryJob: Job? = null
    private var retryCount = 0
    private var silenceSkipJob: Job? = null
    /** Background job that pre-resolves the next track's stream URL into [songUrlCache]. */
    private var prefetchJob: Job? = null

    // URL cache for stream URLs - class-level so it can be invalidated on errors
    private val songUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    // Flag to bypass cache when quality changes - forces fresh stream fetch
    private val bypassCacheForQualityChange = mutableSetOf<String>()

    // Enhanced error tracking for strict retry management
    private var currentMediaIdRetryCount = mutableMapOf<String, Int>()
    private val MAX_RETRY_PER_SONG = 3
    private val RETRY_DELAY_MS = 1000L

    // Track failed songs to prevent infinite retry loops
    private val recentlyFailedSongs = mutableSetOf<String>()
    private var failedSongsClearJob: Job? = null

    // Google Cast support
    var castConnectionHandler: CastConnectionHandler? = null
        private set

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (!player.isPlaying) {
                        scope.launch(Dispatchers.IO) {
                            discordRpc?.closeRPC()
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (player.isPlaying) {
                        scope.launch {
                            currentSong.value?.let { song ->
                                updateDiscordRPC(song)
                            }
                        }
                    }
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            invalidateNative(NativeDjExecutionReason.ROUTE_CHANGED)
            Timber.tag("TiniumAudioSession").i("event=route-removed types=%s", removedDevices?.map { it.type })
        }
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesAdded(addedDevices)
            invalidateNative(NativeDjExecutionReason.ROUTE_CHANGED)
            Timber.tag("TiniumAudioSession").i("event=route-added types=%s", addedDevices?.map { it.type })
            val hasBluetooth = addedDevices?.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } == true

            if (hasBluetooth) {
                if (dataStore.get(ResumeOnBluetoothConnectKey, false)) {
                    if (player.playbackState == Player.STATE_READY && !player.isPlaying) {
                        player.play()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Player rediness reset to false
        playerInitialized.value = false

        // 3. Connect the processor to the service
        // handled in createExoPlayer

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.music_player),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            val pending = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.music_player))
                .setContentText("")
                .setSmallIcon(R.drawable.vivimusicnotification)  //vivimusicnotification
                .setContentIntent(pending)
                .setOngoing(true)
                .build().let { notification ->
                    Notification.Builder.recoverBuilder(this, notification)
                        .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.tinium_logo_background))
                        .build()
                }
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create foreground notification")
            reportException(e)
        }

        setMediaNotificationProvider(
            BrandedMediaNotificationProvider(this, DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.vivimusicnotification)
                }),
        )
        player = createExoPlayer()
        player.addListener(this@MusicService)
        sleepTimer = SleepTimer(scope, player)
        player.addListener(sleepTimer)

        // Mark player as initialized after successful creation
        playerInitialized.value = true
        Timber.tag(TAG).d("Player successfully initialized")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocusRequest()

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Restore shuffle mode if remember option is enabled
        if (dataStore.get(RememberShuffleAndRepeatKey, true)) {
            player.shuffleModeEnabled = dataStore.get(ShuffleModeKey, false)
        }

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        val screenStateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, screenStateFilter)

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        audioQuality = dataStore.get(AudioQualityKey).toEnum(com.music.vivi.constants.AudioQuality.AUTO)
        ipVersion = dataStore.get(IpVersionKey).toEnum(IpVersion.AUTO)
        playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

        // Initialize Google Cast
        initializeCast()

        // 4. Watch for EQ profile changes
        scope.launch {
            eqProfileRepository.activeProfile.collect { profile ->
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    if (result.isSuccess && player.playbackState == Player.STATE_READY && player.isPlaying) {
                        // Instant update: flush buffers and seek slightly to re-process audio
                        // Small seek to force re-buffer through the new EQ settings
                        // Seek to current position effectively resets the pipeline
                        player.seekTo(player.currentPosition)
                    }
                } else {
                    equalizerService.disable()
                    if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                        player.seekTo(player.currentPosition)
                    }
                }
            }
        }

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    triggerRetry()
                }
                // Update Discord RPC when network becomes available
                if (isConnected && discordRpc != null && player.isPlaying) {
                    val mediaId = player.currentMetadata?.id
                    if (mediaId != null) {
                        database.song(mediaId).first()?.let { song ->
                            updateDiscordRPC(song)
                        }
                    }
                }
            }
        }

        // Watch for audio quality setting changes
        var isFirstQualityEmit = true
        scope.launch {
            dataStore.data
                .map { it[AudioQualityKey]?.let { value ->
                    com.music.vivi.constants.AudioQuality.entries.find { it.name == value }
                } ?: com.music.vivi.constants.AudioQuality.AUTO }
                .distinctUntilChanged()
                .collect { newQuality ->
                    val oldQuality = audioQuality
                    audioQuality = newQuality

                    // Skip reload on first emit (app startup)
                    if (isFirstQualityEmit) {
                        isFirstQualityEmit = false
                        Timber.tag("MusicService").i("QUALITY INIT: $newQuality")
                        return@collect
                    }

                    invalidateNative(NativeDjExecutionReason.SOURCE_MISMATCH);nativeSources.clear()
                    Timber.tag("MusicService").i("QUALITY CHANGED: $oldQuality -> $newQuality")

                    // Reload current song with new quality
                    val mediaId = player.currentMediaItem?.mediaId ?: return@collect
                    val currentPosition = player.currentPosition
                    val wasPlaying = player.isPlaying
                    val currentIndex = player.currentMediaItemIndex

                    Timber.tag("MusicService").i("RELOADING STREAM: $mediaId at position ${currentPosition}ms")

                    // Clear cached URL to force fresh fetch
                    songUrlCache.remove(mediaId)

                    // CRITICAL: Clear caches synchronously to prevent format parsing errors
                    runBlocking(Dispatchers.IO) {
                        try {
                            playerCache.removeResource(mediaId)
                            downloadCache.removeResource(mediaId)
                            Timber.tag("MusicService").d("Cleared player and download cache for $mediaId")
                        } catch (e: Exception) {
                            Timber.tag("MusicService").e(e, "Failed to clear cache for $mediaId")
                        }
                    }

                    // Set bypass flag so resolver skips cache checks
                    bypassCacheForQualityChange.add(mediaId)
                    Timber.tag("MusicService").d("Set bypass cache flag for $mediaId")

                    // Reload player at same position
                    player.stop()
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                    }
                }
        }

        // Watch for IP version changes
        scope.launch {
            dataStore.data
                .map { it[IpVersionKey]?.toEnum(IpVersion.AUTO) ?: IpVersion.AUTO }
                .distinctUntilChanged()
                .collect { newIpVersion ->
                    val oldIpVersion = ipVersion
                    ipVersion = newIpVersion

                    if (isFirstQualityEmit) return@collect

                    Timber.tag("MusicService").i("IP VERSION CHANGED: $oldIpVersion -> $newIpVersion")

                    // Reload player to apply new DNS filter
                    val mediaId = player.currentMediaItem?.mediaId ?: return@collect
                    val currentPosition = player.currentPosition
                    val currentIndex = player.currentMediaItemIndex
                    val wasPlaying = player.isPlaying

                    // Clear cached URL
                    songUrlCache.remove(mediaId)

                    // Reload player
                    player.stop()
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                    }
                }
        }

        combine(playerVolume, isMuted) { volume, muted ->
            if (muted) 0f else volume
        }.collectLatest(scope) {
            player.volume = it
            autoMixDecks.values.forEach { deck -> deck.applyUserVolume() }
            nativeAutoMix?.volumeChanged()
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            updateWidgetUI(player.isPlaying)
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database.lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyricsWithProvider = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyricsWithProvider.lyrics,
                            provider = lyricsWithProvider.provider,
                        ),
                    )
                }
            }
        }

        dataStore.data
            .map { (it[SkipSilenceKey] ?: false) to (it[SkipSilenceInstantKey] ?: false) }
            .distinctUntilChanged()
            .collectLatest(scope) { (skipSilence, instantSkip) ->
                if(skipSilence)invalidateNative(NativeDjExecutionReason.UNSUPPORTED_CAPABILITY)
                player.skipSilenceEnabled = skipSilence
                secondaryPlayer?.skipSilenceEnabled = skipSilence

                val enableInstant = skipSilence && instantSkip
                instantSilenceSkipEnabled.value = enableInstant

                playerSilenceProcessors.values.forEach { processor ->
                    processor.instantModeEnabled = enableInstant
                    if (!enableInstant) {
                        processor.resetTracking()
                    }
                }

                if (!enableInstant) {
                    silenceSkipJob?.cancel()
                }
            }

        // SponsorBlock automatic segment skipping flow
        var sponsorBlockJob: Job? = null
        combine(
            currentMediaMetadata,
            dataStore.data.map { prefs ->
                SponsorBlockConfig(
                    enabled = prefs[EnableSponsorBlockKey] ?: true,
                    serverUrl = prefs[SponsorBlockServerUrlKey] ?: "https://sponsor.ajay.app",
                    skipNonMusic = prefs[SponsorBlockSkipNonMusicKey] ?: true,
                    skipSponsor = prefs[SponsorBlockSkipSponsorKey] ?: true,
                    skipSelfPromo = prefs[SponsorBlockSkipSelfPromoKey] ?: true,
                    skipInteraction = prefs[SponsorBlockSkipInteractionKey] ?: true,
                    skipIntroOutro = prefs[SponsorBlockSkipIntroOutroKey] ?: true,
                    skipPreviewFiller = prefs[SponsorBlockSkipPreviewFillerKey] ?: false,
                    showToast = prefs[SponsorBlockShowToastKey] ?: true
                )
            }.distinctUntilChanged()
        ) { metadata, config ->
            metadata to config
        }.collectLatest(scope) { (metadata, config) ->
            sponsorBlockJob?.cancel()
            sponsorBlockJob = null

            if (!config.enabled || metadata == null) {
                return@collectLatest
            }

            val categories = mutableListOf<String>()
            if (config.skipNonMusic) categories.add("music_offtopic")
            if (config.skipSponsor) categories.add("sponsor")
            if (config.skipSelfPromo) categories.add("selfpromo")
            if (config.skipInteraction) categories.add("interaction")
            if (config.skipIntroOutro) {
                categories.add("intro")
                categories.add("outro")
            }
            if (config.skipPreviewFiller) {
                categories.add("preview")
                categories.add("filler")
            }

            if (categories.isEmpty()) {
                return@collectLatest
            }

            val result = SponsorBlockApi.getSkipSegments(metadata.id, categories, config.serverUrl)
            val segments = result.getOrDefault(emptyList())

            if (segments.isNotEmpty()) {
                sponsorBlockJob = scope.launch {
                    val skippedUuids = mutableSetOf<String>()
                    while (isActive) {
                        if (player.isPlaying) {
                            val currentPos = player.currentPosition
                            val segmentToSkip = segments.find { segment ->
                                currentPos >= segment.startMs && currentPos < (segment.endMs - 300) && !skippedUuids.contains(segment.uuid)
                            }

                            if (segmentToSkip != null) {
                                skippedUuids.add(segmentToSkip.uuid)
                                withContext(Dispatchers.Main) {
                                    player.seekTo(segmentToSkip.endMs)
                                    if (config.showToast) {
                                        val messageRes = when (segmentToSkip.category) {
                                            "music_offtopic" -> R.string.sponsorblock_skipped_non_music
                                            "sponsor" -> R.string.sponsorblock_skipped_sponsor
                                            "selfpromo" -> R.string.sponsorblock_skipped_selfpromo
                                            "interaction" -> R.string.sponsorblock_skipped_interaction
                                            "intro" -> R.string.sponsorblock_skipped_intro
                                            "outro" -> R.string.sponsorblock_skipped_outro
                                            else -> R.string.sponsorblock_skipped_segment
                                        }
                                        Toast.makeText(this@MusicService, getString(messageRes), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        delay(250)
                    }
                }
            }
        }
        // end of sponser block

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
        ) { format, normalizeAudio ->
            format to normalizeAudio
        }.collectLatest(scope) { (format, normalizeAudio) -> setupLoudnessEnhancer()}

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { (it[CrossfadeEnabledKey] ?: false) || (it[AutoMixEnabledKey] ?: false) }
        ) { offloadPref, crossfadeEnabled ->
             // Force disable offload if crossfade is enabled to prevent volume ramp issues
             if (crossfadeEnabled) false else offloadPref
        }.distinctUntilChanged()
        .collectLatest(scope) { useOffload ->
             player.setOffloadEnabled(useOffload && !isCrossfading && intelligentAutoMix?.mixing != true)
             secondaryPlayer?.setOffloadEnabled(useOffload)
        }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (key, enabled) ->
                if (discordRpc?.isRpcRunning() == true) {
                    discordRpc?.closeRPC()
                }
                discordRpc = null
                if (key != null && enabled) {
                    discordRpc = DiscordRPC(this, key)
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentSong.value?.let {
                            updateDiscordRPC(it, true)
                        }
                    }
                }
            }

        // Watch all Discord customization preferences
        dataStore.data
            .map {
                listOf(
                    it[DiscordUseDetailsKey],
                    it[DiscordAdvancedModeKey],
                    it[DiscordStatusKey],
                    it[DiscordButton1TextKey],
                    it[DiscordButton1VisibleKey],
                    it[DiscordButton2TextKey],
                    it[DiscordButton2VisibleKey],
                    it[DiscordActivityTypeKey],
                    it[DiscordActivityNameKey]
                )
            }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) {
                if (player.playbackState == Player.STATE_READY) {
                    currentSong.value?.let { song ->
                        updateDiscordRPC(song, true)
                    }
                }
            }

        dataStore.data
            .map { it[EnableLastFMScrobblingKey] ?: false }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                if (enabled && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration = dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)
                    scrobbleManager = ScrobbleManager(
                        scope,
                        minSongDuration = minSongDuration,
                        scrobbleDelayPercent = delayPercent,
                        scrobbleDelaySeconds = delaySeconds
                    )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!enabled && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS
                )
            }
            .distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        combine(
            dataStore.data.map { prefs ->
                val autoMix = readAutoMixSettings(prefs)
                CrossfadeSettings(
                    enabled = prefs[CrossfadeEnabledKey] ?: false,
                    durationSeconds = prefs[CrossfadeDurationKey] ?: 5f,
                    gapless = prefs[CrossfadeGaplessKey] ?: true,
                    manualSkip = prefs[CrossfadeManualSkipKey] ?: false,
                    curve = prefs[CrossfadeCurveKey].toEnum(CrossfadeCurve.EASE_OUT_QUAD),
                    autoMix = autoMix.enabled,
                    performance = autoMix.performance,
                    engineMode = autoMix.engineMode,
                )
            },
            listenTogetherManager.roomState
        ) { settings, roomState ->
            // Disable crossfade if user is in a listen together room
            settings.copy(enabled = mixingPolicy(settings.autoMix, settings.enabled, false, roomState != null).crossfade)
        }
            .distinctUntilChanged()
            .collect(scope) { settings ->
                if(autoMixEngineMode!=settings.engineMode || autoMixRequested!=settings.autoMix)nativeSources.clear()
                if(autoMixEngineMode!=settings.engineMode || nativePerformance!=settings.performance)
                    invalidateNative(NativeDjExecutionReason.CANCELED)
                autoMixEngineMode=settings.engineMode
                nativePerformance=settings.performance
                analysisConcurrencyBudget.configure(settings.performance)
                autoMixRequested = settings.autoMix
                crossfadeEnabled = settings.enabled
                crossfadeDuration = settings.durationSeconds * 1000f // Convert to ms
                crossfadeGapless = settings.gapless
                crossfadeManualSkipEnabled = settings.manualSkip
                crossfadeCurve = settings.curve
                if (autoMixRequested) {
                    crossfadeTriggerJob?.cancel()
                    crossfadeJob?.cancel()
                    secondaryPlayer?.let { pending ->
                        pending.removeListener(secondaryPlayerListener)
                        playerEqProcessors.remove(pending)?.let(equalizerService::removeAudioProcessor)
                        playerSilenceProcessors.remove(pending)
                        autoMixProcessors.remove(pending)
                        releaseAudioOutput(pending)
                    }
                    secondaryPlayer = null
                    _playerFlow.value = player // canceling a legacy standby must restore the actual UI player
                    if (isCrossfading) {
                        cleanupCrossfade()
                        player.volume = if (isMuted.value) 0f else playerVolume.value
                    }
                    if (intelligentAutoMix == null) intelligentAutoMix = AutoMixController(AutoMixServiceHost(), AnalysisWorkers(budget=analysisConcurrencyBudget), onStatus = { if(legacyAutoMixEnabled || !autoMixRequested)_autoMixStatus.value = it.copy(nativeFallback=nativeFallbackReason) }, recorder=autoMixRecorder)
                }
                if(autoMixRequested && autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL && nativeAutoMix==null) {
                    nativeAutoMix=NativeAutoMixServiceController(NativeServiceHost(),AndroidNativeAnalysis.createPlanningAnalysis(),
                        AnalysisWorkers(settings.performance,analysisConcurrencyBudget),autoMixRecorder)
                }
                nativeAutoMix?.configure(autoMixRequested,autoMixEngineMode,settings.performance)
                if(nativeAutoMix==null) {
                    legacyAutoMixEnabled=autoMixRequested
                    intelligentAutoMix?.configure(autoMixRequested,settings.performance)
                }
                if (playerInitialized.value) setupLoudnessEnhancer()
                if (autoMixRequested && autoMixTicker?.isActive != true) {
                    autoMixTicker = scope.launch {
                        while (isActive && autoMixRequested) {
                            try {
                                if(autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL) {
                                    observeNativeStartup()
                                    nativeAutoMix?.tick()
                                }
                                if(legacyAutoMixEnabled)intelligentAutoMix?.tick()
                            } catch (_: Exception) {
                                nativeAutoMix?.technicalFailure()
                                intelligentAutoMix?.invalidate(com.music.vivi.playback.automix.AutoMixFailure.ANALYSIS_CANCELED)
                                Timber.tag("AutoMix").w("Transition unavailable; retaining normal playback")
                            }
                            delay(if(autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL)
                                minOf(100L,if(legacyAutoMixEnabled)intelligentAutoMix?.nextTickDelayMs() ?: 100L else 100L)
                                else intelligentAutoMix?.nextTickDelayMs() ?: 1000)
                        }
                    }
                } else if (!autoMixRequested) {
                    autoMixTicker?.cancel(); autoMixTicker = null
                }
                scheduleCrossfade()
            }

        if (dataStore.get(PersistentQueueKey, true)) {
            val queueFile = filesDir.resolve(PERSISTENT_QUEUE_FILE)
            if (queueFile.exists()) {
                runCatching {
                    queueFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    runCatching {
                        // Convert back to proper queue type
                        val restoredQueue = queue.toQueue()
                        // Wait for player initialization before playing
                        scope.launch {
                            playerInitialized.first { it }
                            if (isActive) {
                                playQueue(
                                    queue = restoredQueue,
                                    playWhenReady = false,
                                )
                            }
                        }
                    }.onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to restore persisted queue, clearing data")
                        clearPersistedQueueFiles()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read persisted queue, clearing data")
                    clearPersistedQueueFiles()
                }
            }

            val automixFile = filesDir.resolve(PERSISTENT_AUTOMIX_FILE)
            if (automixFile.exists()) {
                runCatching {
                    automixFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    runCatching {
                        automixItems.value = queue.items.map { it.toMediaItem() }
                    }.onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to restore automix queue, clearing data")
                        clearPersistedQueueFiles()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read automix queue, clearing data")
                    clearPersistedQueueFiles()
                }
            }

            // Restore player state
            val playerStateFile = filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE)
            if (playerStateFile.exists()) {
                runCatching {
                    playerStateFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistPlayerState
                        }
                    }
                }.onSuccess { playerState ->
                    // Restore player settings after queue is loaded
                    scope.launch {
                        delay(1000) // Wait for queue to be loaded
                        // Don't restore repeat/shuffle from playerState as they are already set from DataStore (source of truth)
                        // player.repeatMode = playerState.repeatMode
                        // player.shuffleModeEnabled = playerState.shuffleModeEnabled
                        playerVolume.value = playerState.volume

                        // Restore position if it's still valid
                        if (playerState.currentMediaItemIndex < player.mediaItemCount) {
                            player.seekTo(playerState.currentMediaItemIndex, playerState.currentPosition)
                        }

                        // Trigger initial load of radio filter chips for the restored song
                        refreshChipsForCurrentSong()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read player state, clearing data")
                    clearPersistedQueueFiles()
                }
            }
        }

        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                delay(30.seconds)
                if (dataStore.get(PersistentQueueKey, true)) {
                    saveQueueToDisk()
                }
            }
        }

        // Save queue more frequently when playing to ensure state is preserved
        scope.launch {
            while (isActive) {
                delay(10.seconds)
                if (dataStore.get(PersistentQueueKey, true) && player.isPlaying) {
                    saveQueueToDisk()
                }
            }
        }
    }

    private fun createExoPlayer(publish: Boolean = true, autoMixDeck: Boolean = false, pinned:AnalysisSource?=null): ExoPlayer {
        val eqProcessor = CustomEqualizerAudioProcessor()
        equalizerService.addAudioProcessor(eqProcessor)

        val silenceProcessor = SilenceDetectorAudioProcessor { handleLongSilenceDetected() }
        val mixProcessor = AutoMixAudioProcessor()

        // Set initial state
        runBlocking {
            val skipSilence = dataStore.get(SkipSilenceKey, false)
            val instantSkip = dataStore.get(SkipSilenceInstantKey, false)
            silenceProcessor.instantModeEnabled = skipSilence && instantSkip
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(createMediaSourceFactory(pinned))
            .setRenderersFactory(createRenderersFactory(eqProcessor, silenceProcessor, mixProcessor, autoMixDeck))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setDeviceVolumeControlEnabled(true)
            .build()

        playerSilenceProcessors[player] = silenceProcessor
        autoMixProcessors[player] = mixProcessor
        playerEqProcessors[player] = eqProcessor

        player.apply {
                runBlocking {
                    val offload = dataStore.get(AudioOffload, false)
                    val crossfade = dataStore.get(CrossfadeEnabledKey, false)
                    val autoMix = dataStore.get(AutoMixEnabledKey, false)
                    setOffloadEnabled(if (crossfade || autoMix) false else offload)
                    skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                }
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

                // Cleanup handled manually in onDestroy/release
            }
        preferredDeviceId?.let { id ->
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id==id }?.let(player::setPreferredAudioDevice)
        }
        observeAudioOutput(player, publish)
        if (publish) _playerFlow.value = player
        return player
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                handleAudioFocusChange(focusChange)
            }
            .setAcceptsDelayedFocusGain(true)
            .build()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) autoMixFocusGain = 0.2f
        else if (focusChange in listOf(AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)) autoMixFocusGain = 1f
        when (focusChange) {

            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                hasAudioFocus = true

                if (wasPlayingBeforeAudioFocusLoss && !player.isPlaying && !reentrantFocusGain) {
                    reentrantFocusGain = true
                    scope.launch {
                        delay(300)
                        if (hasAudioFocus && wasPlayingBeforeAudioFocusLoss && !player.isPlaying) {
                            // Don't start local playback if casting
                            if (castConnectionHandler?.isCasting?.value != true) {
                                player.play()
                            }
                            wasPlayingBeforeAudioFocusLoss = false
                        }
                        reentrantFocusGain = false
                    }
                }

                player.volume = if (isMuted.value) 0f else playerVolume.value
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
                abandonAudioFocus()
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.volume = if (isMuted.value) 0f else (playerVolume.value * 0.2f)
                }
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                player.volume = if (isMuted.value) 0f else playerVolume.value
                lastAudioFocusState = focusChange
            }
        }
        autoMixDecks.values.forEach { it.applyUserVolume() }
        nativeAutoMix?.volumeChanged()
        intelligentAutoMix?.syncPlaybackState()
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    private fun clearPersistedQueueFiles() {
        runCatching { filesDir.resolve(PERSISTENT_QUEUE_FILE).delete() }
        runCatching { filesDir.resolve(PERSISTENT_AUTOMIX_FILE).delete() }
        runCatching { filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).delete() }
    }

    fun hasAudioFocusForPlayback(): Boolean {
        return hasAudioFocus
    }

    private fun waitOnNetworkError() {
        if (waitingForNetworkConnection.value) return

        // Check if we've exceeded max retry attempts
        if (retryCount >= MAX_RETRY_COUNT) {
            Timber.tag(TAG).w("Max retry count ($MAX_RETRY_COUNT) reached, stopping playback")
            stopOnError()
            retryCount = 0
            return
        }

        waitingForNetworkConnection.value = true

        // Start a retry timer with exponential backoff
        retryJob?.cancel()
        retryJob = scope.launch {
            // Exponential backoff: 3s, 6s, 12s, 24s... max 30s
            val delayMs = minOf(3000L * (1 shl retryCount), 30000L)
            Timber.tag(TAG).d("Waiting ${delayMs}ms before retry attempt ${retryCount + 1}/$MAX_RETRY_COUNT")
            delay(delayMs)

            if (isNetworkConnected.value && waitingForNetworkConnection.value) {
                retryCount++
                triggerRetry()
            }
        }
    }

    private fun triggerRetry() {
        waitingForNetworkConnection.value = false
        retryJob?.cancel()

        if (player.currentMediaItem != null) {
            // After 3+ failed retries, try to refresh the stream URL by seeking to current position
            // This forces ExoPlayer to re-resolve the data source and get a fresh URL
            if (retryCount > 3) {
                Timber.tag(TAG).d("Retry count > 3, attempting to refresh stream URL")
                val currentPosition = player.currentPosition
                player.seekTo(player.currentMediaItemIndex, currentPosition)
            }
            player.prepare()
            // Don't call play() here - let the player auto-resume via playWhenReady
            // This avoids stealing audio focus during retry attempts
        }
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            // Don't start local playback if casting
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (currentSong.value?.song?.liked ==
                                true
                            ) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    )
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(R.drawable.radio)
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
            ),
        )
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: InnerTubeXPlayer.PlaybackData? = null
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else {
                var updatedSong = song.song
                if (song.song.duration == -1) {
                    updatedSong = updatedSong.copy(duration = duration)
                }
                // Update isVideo flag if it's different from the current value
                if (song.song.isVideo != mediaMetadata.isVideoSong) {
                    updatedSong = updatedSong.copy(isVideo = mediaMetadata.isVideoSong)
                }
                if (updatedSong != song.song) {
                    update(updatedSong)
                }
            }
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        if (serviceDestroyed) return
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.Main) + Job()

        // Safety Check : Ensuring player is initilized
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("playQueue called before player initialization, queuing request")
            scope.launch {
                playerInitialized.first { it }
                playQueue(queue, playWhenReady)
            }
            return
        }

        // Manually switching to a different song — tapping a track in a
        // playlist/album/queue, not just pressing next/previous — is still a
        // manual navigation action, so it crossfades under the same
        // conditions as manual next/previous. This now also covers queues that
        // start from a single "preload" item (Charts, Explore, Stats, Listen
        // Together sync, and "Start radio"): we crossfade into the preload item
        // on the secondary player immediately, then let the background
        // coroutine fill in the rest of the queue around it once resolved.
        // Requires the player to actually be playing, since the fade ramp
        // pauses while the primary player is paused.
        val canAttemptQueueCrossfade =
            crossfadeEnabled &&
                crossfadeManualSkipEnabled &&
                !isCrossfading &&
                castConnectionHandler?.isCasting?.value != true &&
                player.duration != C.TIME_UNSET &&
                player.playbackState != STATE_IDLE &&
                player.isPlaying

        Timber.tag(TAG).d(
            "playQueue: canAttemptQueueCrossfade=%s (preloadItem=%s crossfadeEnabled=%s manualSkip=%s isCrossfading=%s casting=%s duration=%s playbackState=%s)",
            canAttemptQueueCrossfade,
            queue.preloadItem != null,
            crossfadeEnabled,
            crossfadeManualSkipEnabled,
            isCrossfading,
            castConnectionHandler?.isCasting?.value,
            player.duration,
            player.playbackState,
        )

        currentQueue = queue
        queueTitle = null
        radioChipsJob?.cancel()
        radioChipsJob = null
        radioChips.value = emptyList()
        val persistShuffleAcrossQueues = dataStore.get(PersistentShuffleAcrossQueuesKey, false)
        val previousShuffleEnabled = player.shuffleModeEnabled
        if (!canAttemptQueueCrossfade && !persistShuffleAcrossQueues) {
            player.shuffleModeEnabled = false
        }
        // Reset original queue size when starting a new queue
        originalQueueSize = 0
        // When crossfade is on and the queue starts from a preload item, begin
        // the fade immediately against just that item on the secondary player
        // (instead of the synchronous instant-replace below). The background
        // coroutine then inserts the rest of the queue around the now-playing
        // preload item once it resolves. Falls back to the instant preload if a
        // crossfade is already in flight or couldn't start.
        var preloadQueueCrossfadeStarted = false
        if (canAttemptQueueCrossfade && queue.preloadItem != null) {
            preloadQueueCrossfadeStarted =
                startQueueCrossfadeWithPreload(queue, persistShuffleAcrossQueues)
            if (!preloadQueueCrossfadeStarted && !persistShuffleAcrossQueues) {
                player.shuffleModeEnabled = false
            }
        }
        if (!preloadQueueCrossfadeStarted && queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue.getInitialStatus()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }

            if (queue.radioChips != null) {
                radioChipsJob = scope.launch {
                    queue.radioChips!!.collect {
                        timber.log.Timber.tag("Chippy").e("MusicService collected chips from Queue! Size: ${it.size}")
                        radioChips.value = it
                    }
                }
            }
            if (initialStatus.items.isEmpty()) return@launch
            // Track original queue size for shuffle playlist first feature
            originalQueueSize = initialStatus.items.size

            // Full-queue crossfade path: only when there was no preload item
            // (the preload path already started its fade above). Guarding here
            // prevents a second crossfade attempt once the full queue resolves.
            if (canAttemptQueueCrossfade && queue.preloadItem == null) {
                Timber.tag(TAG).d(
                    "playQueue: attempting queue crossfade (items=%d targetIndex=%d)",
                    initialStatus.items.size,
                    initialStatus.mediaItemIndex,
                )
                if (startQueueCrossfade(initialStatus, persistShuffleAcrossQueues)) {
                    Timber.tag(TAG).d("playQueue: queue crossfade started successfully")
                    return@launch
                } else {
                    Timber.tag(TAG).d("playQueue: startQueueCrossfade returned false, falling back to instant replace")
                }
            }

            if (queue.preloadItem != null) {
                val actualIndex = initialStatus.items.indexOfFirst { it.mediaId == queue.preloadItem!!.id }
                val targetIndex = if (actualIndex != -1) {
                    actualIndex
                } else {
                    initialStatus.mediaItemIndex.coerceIn(0, initialStatus.items.size)
                }

                if (targetIndex < initialStatus.items.size) {
                    player.addMediaItems(
                        0,
                        initialStatus.items.subList(0, targetIndex)
                    )
                    player.addMediaItems(
                        initialStatus.items.subList(
                            targetIndex + 1,
                            initialStatus.items.size
                        )
                    )
                    if (actualIndex != -1) {
                        player.replaceMediaItem(
                            targetIndex,
                            initialStatus.items[targetIndex]
                        )
                    }
                } else {
                    player.addMediaItems(0, initialStatus.items)
                }
            } else {
                player.setMediaItems(
                    initialStatus.items,
                    if (initialStatus.mediaItemIndex >
                        0
                    ) {
                        initialStatus.mediaItemIndex
                    } else {
                        0
                    },
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
            }

            // Rebuild shuffle order if shuffle is enabled
            if (player.shuffleModeEnabled) {
                val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
            }
        }
    }

    fun startRadioSeamlessly() {
        // Safety Check: Ensure Player is initilized
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("startRadioSeamlessly called before player initialization")
            return
        }

        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id

        scope.launch(SilentHandler) {
            // Use simple videoId to let YouTube personalize recommendations
            val radioQueue = YouTubeQueue(
                endpoint = WatchEndpoint(
                    videoId = currentMediaId,
                    playlistId = "RDAMVM$currentMediaId"
                )
            )

            try {
                val initialStatus = withContext(Dispatchers.IO) {
                    radioQueue.getInitialStatus()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }

                if (initialStatus.title != null) {
                    queueTitle = initialStatus.title
                }

                if (radioQueue.radioChips != null) {
                    radioChipsJob?.cancel()
                    radioChipsJob = scope.launch {
                        radioQueue.radioChips!!.collect {
                            radioChips.value = it
                        }
                    }
                }

                // Filter radio items to exclude current media item
                val radioItems = initialStatus.items.filter { item ->
                    item.mediaId != currentMediaId
                }

                if (radioItems.isNotEmpty()) {
                    val itemCount = player.mediaItemCount

                    if (itemCount > currentIndex + 1) {
                        player.removeMediaItems(currentIndex + 1, itemCount)
                    }

                    player.addMediaItems(currentIndex + 1, radioItems)
                    if (player.shuffleModeEnabled) {
                        val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                    }
                }

                currentQueue = radioQueue
            } catch (e: Exception) {
                // Fallback: try with related endpoint
                try {
                    val nextResult = withContext(Dispatchers.IO) {
                        YouTube.next(WatchEndpoint(videoId = currentMediaId)).getOrNull()
                    }
                    nextResult?.relatedEndpoint?.let { relatedEndpoint ->
                        val relatedPage = withContext(Dispatchers.IO) {
                            YouTube.related(relatedEndpoint).getOrNull()
                        }
                        relatedPage?.songs?.let { songs ->
                            val radioItems = songs
                                .filter { it.id != currentMediaId }
                                .map { it.toMediaItem() }
                                .filterExplicit(dataStore.get(HideExplicitKey, false))
                                .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))

                            if (radioItems.isNotEmpty()) {
                                val itemCount = player.mediaItemCount
                                if (itemCount > currentIndex + 1) {
                                    player.removeMediaItems(currentIndex + 1, itemCount)
                                }
                                player.addMediaItems(currentIndex + 1, radioItems)
                                if (player.shuffleModeEnabled) {
                                    val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                                    applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Silent fail
                }
            }
        }
    }

    fun applyRadioChip(chip: RadioChip) {
        val currentMediaMetadata = player.currentMetadata ?: return
        val currentMediaId = currentMediaMetadata.id
        val currentIndex = player.currentMediaItemIndex

        val endpoint = (currentQueue as? YouTubeQueue)?.endpoint?.copy(params = chip.params)
            ?: com.music.innertube.models.WatchEndpoint(
                videoId = currentMediaId,
                playlistId = "RDAMVM$currentMediaId",
                params = chip.params
            )

        scope.launch(SilentHandler) {
            val radioQueue = YouTubeQueue(
                endpoint = endpoint
            )

            try {
                val initialStatus = withContext(Dispatchers.IO) {
                    radioQueue.getInitialStatus()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }

                if (initialStatus.title != null) {
                    queueTitle = initialStatus.title
                }

                if (radioQueue.radioChips != null) {
                    radioChipsJob?.cancel()
                    radioChipsJob = scope.launch {
                        radioQueue.radioChips!!.collect {
                            radioChips.value = it.map { rc -> 
                                if (rc.title == chip.title) rc.copy(isSelected = true) else rc.copy(isSelected = false)
                            }
                        }
                    }
                }

                // Filter radio items to exclude current media item
                val radioItems = initialStatus.items.filter { item ->
                    item.mediaId != currentMediaId
                }

                if (radioItems.isNotEmpty()) {
                    val itemCount = player.mediaItemCount

                    if (itemCount > currentIndex + 1) {
                        player.removeMediaItems(currentIndex + 1, itemCount)
                    }

                    player.addMediaItems(currentIndex + 1, radioItems)
                    if (player.shuffleModeEnabled) {
                        val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                    }
                }

                this@MusicService.currentQueue = radioQueue
            } catch (e: Exception) {
                timber.log.Timber.tag(TAG).e(e, "Failed to apply radio chip")
            }
        }
    }

    fun refreshChipsForCurrentSong() {
        val currentQueue = currentQueue // Use smart cast via local variable
        if (currentQueue != null && currentQueue !is YouTubeQueue) {
            val isRestoredRadio = (currentQueue as? ListQueue)?.isRadio == true
            if (!isRestoredRadio) {
                radioChips.value = emptyList()
                return
            }
        }
        
        val currentMediaMetadata = player.currentMetadata ?: return
        val currentMediaId = currentMediaMetadata.id

        // Only auto-refresh when in a radio-style queue (RDAMVM or similar)
        // so we don't override chips for a chip-filtered queue the user selected
        scope.launch(SilentHandler) {
            val radioQueue = YouTubeQueue(
                endpoint = WatchEndpoint(
                    videoId = currentMediaId,
                    playlistId = "RDAMVM$currentMediaId"
                )
            )
            try {
                var nextResult = withContext(Dispatchers.IO) {
                    com.music.innertube.YouTube.next(
                        radioQueue.endpoint
                    ).getOrNull()
                }

                if (nextResult == null || nextResult.radioChips.isEmpty()) {
                    nextResult = withContext(Dispatchers.IO) {
                        com.music.innertube.YouTube.next(
                            WatchEndpoint(videoId = currentMediaId)
                        ).getOrNull()
                    }
                }

                if (nextResult != null && nextResult.radioChips.isNotEmpty()) {
                    radioChipsJob?.cancel()
                    // We directly update the chips' value from the nextResult
                    // no need to collect the dummy radioQueue stateflow
                    radioChips.value = nextResult.radioChips
                    timber.log.Timber.tag("Chippy").d("refreshChipsForCurrentSong: pushed ${nextResult.radioChips.size} chips for $currentMediaId")
                } else if (currentQueue == null) {
                    // No chips from API and no existing radio queue - clear chips
                    radioChips.value = emptyList()
                }
            } catch (_: Exception) {
                // Silently fail — chips are optional UX enhancement
            }
        }
    }

    fun getAutomixAlbum(albumId: String) {
        scope.launch(SilentHandler) {
            YouTube
                .album(albumId)
                .onSuccess {
                    getAutomix(it.album.playlistId)
                }
        }
    }

    fun getAutomix(playlistId: String) {
        if (dataStore.get(SimilarContent, true) &&
            !(dataStore.get(DisableLoadMoreWhenRepeatAllKey, false) && player.repeatMode == REPEAT_MODE_ALL)) {
            scope.launch(SilentHandler) {
                try {
                    // Try primary method
                    YouTube.next(WatchEndpoint(playlistId = playlistId))
                        .onSuccess { firstResult ->
                            YouTube.next(WatchEndpoint(playlistId = firstResult.endpoint.playlistId))
                                .onSuccess { secondResult ->
                                    automixItems.value = secondResult.items.map { song ->
                                        song.toMediaItem()
                                    }
                                }
                                .onFailure {
                                    // Fallback: use first result items
                                    if (firstResult.items.isNotEmpty()) {
                                        automixItems.value = firstResult.items.map { song ->
                                            song.toMediaItem()
                                        }
                                    }
                                }
                        }
                        .onFailure {
                            // Fallback: try with radio format
                            val currentSong = player.currentMetadata
                            if (currentSong != null) {
                                // Use simple videoId for better personalized recommendations
                                YouTube.next(WatchEndpoint(
                                    videoId = currentSong.id
                                )).onSuccess { radioResult ->
                                    val filteredItems = radioResult.items
                                        .filter { it.id != currentSong.id }
                                        .map { it.toMediaItem() }
                                    if (filteredItems.isNotEmpty()) {
                                        automixItems.value = filteredItems
                                    }
                                }.onFailure {
                                    // Final fallback: try related endpoint
                                    YouTube.next(WatchEndpoint(videoId = currentSong.id)).getOrNull()?.relatedEndpoint?.let { relatedEndpoint ->
                                        YouTube.related(relatedEndpoint).onSuccess { relatedPage ->
                                            val relatedItems = relatedPage.songs
                                                .filter { it.id != currentSong.id }
                                                .map { it.toMediaItem() }
                                            if (relatedItems.isNotEmpty()) {
                                                automixItems.value = relatedItems

                                            }
                                        }
                                    }
                                }
                            }
                        }
                } catch (_: Exception) {
                    // Silent fail
                }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixItems.value = emptyList()
    }

    fun playNext(items: List<MediaItem>) {
        // If queue is empty or player is idle, play immediately instead
        if (player.mediaItemCount == 0 || player.playbackState == STATE_IDLE) {
            player.setMediaItems(items)
            player.prepare()
            // Don't start local playback if casting
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        // Remove duplicates if enabled
        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        val insertIndex = player.currentMediaItemIndex + 1
        val shuffleEnabled = player.shuffleModeEnabled

        // Insert items immediately after the current item in the window/index space
        player.addMediaItems(insertIndex, items)
        player.prepare()

        if (shuffleEnabled) {
            // Rebuild shuffle order so that newly inserted items are played next
            val timeline = player.currentTimeline
            if (!timeline.isEmpty) {
                val size = timeline.windowCount
                val currentIndex = player.currentMediaItemIndex

                // Newly inserted indices are a contiguous range [insertIndex, insertIndex + items.size)
                val newIndices = (insertIndex until (insertIndex + items.size)).toSet()

                // Collect existing shuffle traversal order excluding current index
                val orderAfter = mutableListOf<Int>()
                var idx = currentIndex
                while (true) {
                    idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (idx == C.INDEX_UNSET) break
                    if (idx != currentIndex) orderAfter.add(idx)
                }

                val prevList = mutableListOf<Int>()
                var pIdx = currentIndex
                while (true) {
                    pIdx = timeline.getPreviousWindowIndex(pIdx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (pIdx == C.INDEX_UNSET) break
                    if (pIdx != currentIndex) prevList.add(pIdx)
                }
                prevList.reverse() // preserve original forward order

                val existingOrder = (prevList + orderAfter).filter { it != currentIndex && it !in newIndices }

                // Build new shuffle order: current -> newly inserted (in insertion order) -> rest
                val nextBlock = (insertIndex until (insertIndex + items.size)).toList()
                val finalOrder = IntArray(size)
                var pos = 0
                finalOrder[pos++] = currentIndex
                nextBlock.forEach { if (it in 0 until size) finalOrder[pos++] = it }
                existingOrder.forEach { if (pos < size) finalOrder[pos++] = it }

                // Fill any missing indices (safety) to ensure a full permutation
                if (pos < size) {
                    for (i in 0 until size) {
                        if (!finalOrder.contains(i)) {
                            finalOrder[pos++] = i
                            if (pos == size) break
                        }
                    }
                }

                player.setShuffleOrder(DefaultShuffleOrder(finalOrder, System.currentTimeMillis()))
            }
        }
    }

    
    /**
     * Marks [mediaId] as belonging to the Cache Playlist by setting [dateDownload],
     * but ONLY if the full file (byte 0 through contentLength) is actually present
     * in playerCache. This must only be called from a genuine "track finished
     * naturally" signal (see onMediaItemTransition's AUTO-reason handling) —
     * never from raw dataSpec/chunk resolution, since the player's background
     * prefetch can finish downloading a short file in seconds, long before the
     * user has actually listened to it (or even if they skipped away early).
     *
     * No-op if already marked downloaded, or if we don't yet know the file's
     * contentLength (FormatEntity not fetched yet).
     */

    private suspend fun markCachedIfFullyDownloaded(mediaId: String) {
        val song = database.song(mediaId).first() ?: return
        if (song.song.dateDownload != null || song.song.isDownloaded) return
        val contentLength = song.format?.contentLength ?: return
        // Do not use runBlocking here, just suspend function call
        if (!playerCache.isCached(mediaId, 0, contentLength)) return
        database.query {
            update(song.song.copy(dateDownload = java.time.LocalDateTime.now()))
        }
    }

    fun addToQueue(items: List<MediaItem>) {
        // Remove duplicates if enabled
        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        player.addMediaItems(items)
        if (player.shuffleModeEnabled) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
        player.prepare()
    }

    fun toggleLibrary() {
        scope.launch {
            val songToToggle = currentSong.first()
            songToToggle?.let {
                val isInLibrary = it.song.inLibrary != null
                val token = if (isInLibrary) it.song.libraryRemoveToken else it.song.libraryAddToken

                // Call YouTube API with feedback token if available
                token?.let { feedbackToken ->
                    YouTube.feedback(listOf(feedbackToken))
                }

                // Update local database
                database.query {
                    update(it.song.toggleLibrary())
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    fun toggleLike() {
        scope.launch {
            val songToToggle = currentSong.first()
            songToToggle?.let {
                val song = it.song.toggleLike()
                database.query {
                    update(song)
                    syncUtils.likeSong(song)

                    // Check if auto-download on like is enabled and the song is now liked
                    if (dataStore.get(AutoDownloadOnLikeKey, false) && song.liked) {
                        // Trigger download for the liked song
                        val downloadRequest =
                            androidx.media3.exoplayer.offline.DownloadRequest
                                .Builder(song.id, song.id.toUri())
                                .setCustomCacheKey(song.id)
                                .setData(song.title.toByteArray())
                                .build()
                        androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                            this@MusicService,
                            ExoDownloadService::class.java,
                            downloadRequest,
                            false
                        )
                    }
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun setupLoudnessEnhancer() {
        if (serviceDestroyed) return
        if (autoMixRequested) { releaseLoudnessEnhancer(); return }
        val audioSessionId = player.audioSessionId

        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            Timber.tag(TAG).w("setupLoudnessEnhancer: invalid audioSessionId ($audioSessionId), cannot create effect yet")
            return
        }

        // An effect belongs to its actual output, not whichever player is currently primary.
        if (loudnessEnhancer != null && loudnessEnhancerSessionId != audioSessionId) releaseLoudnessEnhancer()
        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                loudnessEnhancerSessionId = audioSessionId
                Timber.tag(TAG).d("LoudnessEnhancer created for sessionId=$audioSessionId")
            } catch (e: Exception) {
                reportException(e)
                loudnessEnhancer = null
                return
            }
        }

        scope.launch {
            try {
                val currentMediaId = withContext(Dispatchers.Main) {
                    player.currentMediaItem?.mediaId
                }

                val normalizeAudio = withContext(Dispatchers.IO) {
                    dataStore.data.map { it[AudioNormalizationKey] ?: true }.first()
                }

                if (normalizeAudio && currentMediaId != null) {
                    val format = withContext(Dispatchers.IO) {
                        database.format(currentMediaId).first()
                    }

                    Timber.tag(TAG).d("Audio normalization enabled: $normalizeAudio")
                    Timber.tag(TAG).d("Format loudnessDb: ${format?.loudnessDb}, perceptualLoudnessDb: ${format?.perceptualLoudnessDb}")

                    // Use loudnessDb if available, otherwise fall back to perceptualLoudnessDb
                    val loudness = format?.loudnessDb ?: format?.perceptualLoudnessDb

                    withContext(Dispatchers.Main) {
                        if (autoMixRequested || player.audioSessionId != audioSessionId ||
                            player.currentMediaItem?.mediaId != currentMediaId) return@withContext
                        if (loudness != null) {
                            val loudnessDb = loudness.toFloat()
                            val targetGain = (-loudnessDb * 100).toInt()
                            val clampedGain = targetGain.coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)

                            Timber.tag(TAG).d("Calculated raw normalization gain: $targetGain mB (from loudness: $loudnessDb)")

                            try {
                                loudnessEnhancer?.setTargetGain(clampedGain)
                                loudnessEnhancer?.enabled = true
                                Timber.tag(TAG).i("LoudnessEnhancer gain applied: $clampedGain mB")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to apply loudness enhancement")
                                reportException(e)
                                releaseLoudnessEnhancer()
                            }
                        } else {
                            loudnessEnhancer?.enabled = false
                            Timber.tag(TAG).w("Normalization enabled but no loudness data available - no normalization applied")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        loudnessEnhancer?.enabled = false
                        Timber.tag(TAG).d("setupLoudnessEnhancer: normalization disabled or mediaId unavailable")
                    }
                }
            } catch (e: Exception) {
                reportException(e)
                releaseLoudnessEnhancer()
            }
        }
    }

    private fun releaseLoudnessEnhancer() {
        try {
            loudnessEnhancer?.release()
            Timber.tag(TAG).d("LoudnessEnhancer released")
        } catch (e: Exception) {
            reportException(e)
            Timber.tag(TAG).e(e, "Error releasing LoudnessEnhancer: ${e.message}")
        } finally {
            loudnessEnhancerSessionId = 0
            loudnessEnhancer = null
        }
    }

    private fun observeAudioOutput(source: ExoPlayer, primary: Boolean) {
        val identity = "deck-${++outputSequence}"
        outputIdentities[source] = identity
        effectSessions.register(identity, if (primary) com.music.vivi.playback.effects.AudioEffectSessions.Role.PRIMARY
            else com.music.vivi.playback.effects.AudioEffectSessions.Role.PREPARED)
        val lifecycle=com.music.vivi.playback.effects.AudioOutputLifecycle(effectSessions,identity)
        outputLifecycles[source]=lifecycle
        val listener = object : Player.Listener, androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                val previous=outputFormats[source]
                val info=AudioDiagnostics.from(format).let { fresh ->
                    if((diagnosticTrackCounts[source] ?: 0)>0) fresh.copy(outputSampleRate=previous?.outputSampleRate,
                        outputEncoding=previous?.outputEncoding) else fresh
                }
                outputFormats[source]=info
                formatTrackIds[source]=source.currentMediaItem?.mediaId
                if (playerInitialized.value && source === player) _audioDiagnostics.value=info
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                lifecycle.sessionChanged(audioSessionId)
                if (playerInitialized.value && source === player) setupLoudnessEnhancer()
            }
            override fun onAudioTrackInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                diagnosticTrackCounts[source]=(diagnosticTrackCounts[source] ?: 0)+1
                val info=(outputFormats[source] ?: AudioDiagnostics()).copy(
                    outputSampleRate=audioTrackConfig.sampleRate, outputEncoding=audioTrackConfig.encoding)
                outputFormats[source]=info
                if(playerInitialized.value && source === player) _audioDiagnostics.value=info
                lifecycle.trackInitialized(source.audioSessionId)
                if(playerInitialized.value && source === player) setupLoudnessEnhancer()
            }
            override fun onAudioTrackReleased(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                diagnosticTrackCounts[source]=((diagnosticTrackCounts[source] ?: 0)-1).coerceAtLeast(0)
                if(diagnosticTrackCounts[source]==0) {
                    val info=(outputFormats[source] ?: AudioDiagnostics()).copy(outputSampleRate=null,outputEncoding=null)
                    outputFormats[source]=info
                    if(playerInitialized.value && source === player) _audioDiagnostics.value=info
                }
                lifecycle.trackReleased()
            }
        }
        outputListeners[source] = listener
        source.addListener(listener)
        source.addAnalyticsListener(listener)
    }

    private fun promoteAudioOutput(source: ExoPlayer) {
        _audioDiagnostics.value=outputFormats[source] ?: AudioDiagnostics.from(source.audioFormat)
        outputIdentities[source]?.let(effectSessions::promote)
        setupLoudnessEnhancer()
    }

    private fun releaseAudioOutput(source: ExoPlayer) {
        outputFormats.remove(source)
        formatTrackIds.remove(source)
        diagnosticTrackCounts.remove(source)
        outputListeners.remove(source)?.let { listener ->
            source.removeListener(listener)
            if(listener is androidx.media3.exoplayer.analytics.AnalyticsListener) source.removeAnalyticsListener(listener)
        }
        outputIdentities.remove(source)
        val lifecycle=outputLifecycles.remove(source)
        autoMixDecks.remove(source)
        autoMixProcessors.remove(source)
        playerSilenceProcessors.remove(source)
        playerEqProcessors.remove(source)?.let(equalizerService::removeAudioProcessor)
        try { source.release() } finally { lifecycle?.dispose() }
    }

    private var previousMediaItemIndex = C.INDEX_UNSET

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            if(!nativePromoting)nativeAutoMix?.validateQueue() // unrelated future additions retain the pair
            autoMixQueueEpoch++
            intelligentAutoMix?.playlistChanged(player.currentMediaItem?.mediaId)
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if(isCrossfading && mediaItem?.mediaId != crossfadeTargetId) {
            crossfadeJob?.cancel(); cleanupCrossfade()
            player.volume=if(isMuted.value) 0f else playerVolume.value
        }
        if(!nativePromoting)invalidateNative(NativeDjExecutionReason.QUEUE_CHANGED)
        intelligentAutoMix?.trackChanged(mediaItem?.mediaId)
        _audioDiagnostics.value=outputFormats[player]?.takeIf { formatTrackIds[player]==mediaItem?.mediaId } ?: AudioDiagnostics()
        // Force Repeat One if the player ignored it and auto-advanced
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            if (previousMediaItemIndex != C.INDEX_UNSET && previousMediaItemIndex < player.mediaItemCount) {
                val finishedMediaId = player.getMediaItemAt(previousMediaItemIndex).mediaId
                scope.launch(Dispatchers.IO) { markCachedIfFullyDownloaded(finishedMediaId) }
            }

            val repeatMode = runBlocking { dataStore.get(RepeatModeKey, REPEAT_MODE_OFF) }
            if (repeatMode == REPEAT_MODE_ONE &&
                previousMediaItemIndex != C.INDEX_UNSET &&
                previousMediaItemIndex != player.currentMediaItemIndex) {

                player.seekTo(previousMediaItemIndex, 0)
            }
        }
        previousMediaItemIndex = player.currentMediaItemIndex

        lastPlaybackSpeed = -1.0f // force update song

        setupLoudnessEnhancer()

        discordUpdateJob?.cancel()

        scrobbleManager?.onSongStop()
        if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
        }

        // Sync Cast when media changes and Cast is connected
        // Skip if this change was triggered by Cast sync (to prevent loops)
        if (castConnectionHandler?.isCasting?.value == true &&
            castConnectionHandler?.isSyncingFromCast != true &&
            mediaItem != null) {
            val metadata = mediaItem.metadata
            if (metadata != null) {
                // Try to navigate to the item if it's already in Cast queue
                // This avoids a full reload which causes the widget to refresh
                val navigated = castConnectionHandler?.navigateToMediaIfInQueue(metadata.id) ?: false
                if (!navigated) {
                    // Item not in Cast queue, need to reload
                    castConnectionHandler?.loadMedia(metadata)
                }
            }
        }

        // Auto load more songs from queue
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage() &&
            !(dataStore.get(DisableLoadMoreWhenRepeatAllKey, false) && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            scope.launch(SilentHandler) {
                val mediaItems = withContext(Dispatchers.IO) {
                    currentQueue.nextPage()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }
                if (player.playbackState != STATE_IDLE && mediaItems.isNotEmpty()) {
                    player.addMediaItems(mediaItems)
                    if (player.shuffleModeEnabled) {
                        val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                    }
                }
            }
        }

        // Pre-resolve the next track's stream URL so the transition feels instant
        prefetchNextTrack()

        // Save state when media item changes
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
    }

    /**
     * Look-ahead stream prefetcher.
     *
     * Resolves the next queued track's signed CDN stream URL in the background and stores it
     * in [songUrlCache].  When ExoPlayer later transitions to that track, [createDataSourceFactory]
     * reads the cached URL instantly — eliminating the 500 ms–2 s network round-trip that would
     * otherwise cause an audible gap between songs.
     *
     * Only the **immediate next** track is prefetched to keep bandwidth usage minimal.  The job is
     * cancelled and restarted on every track change, so stale URLs from shuffle/queue edits are
     * automatically discarded.
     *
     * Note: this only resolves a small InnerTube JSON response (~10–30 KB).  No audio bytes are
     * downloaded here — that is handled by ExoPlayer's own CacheDataSource pipeline.
     */
    private fun prefetchNextTrack() {
        // Cancel any in-flight prefetch from the previous song
        prefetchJob?.cancel()

        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return  // no next track (end of queue / repeat-one)

        val nativeEpoch=nativeSources.epoch
        val nextItem=player.getMediaItemAt(nextIndex)
        val nextMediaId = nextItem.mediaId
        if(autoMixRequested && autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL && nextItem.localConfiguration?.uri?.scheme=="file") {
            val uri=nextItem.localConfiguration!!.uri;val file=java.io.File(requireNotNull(uri.path))
            nativeSources.remember(nextMediaId,AnalysisSource(uri,nextMediaId,file.length(),"$nextMediaId|local|${file.length()}|${file.lastModified()}|$uri",analysisDataSourceFactory,true),nativeEpoch)
            return
        }

        // Nothing to do — URL is already cached and hasn't expired
        val cachedEntry = songUrlCache[nextMediaId]
        if (cachedEntry != null && cachedEntry.second > System.currentTimeMillis() &&
            (autoMixEngineMode!=AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL || !autoMixRequested || nativeSources.containsKey(nextMediaId))) return

        prefetchJob = scope.launch(Dispatchers.IO + SilentHandler) {
            if(autoMixRequested && autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL) {
                // Reuse already-authorized cache/URL metadata before making a network request.
                val format=database.format(nextMediaId).first()
                val length=format?.contentLength ?: 0L
                if(format!=null && length>0) {
                    val complete=downloadCache.isCached(nextMediaId,0,length) || playerCache.isCached(nextMediaId,0,length)
                    val uri=if(complete)nextItem.localConfiguration?.uri else cachedEntry?.takeIf {it.second>System.currentTimeMillis()}?.first?.toUri()
                    if(uri!=null && isActive) {
                        nativeSources.remember(nextMediaId,AnalysisSource(uri,nextMediaId,length,
                            "$nextMediaId|${format.itag}|$length|${format.codecs}|${format.sampleRate}|${if(complete)"cached" else uri}",analysisDataSourceFactory,complete),nativeEpoch)
                        return@launch
                    }
                }
            }
            Timber.tag(TAG).d("[Prefetch] Resolving stream URL for next track: $nextMediaId")
            val result = runCatching {
                YTPlayerUtils.playerResponseForPlayback(
                    videoId = nextMediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    context = this@MusicService,
                )
            }
            result.getOrNull()?.getOrNull()?.let { playbackData ->
                // Only write to cache if the job wasn't cancelled while we were resolving
                if (isActive) {
                    songUrlCache[nextMediaId] =
                        playbackData.streamUrl to
                            System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
                    Timber.tag(TAG).d("[Prefetch] Cached stream URL for $nextMediaId (expires in ${playbackData.streamExpiresInSeconds}s)")

                    playbackData.format?.let { format ->
                        if(autoMixRequested && autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL && (format.contentLength ?: 0L)>0)
                            nativeSources.remember(nextMediaId,AnalysisSource(playbackData.streamUrl.toUri(),nextMediaId,format.contentLength!!,
                                "$nextMediaId|${format.itag}|${format.contentLength}|${format.mimeType}|${format.audioSampleRate}|${playbackData.streamUrl}",analysisDataSourceFactory),nativeEpoch)
                        val loudnessDb = playbackData.audioConfig?.loudnessDb
                        val perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb
                        database.query {
                            upsert(
                                FormatEntity(
                                    id = nextMediaId,
                                    itag = format.itag,
                                    mimeType = format.mimeType.split(";")[0],
                                    codecs = format.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "mp3",
                                    bitrate = format.bitrate,
                                    sampleRate = format.audioSampleRate,
                                    contentLength = format.contentLength ?: 0L,
                                    loudnessDb = loudnessDb,
                                    perceptualLoudnessDb = perceptualLoudnessDb,
                                    playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                                )
                            )
                        }
                    }
                }
            } ?: Timber.tag(TAG).d("[Prefetch] Could not resolve stream URL for $nextMediaId — will resolve on demand")
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        // Force Repeat All if the player ignored it and ended playback
        if (playbackState == Player.STATE_ENDED) {
            val repeatMode = runBlocking { dataStore.get(RepeatModeKey, REPEAT_MODE_OFF) }
            if (repeatMode == REPEAT_MODE_ALL && player.mediaItemCount > 0) {
                player.seekTo(0, 0)
                player.prepare()
                player.play()
            }
        }

        // Save state when playback state changes (but not during silence skipping)
        if (dataStore.get(PersistentQueueKey, true) && !isSilenceSkipping) {
            saveQueueToDisk()
        }

        if (playbackState == Player.STATE_READY) {
            consecutivePlaybackErr = 0
            retryCount = 0
            waitingForNetworkConnection.value = false
            retryJob?.cancel()

            // Reset retry count for current song on successful playback
            player.currentMediaItem?.mediaId?.let { mediaId ->
                resetRetryCount(mediaId)
                Timber.tag(TAG).d("Playback successful for $mediaId, reset retry count")
            }
            scheduleCrossfade()
        }

        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            scrobbleManager?.onSongStop()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if(!nativeForwardingPlayback) {
            nativeForwardingPlayback=true
            try {
                if(!playWhenReady && reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && nativeAutoMix?.ownsPlayback==true) {
                    val original=player;val index=player.currentMediaItemIndex
                    nativePlayback?.outgoingItemReachedEnd();nativeAutoMix?.tick()
                    if(player===original) {
                        // A failed end-boundary attempt must not leave the full queue parked
                        // at the native pause-at-end boundary. Release ownership before advancing.
                        invalidateNative(NativeDjExecutionReason.UNEXPECTED_EOS)
                        if(player.currentMediaItemIndex!=index || player.hasNextMediaItem()) {
                            if(player.currentMediaItemIndex==index)player.seekToNextMediaItem()
                            player.play()
                        }
                    }
                } else nativeAutoMix?.playbackChanged(playWhenReady)
            }finally {nativeForwardingPlayback=false}
        }
        if(autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL && nativeAutoMix?.ownsPlayback!=true) {
            if(playWhenReady)nativeStartCommand=Triple(player,System.nanoTime()/1000,player.currentPosition*1000)
            else {nativeStartup=null;nativeStartCommand=null}
        }
        intelligentAutoMix?.syncPlaybackState()
        if (isCrossfading) {
            if(playWhenReady) fadingPlayer?.play() else fadingPlayer?.pause()
        }
        // Safety net: if local player tries to start while casting, immediately pause it
        if (playWhenReady && castConnectionHandler?.isCasting?.value == true) {
            player.pause()
            return
        }

        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            if (playWhenReady) {
                isPausedByVolumeMute = false
            }

            if (!playWhenReady && !isPausedByVolumeMute) {
                wasPlayingBeforeVolumeMute = false
            }
        }

        if (playWhenReady) {
            setupLoudnessEnhancer()
        }
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            scheduleCrossfade()
            if (player.playWhenReady && player.playbackState in listOf(Player.STATE_BUFFERING, Player.STATE_READY)) {
                requestAudioFocus()
            }

        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }

        // Widget and Discord RPC updates
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            updateWidgetUI(player.isPlaying)
            if (player.isPlaying) {
                startWidgetUpdates()
            } else {
                stopWidgetUpdates()
            }
            if (!player.isPlaying && !events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                scope.launch {
                    discordRpc?.close()
                }
            }
        }

        // Update Discord RPC when media item changes or playback starts
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) {
            val mediaId = player.currentMetadata?.id
            if (mediaId != null) {
                scope.launch {
                    // Fetch song from database to get full info
                    database.song(mediaId).first()?.let { song ->
                        updateDiscordRPC(song)
                    }
                }
            }
        }

        // Scrobbling
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
        }

    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        invalidateNative(NativeDjExecutionReason.QUEUE_CHANGED)
        intelligentAutoMix?.invalidate(com.music.vivi.playback.automix.AutoMixFailure.QUEUE_CHANGED)
        updateNotification()
        if (shuffleModeEnabled) {
            // If queue is empty, don't shuffle
            if (player.mediaItemCount == 0) return

            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            val currentIndex = player.currentMediaItemIndex
            val totalCount = player.mediaItemCount

            applyShuffleOrder(currentIndex, totalCount, shufflePlaylistFirst)
        }

        // Save shuffle mode to preferences
        if (dataStore.get(RememberShuffleAndRepeatKey, true)) {
            scope.launch {
                dataStore.edit { settings ->
                    settings[ShuffleModeKey] = shuffleModeEnabled
                }
            }
        }

        // Save state when shuffle mode changes
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        invalidateNative(NativeDjExecutionReason.QUEUE_CHANGED)
        intelligentAutoMix?.invalidate(com.music.vivi.playback.automix.AutoMixFailure.QUEUE_CHANGED)
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        // Save state when repeat mode changes
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
    }

    /**
     * Reads the primary player's currently-active shuffle traversal order
     * straight from its [Timeline] (using getNext/getPreviousWindowIndex
     * with shuffle enabled, the same trick [playNext] uses) and returns it as an
     * explicit index array reproducing the full active traversal order, with
     * the current item at its real position (not necessarily first).
     *
     * ExoPlayer has no public getter for the live [ShuffleOrder], so this is how
     * we capture it just before a crossfade player swap in order to replay the
     * *same* order on the secondary player — instead of calling
     * [applyShuffleOrder], which regenerates a fresh random order and was the
     * cause of shuffle re-randomizing on every skip / song selection (and of
     * already-played tracks reappearing).
     */
    private fun getCurrentShuffleOrderIndices(sourcePlayer: Player): IntArray? {
        val timeline = sourcePlayer.currentTimeline
        if (timeline.isEmpty) return null
        val currentIndex = sourcePlayer.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return null

        val before = mutableListOf<Int>()
        var prev = currentIndex
        while (true) {
            prev = timeline.getPreviousWindowIndex(prev, Player.REPEAT_MODE_OFF, true)
            if (prev == C.INDEX_UNSET) break
            before.add(prev)
        }
        before.reverse()

        val after = mutableListOf<Int>()
        var next = currentIndex
        while (true) {
            next = timeline.getNextWindowIndex(next, Player.REPEAT_MODE_OFF, true)
            if (next == C.INDEX_UNSET) break
            after.add(next)
        }

        return (before + currentIndex + after).toIntArray()
    }

    /**
     * Applies a new shuffle order to the player, maintaining the current item's position.
     * If `shufflePlaylistFirst` is true, it attempts to shuffle original items separately from added items.
     */
    private fun applyShuffleOrder(
        currentIndex: Int,
        totalCount: Int,
        shufflePlaylistFirst: Boolean
    ) {
        if (totalCount == 0) return

        if (shufflePlaylistFirst && originalQueueSize > 0 && originalQueueSize < totalCount) {
            // Shuffle original items and added items separately
            val originalIndices = (0 until originalQueueSize).filter { it != currentIndex }.toMutableList()
            val addedIndices = (originalQueueSize until totalCount).filter { it != currentIndex }.toMutableList()

            originalIndices.shuffle()
            addedIndices.shuffle()

            val shuffledIndices = IntArray(totalCount)
            var pos = 0
            shuffledIndices[pos++] = currentIndex

            if (currentIndex < originalQueueSize) {
                originalIndices.forEach { shuffledIndices[pos++] = it }
                addedIndices.forEach { shuffledIndices[pos++] = it }
            } else {
                (0 until originalQueueSize).shuffled().forEach { shuffledIndices[pos++] = it }
                addedIndices.forEach { shuffledIndices[pos++] = it }
            }
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        } else {
            val shuffledIndices = IntArray(totalCount) { it }
            shuffledIndices.shuffle()
            // Ensure current item is first in the shuffle order
            val currentItemIndexInShuffled = shuffledIndices.indexOf(currentIndex)
            if (currentItemIndexInShuffled != -1) { // Should always be true if totalCount > 0
                val temp = shuffledIndices[0]
                shuffledIndices[0] = shuffledIndices[currentItemIndexInShuffled]
                shuffledIndices[currentItemIndexInShuffled] = temp
            }
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        if(playbackParameters.speed!=1f || playbackParameters.pitch!=1f)invalidateNative(NativeDjExecutionReason.UNSUPPORTED_RATE)
        if (playbackParameters.speed != lastPlaybackSpeed) {
            lastPlaybackSpeed = playbackParameters.speed
            discordUpdateJob?.cancel()

            // update scheduling thingy
            discordUpdateJob = scope.launch {
                delay(1000)
                if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                    currentSong.value?.let { song ->
                        updateDiscordRPC(song)
                    }
                }
            }
        }
    }

    /**
     * Extracts the HTTP response code from an error's cause chain.
     * Returns null if no HTTP response code is found.
     */
    private fun getHttpResponseCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }

    /**
     * Checks if the error is caused by an expired/forbidden URL (HTTP 403).
     * This typically happens when a YouTube stream URL expires.
     */
    private fun isExpiredUrlError(error: PlaybackException): Boolean {
        val responseCode = getHttpResponseCode(error)
        return responseCode == 403
    }

    /**
     * Checks if the error is a Range Not Satisfiable error (HTTP 416).
     * This happens when cached data doesn't match the actual stream size.
     */
    private fun isRangeNotSatisfiableError(error: PlaybackException): Boolean {
        val responseCode = getHttpResponseCode(error)
        return responseCode == 416
    }

    /**
     * Checks if the error is a "page needs to be reloaded" error.
     * This is a YouTube-specific error that requires refreshing the stream.
     */
    private fun isPageReloadError(error: PlaybackException): Boolean {
        val errorMessage = error.message?.lowercase() ?: ""
        val causeMessage = error.cause?.message?.lowercase() ?: ""
        val innerCauseMessage = error.cause?.cause?.message?.lowercase() ?: ""

        val reloadKeywords = listOf(
            "page needs to be reloaded",
            "pagina deve essere ricaricata",
            "la pagina deve essere ricaricata",
            "page must be reloaded",
            "reload",
            "ricaricata"
        )

        return reloadKeywords.any { keyword ->
            errorMessage.contains(keyword) ||
            causeMessage.contains(keyword) ||
            innerCauseMessage.contains(keyword)
        }
    }

    private fun isNetworkRelatedError(error: PlaybackException): Boolean {
        // Don't treat specific errors as network errors - they need special handling
        if (isExpiredUrlError(error) || isRangeNotSatisfiableError(error) || isPageReloadError(error)) {
            return false
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                error.cause is java.net.ConnectException ||
                error.cause is java.net.UnknownHostException ||
                (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }

    /**
     * Checks if the error is caused by AudioTrack write or initialization failures.
     * These errors indicate the audio renderer is in a corrupted/invalid state.
     */
    private fun isAudioRendererError(error: PlaybackException): Boolean {
        return error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
    }

    /**
     * Returns true if the root cause is a StreamResolveException — meaning InnerTubeX
     * exhausted every client and cannot provide a playable stream URL.
     * Retrying via the normal IO-error path is pointless in this case.
     */
    private fun isStreamResolveFailure(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause.javaClass.name.contains("StreamResolveException")) return true
            cause = cause.cause
        }
        return false
    }

    override fun onPlayerError(error: PlaybackException) {
        invalidateNative(NativeDjExecutionReason.PLAYER_FAILED)
        intelligentAutoMix?.invalidate(com.music.vivi.playback.automix.AutoMixFailure.DECODER_FAILED)
        super.onPlayerError(error)

        // Safety check : ensuring player is still initialized
        if (!playerInitialized.value) {
            Timber.tag(TAG).e(error, "Player error occurred but player not initialized")
            return
        }

        val mediaId = player.currentMediaItem?.mediaId
        Timber.tag(TAG).w(error, "Player error occurred for $mediaId: errorCode=${error.errorCode}, message=${error.message}")
        reportException(error)

        // Check if this song has failed too many times
        if (mediaId != null && hasExceededRetryLimit(mediaId)) {
            Timber.tag(TAG).w("Song $mediaId has exceeded retry limit, skipping")
            markSongAsFailed(mediaId)
            handleFinalFailure()
            return
        }

        // Aggressive cache clearing for all playback errors
        if (mediaId != null) {
            performAggressiveCacheClear(mediaId)
        }

        // Handle specific error types with strict strategies
        when {
            isStreamResolveFailure(error) -> {
                // All InnerTubeX clients were exhausted — no stream is available.
                // Retrying would just repeat the same full client sweep with the same result.
                Timber.tag(TAG).e(
                    "Stream resolution failed for $mediaId — all clients exhausted. " +
                    "Stopping immediately (skipping retry loop)."
                )
                if (mediaId != null) markSongAsFailed(mediaId)
                handleFinalFailure()
                return
            }
            isAudioRendererError(error) -> {
                Timber.tag(TAG).d("AudioTrack error detected (${error.errorCode}), performing safe recovery")
                handleAudioRendererError(mediaId)
                return
            }
            isRangeNotSatisfiableError(error) -> {
                Timber.tag(TAG).d("Range Not Satisfiable (416) detected, performing strict recovery")
                handleRangeNotSatisfiableError(mediaId)
                return
            }
            isPageReloadError(error) -> {
                Timber.tag(TAG).d("Page reload error detected, performing strict recovery")
                handlePageReloadError(mediaId)
                return
            }
            isExpiredUrlError(error) -> {
                Timber.tag(TAG).d("Expired URL (403) detected, refreshing stream URL")
                handleExpiredUrlError(mediaId)
                return
            }

            !isNetworkConnected.value || isNetworkRelatedError(error) -> {
                Timber.tag(TAG).d("Network-related error detected, waiting for connection")
                waitOnNetworkError()
                return
            }
        }

        // For IO_UNSPECIFIED and IO_BAD_HTTP_STATUS, try recovery first
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            Timber.tag(TAG).d("IO error detected (${error.errorCode}), attempting recovery")
            handleGenericIOError(mediaId)
            return
        }

        // Final fallback
        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            Timber.tag(TAG).d("Auto-skipping to next track due to unrecoverable error")
            skipOnError()
        } else {
            Timber.tag(TAG).d("Stopping playback due to unrecoverable error")
            stopOnError()
        }
    }

    /**
     * Performs aggressive cache clearing for a media item.
     * Clears both player cache and download cache, plus URL cache.
     */
    private fun performAggressiveCacheClear(mediaId: String) {
        Timber.tag(TAG).d("Performing aggressive cache clear for $mediaId")

        // Clear URL cache
        songUrlCache.remove(mediaId)

        // Clear player cache
        try {
            playerCache.removeResource(mediaId)
            Timber.tag(TAG).d("Cleared player cache for $mediaId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear player cache for $mediaId")
        }


    }

    /**
     * Stops the player, clears all caches for the current song on the IO thread,
     * then restarts playback — ensuring no stale cache data causes parse errors.
     * Safe to call from the main thread (UI).
     */
    fun retryCurrentStream() {
        val mediaId = player.currentMediaItem?.mediaId ?: return

        // Stop player before touching the cache so ExoPlayer isn't mid-read
        player.stop()

        // Clear all caches synchronously on IO — must finish before prepare()
        runBlocking(Dispatchers.IO) {
            performAggressiveCacheClear(mediaId)
        }

        // Now safe to restart — ExoPlayer will fetch a fresh stream
        player.seekTo(0)
        player.prepare()
        player.play()
    }

    /**
     * Checks if a song has exceeded the retry limit.
     */
    private fun hasExceededRetryLimit(mediaId: String): Boolean {
        val currentRetries = currentMediaIdRetryCount[mediaId] ?: 0
        return currentRetries >= MAX_RETRY_PER_SONG
    }

    /**
     * Increments the retry count for a song.
     */
    private fun incrementRetryCount(mediaId: String) {
        val currentRetries = currentMediaIdRetryCount[mediaId] ?: 0
        currentMediaIdRetryCount[mediaId] = currentRetries + 1
        Timber.tag(TAG).d("Retry count for $mediaId: ${currentRetries + 1}/$MAX_RETRY_PER_SONG")
    }

    /**
     * Resets the retry count for a song (called on successful playback).
     */
    private fun resetRetryCount(mediaId: String) {
        currentMediaIdRetryCount.remove(mediaId)
        recentlyFailedSongs.remove(mediaId)
    }

    /**
     * Marks a song as failed to prevent further retry attempts.
     */
    private fun markSongAsFailed(mediaId: String) {
        recentlyFailedSongs.add(mediaId)
        currentMediaIdRetryCount.remove(mediaId)

        // Schedule cleanup of failed songs list after 5 minutes
        failedSongsClearJob?.cancel()
        failedSongsClearJob = scope.launch {
            delay(5 * 60 * 1000L) // 5 minutes
            recentlyFailedSongs.clear()
            Timber.tag(TAG).d("Cleared recently failed songs list")
        }
    }

    /**
     * Handles AudioTrack errors (write failed, init failed) with safe recovery.
     * These errors indicate the audio renderer is corrupted and needs careful reset.
     */
    private fun handleAudioRendererError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob = scope.launch {
            try {
                // Pause playback immediately to stop the renderer
                player.pause()
                Timber.tag(TAG).d("Paused playback due to AudioTrack error")

                // Wait longer for audio renderer to settle before retry
                // This prevents the renderer from continuing to fail in a loop
                delay(RETRY_DELAY_MS * 3) // 3 seconds instead of 1 second

                // Check if player is still initialized before attempting recovery
                if (!playerInitialized.value) {
                    Timber.tag(TAG).w("Player no longer initialized, aborting AudioTrack recovery")
                    return@launch
                }

                val currentIndex = player.currentMediaItemIndex
                if (currentIndex != C.INDEX_UNSET) {
                    // Seek to current position to force a clean audio renderer reinit
                    val currentPosition = player.currentPosition
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()

                    Timber.tag(TAG).d("Retrying playback for $mediaId after AudioTrack error")

                    // Resume playback if it wasn't paused by user
                    if (wasPlayingBeforeAudioFocusLoss) {
                        delay(500) // Brief delay to allow renderer to be ready
                        if (hasAudioFocus && playerInitialized.value) {
                            if (castConnectionHandler?.isCasting?.value != true) {
                                player.play()
                            }
                        }
                    }
                } else {
                    Timber.tag(TAG).w("Invalid media item index during AudioTrack recovery")
                    handleFinalFailure()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error during AudioTrack error recovery")
                handleFinalFailure()
            }
        }
    }

    /**
     * Handles Range Not Satisfiable (416) errors with strict recovery.
     * This error occurs when cached data doesn't match the actual stream size.
     */
    private fun handleRangeNotSatisfiableError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob = scope.launch {
            // Clear all caches aggressively
            performAggressiveCacheClear(mediaId)

            // Wait before retry
            delay(RETRY_DELAY_MS)

            // Force re-prepare from position 0 to avoid range issues
            val currentIndex = player.currentMediaItemIndex
            player.seekTo(currentIndex, 0)
            player.prepare()

            Timber.tag(TAG).d("Retrying playback for $mediaId after 416 error (from position 0)")
        }
    }

    /**
     * Handles "page needs to be reloaded" errors with strict recovery.
     * This requires clearing decryption caches and getting fresh stream URLs.
     */
    private fun handlePageReloadError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob = scope.launch {
            Timber.tag(TAG).d("Handling page reload error for $mediaId")

            // Clear all caches including decryption caches
            performAggressiveCacheClear(mediaId)

            // Short delay - the cipher JS is cached after the first attempt, 
            // so the second attempt resolves quickly.
            delay(RETRY_DELAY_MS)

            // Re-prepare the player
            val currentPosition = player.currentPosition
            val currentIndex = player.currentMediaItemIndex
            player.seekTo(currentIndex, currentPosition)
            player.prepare()

            Timber.tag(TAG).d("Retrying playback for $mediaId after page reload error")
        }
    }

    /**
     * Handles expired URL (403) errors by clearing caches and retrying.
     */
    private fun handleExpiredUrlError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        // Clear the cached URL
        songUrlCache.remove(mediaId)
        Timber.tag(TAG).d("Cleared cached URL for $mediaId")



        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RETRY_DELAY_MS)

            // Seek to current position to force URL re-resolution
            val currentPosition = player.currentPosition
            val currentIndex = player.currentMediaItemIndex
            player.seekTo(currentIndex, currentPosition)
            player.prepare()

            Timber.tag(TAG).d("Retrying playback for $mediaId after 403 error")
        }
    }

    /**
     * Handles generic IO errors with recovery attempt.
     */
    private fun handleGenericIOError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob = scope.launch {
            performAggressiveCacheClear(mediaId)
            delay(RETRY_DELAY_MS)

            val currentPosition = player.currentPosition
            val currentIndex = player.currentMediaItemIndex
            player.seekTo(currentIndex, currentPosition)
            player.prepare()

            Timber.tag(TAG).d("Retrying playback for $mediaId after generic IO error")
        }
    }

    /**
     * Handles final failure when all recovery attempts have been exhausted.
     */
    private fun handleFinalFailure() {
        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            Timber.tag(TAG).d("All recovery attempts exhausted, auto-skipping to next track")
            skipOnError()
        } else {
            Timber.tag(TAG).d("All recovery attempts exhausted, stopping playback")
            stopOnError()
        }
    }

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
        super.onDeviceVolumeChanged(volume, muted)
        val pauseOnMute = dataStore.get(PauseOnMute, false)

        if ((volume == 0 || muted) && pauseOnMute) {
            if (player.isPlaying) {
                wasPlayingBeforeVolumeMute = true
                isPausedByVolumeMute = true
                player.pause()
            }
        } else if (volume > 0 && !muted && pauseOnMute) {
            if (wasPlayingBeforeVolumeMute && !player.isPlaying && castConnectionHandler?.isCasting?.value != true) {
                wasPlayingBeforeVolumeMute = false
                isPausedByVolumeMute = false
                player.play()
            }
        }
    }

    private fun createCacheDataSource(writeCache: Boolean = true): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .apply { if (!writeCache) setCacheWriteDataSinkFactory(null) }
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .apply {
                                        if (!writeCache) callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                                            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                    }
                                    .dns(object : Dns {
                                        override fun lookup(hostname: String): List<InetAddress> {
                                            val addresses = Dns.SYSTEM.lookup(hostname)
                                            return when (this@MusicService.ipVersion) {
                                                IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                                                IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                                                IpVersion.AUTO -> addresses
                                            }
                                        }
                                    })
                                    .proxy(YouTube.proxy)
                                    .proxyAuthenticator { _, response ->
                                        YouTube.proxyAuth?.let { auth ->
                                            response.request.newBuilder()
                                                .header("Proxy-Authorization", auth)
                                                .build()
                                        } ?: response.request
                                    }
                                    .build(),
                            ),
                        ),
                    ),
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    // Flag to prevent queue saving during silence skip operations
    private var isSilenceSkipping = false

    private fun handleLongSilenceDetected() {
        if (!instantSilenceSkipEnabled.value) return
        if (silenceSkipJob?.isActive == true) return

        silenceSkipJob = scope.launch {
            // Debounce so short fades or transitions do not trigger a jump.
            delay(200)
            performInstantSilenceSkip()
        }
    }

    private suspend fun performInstantSilenceSkip() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (duration <= INSTANT_SILENCE_SKIP_STEP_MS) return

        isSilenceSkipping = true
        try {
            var hops = 0
            val silenceProcessor = playerSilenceProcessors[player] ?: return
            while (coroutineContext.isActive && instantSilenceSkipEnabled.value && silenceProcessor.isCurrentlySilent()) {
                val current = player.currentPosition
                val target = (current + INSTANT_SILENCE_SKIP_STEP_MS).coerceAtMost(duration - 500)

                if (target <= current) break

                // Reset silence tracking before seeking to prevent immediate re-trigger
                silenceProcessor.resetTracking()
                player.seekTo(target)
                hops++

                if (hops >= 80 || target >= duration - 500) break

                delay(INSTANT_SILENCE_SKIP_SETTLE_MS)
            }
            if (hops > 0) {
                Timber.tag(TAG).d("Silence skip: jumped $hops times")
            }
        } finally {
            isSilenceSkipping = false
        }
    }

    private fun updateDiscordRPC(song: Song, showFeedback: Boolean = false) {
        val useDetails = dataStore.get(DiscordUseDetailsKey, false)
        val advancedMode = dataStore.get(DiscordAdvancedModeKey, false)

        val status = if (advancedMode) dataStore.get(DiscordStatusKey, "online") else "online"
        val b1Text = if (advancedMode) dataStore.get(DiscordButton1TextKey, "") else ""
        val b1Visible = if (advancedMode) dataStore.get(DiscordButton1VisibleKey, true) else true
        val b2Text = if (advancedMode) dataStore.get(DiscordButton2TextKey, "") else ""
        val b2Visible = if (advancedMode) dataStore.get(DiscordButton2VisibleKey, true) else true
        val activityType = if (advancedMode) dataStore.get(DiscordActivityTypeKey, "listening") else "listening"
        val activityName = if (advancedMode) dataStore.get(DiscordActivityNameKey, "") else ""

        discordUpdateJob?.cancel()
        discordUpdateJob = scope.launch {
            discordRpc?.updateSong(
                song,
                player.currentPosition,
                player.playbackParameters.speed,
                useDetails,
                status,
                b1Text,
                b1Visible,
                b2Text,
                b2Visible,
                activityType,
                activityName
            )?.onFailure {
                // Rate limited or error
                if (showFeedback) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@MusicService, "Discord RPC update failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun createDataSourceFactory(pinned:AnalysisSource?=null): DataSource.Factory {
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val nativeEpoch=nativeSources.epoch
            val mediaId = dataSpec.key ?: error("No media id")
            if(pinned!=null && mediaId==pinned.cacheKey)return@Factory dataSpec.withUri(pinned.uri)
            if(autoMixRequested && autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL && dataSpec.uri.scheme=="file") {
                val file=java.io.File(requireNotNull(dataSpec.uri.path))
                val key="$mediaId|local|${file.length()}|${file.lastModified()}|${dataSpec.uri}"
                nativeSources.remember(mediaId,AnalysisSource(dataSpec.uri,mediaId,file.length(),key,analysisDataSourceFactory,true),nativeEpoch)
                return@Factory dataSpec
            }
            fun resolved(spec:DataSpec):DataSpec {
                if(autoMixRequested && autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL) {
                    val format=runBlocking(Dispatchers.IO){database.format(mediaId).first()}
                    if(format!=null && format.contentLength>0)nativeSources.remember(mediaId,AnalysisSource(spec.uri,mediaId,format.contentLength,
                        "$mediaId|${format.itag}|${format.contentLength}|${format.codecs}|${format.sampleRate}|${spec.uri}",analysisDataSourceFactory),nativeEpoch)
                }
                return spec
            }

            // Check if we need to bypass cache for quality change
            val shouldBypassCache = bypassCacheForQualityChange.contains(mediaId)

            if (!shouldBypassCache) {
                val contentLength = runBlocking(Dispatchers.IO) {
                    database.song(mediaId).first()?.format?.contentLength
                }
                val requiredLength = when {
                    dataSpec.length >= 0 -> dataSpec.length
                    contentLength != null -> (contentLength - dataSpec.position).coerceAtLeast(1)
                    else -> CHUNK_LENGTH
                }

                if (downloadCache.isCached(mediaId, dataSpec.position, requiredLength) ||
                    playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)
                ) {
                    scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                    if(autoMixRequested && autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL && contentLength!=null && contentLength>0 &&
                        (downloadCache.isCached(mediaId,0,contentLength) || playerCache.isCached(mediaId,0,contentLength))) {
                        val format=runBlocking(Dispatchers.IO){database.format(mediaId).first()}
                        if(format!=null)nativeSources.remember(mediaId,AnalysisSource(dataSpec.uri,mediaId,contentLength,
                            "$mediaId|${format.itag}|${format.contentLength}|${format.codecs}|${format.sampleRate}|cached",analysisDataSourceFactory,true),nativeEpoch)
                    }
                    return@Factory dataSpec
                }

                songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                    scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                    return@Factory resolved(dataSpec.withUri(it.first.toUri()))
                }
            } else {
                Timber.tag("MusicService").i("BYPASSING CACHE for $mediaId due to quality change")
            }

            Timber.tag("MusicService").i("FETCHING STREAM: $mediaId | quality=$audioQuality")
            val playbackData = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    context = this@MusicService,
                )
            }.getOrElse { throwable ->
                when (throwable) {
                    is PlaybackException -> throw throwable

                    is java.net.ConnectException, is java.net.UnknownHostException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        )
                    }

                    is java.net.SocketTimeoutException -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )
                    }

                    else -> throw PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            }

            val nonNullPlayback = requireNotNull(playbackData) {
                getString(R.string.error_unknown)
            }
            run {
                val format = nonNullPlayback.format
                val loudnessDb = nonNullPlayback.audioConfig?.loudnessDb
                val perceptualLoudnessDb = nonNullPlayback.audioConfig?.perceptualLoudnessDb

                Timber.tag(TAG).d("Storing format for $mediaId with loudnessDb: $loudnessDb, perceptualLoudnessDb: $perceptualLoudnessDb")
                if (loudnessDb == null && perceptualLoudnessDb == null) {
                    Timber.tag(TAG).w("No loudness data available from YouTube for video: $mediaId")
                }

                database.query {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = format.itag,
                            mimeType = format.mimeType.split(";")[0],
                            codecs = format.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "mp3",
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength ?: 0L,
                            loudnessDb = loudnessDb,
                            perceptualLoudnessDb = perceptualLoudnessDb,
                            playbackUrl = nonNullPlayback.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                        )
                    )
                }
                scope.launch(Dispatchers.IO) { recoverSong(mediaId, nonNullPlayback) }

                // Clear bypass flag now that we've fetched fresh stream
                if (bypassCacheForQualityChange.remove(mediaId)) {
                    Timber.tag("MusicService").d("Cleared bypass cache flag for $mediaId after fresh fetch")
                }

                val streamUrl = nonNullPlayback.streamUrl

                songUrlCache[mediaId] =
                    streamUrl to System.currentTimeMillis() + (nonNullPlayback.streamExpiresInSeconds * 1000L)
                return@Factory resolved(dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH))
            }
        }
    }

    private fun createMediaSourceFactory(pinned:AnalysisSource?=null) =
        DefaultMediaSourceFactory(
            createDataSourceFactory(pinned),
            ExtractorsFactory {
                if(autoMixRequested && autoMixEngineMode==AutoMixEngineMode.NATIVE_DJ_EXPERIMENTAL)
                    androidx.media3.extractor.DefaultExtractorsFactory().createExtractors()
                else arrayOf(
                    MatroskaExtractor(),        // .webm / Opus
                    FragmentedMp4Extractor(),   // fragmented .mp4 / AAC (YouTube)
                    Mp4Extractor(),             // regular .mp4 / AAC (JioSaavn)
                )
            },
        )

    private fun createRenderersFactory(
        eqProcessor: CustomEqualizerAudioProcessor,
        silenceProcessor: SilenceDetectorAudioProcessor,
        mixProcessor: AutoMixAudioProcessor,
        autoMixDeck: Boolean,
    ) =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink
                .Builder(this@MusicService)
                .setEnableFloatOutput(enableFloatOutput && !autoMixDeck)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams && !autoMixDeck)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        // 2. Inject processor into audio pipeline
                        arrayOf(
                            eqProcessor,
                            silenceProcessor,
                            mixProcessor,
                        ),
                        SilenceSkippingAudioProcessor(2_000_000, 20_000, 256),
                        SonicAudioProcessor(),
                    ),
                ).build()
        }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val historyDurationMs = dataStore[HistoryDuration]?.times(1000f) ?: 30000f

        if (playbackStats.totalPlayTimeMs >= historyDurationMs &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
                }
            }
        }

        if (playbackStats.totalPlayTimeMs >= historyDurationMs) {
            CoroutineScope(Dispatchers.IO).launch {
                // Must fetch a FRESH playback tracking object. Cached `playbackUrl`s from the database
                // have expired security nonces/signatures and will result in YouTube silently ignoring the
                // history registration despite returning an HTTP 200/204 response.
                val freshPlayerResponse = YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null).getOrNull()
                
                val playbackUrl = freshPlayerResponse?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                val watchtimeUrl = freshPlayerResponse?.playbackTracking?.videostatsWatchtimeUrl?.baseUrl
                
                playbackUrl?.let { baseUrl ->
                    YouTube.registerPlayback(null, baseUrl)
                        .onSuccess {
                            // Also optionally ping watchtime to be absolutely sure the view registers
                            watchtimeUrl?.let { wtUrl -> 
                                YouTube.registerPlayback(null, wtUrl)
                            }
                            playbackRegistered.tryEmit(mediaItem.mediaId)
                        }
                        .onFailure {
                            reportException(it)
                        }
                }
            }
        }
    }

    private fun saveQueueToDisk() {
        if (player.mediaItemCount == 0) {
            Timber.tag(TAG).d("Skipping queue save - no media items")
            return
        }

        try {
            // Save current queue with proper type information
            val persistQueue = currentQueue.toPersistQueue(
                title = queueTitle,
                items = player.mediaItems.mapNotNull { it.metadata },
                mediaItemIndex = player.currentMediaItemIndex,
                position = player.currentPosition
            )

            val persistAutomix =
                PersistQueue(
                    title = "automix",
                    items = automixItems.value.mapNotNull { it.metadata },
                    mediaItemIndex = 0,
                    position = 0,
                )

            // Save player state
            val persistPlayerState = PersistPlayerState(
                playWhenReady = player.playWhenReady,
                repeatMode = player.repeatMode,
                shuffleModeEnabled = player.shuffleModeEnabled,
                volume = playerVolume.value,
                currentPosition = player.currentPosition,
                currentMediaItemIndex = player.currentMediaItemIndex,
                playbackState = player.playbackState
            )

            runCatching {
                filesDir.resolve(PERSISTENT_QUEUE_FILE).outputStream().use { fos ->
                    ObjectOutputStream(fos).use { oos ->
                        oos.writeObject(persistQueue)
                    }
                }
                Timber.tag(TAG).d("Queue saved successfully")
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to save queue")
                reportException(it)
            }

            runCatching {
            filesDir.resolve(PERSISTENT_AUTOMIX_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos ->
                        oos.writeObject(persistAutomix)
                    }
                }
                Timber.tag(TAG).d("Automix saved successfully")
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to save automix")
                reportException(it)
            }

            runCatching {
                filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).outputStream().use { fos ->
                    ObjectOutputStream(fos).use { oos ->
                        oos.writeObject(persistPlayerState)
                    }
                }
                Timber.tag(TAG).d("Player state saved successfully")
            }.onFailure {
                Timber.tag(TAG).e(it, "Failed to save player state")
                reportException(it)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during queue save operation")
            reportException(e)
        }
    }

    override fun onDestroy() {
        serviceDestroyed = true
        isRunning = false
        autoMixTicker?.cancel()
        nativeAutoMix?.close();nativeAutoMix=null
        nativePlayback?.close();nativePlayback=null;nativeSources.clear()
        intelligentAutoMix?.close()
        intelligentAutoMix = null
        crossfadeTriggerJob?.cancel()
        crossfadeJob?.cancel()
        if (fadingPlayer != null) cleanupCrossfade()
        secondaryPlayer?.let { pending ->
            pending.removeListener(secondaryPlayerListener)
            playerEqProcessors.remove(pending)?.let(equalizerService::removeAudioProcessor)
            playerSilenceProcessors.remove(pending)
            autoMixProcessors.remove(pending)
            releaseAudioOutput(pending)
        }
        secondaryPlayer = null

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        castConnectionHandler?.release()
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
        if (discordRpc?.isRpcRunning() == true) {
            discordRpc?.closeRPC()
        }
        discordRpc = null
        connectivityObserver.unregister()
        abandonAudioFocus()
        releaseLoudnessEnhancer()
        mediaSession.release()
        player.removeListener(this)
        player.removeListener(sleepTimer)
        playerSilenceProcessors.remove(player)
        playerEqProcessors.remove(player)?.let(equalizerService::removeAudioProcessor)
        autoMixProcessors.remove(player)
        autoMixDecks.clear()
        releaseAudioOutput(player)
        effectSessions.closeAll()
        discordUpdateJob?.cancel()
        scope.coroutineContext[Job]?.cancel() // queue/state were saved synchronously above
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MusicWidgetReceiver.ACTION_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
                updateWidgetUI(player.isPlaying)
            }
            MusicWidgetReceiver.ACTION_LIKE -> {
                toggleLike()
            }
            MusicWidgetReceiver.ACTION_NEXT -> {
                if (!manualSkipToNextWithCrossfade()) {
                    player.seekToNext()
                }
                updateWidgetUI(player.isPlaying)
            }
            MusicWidgetReceiver.ACTION_PREVIOUS -> {
                if (!manualSkipToPreviousWithCrossfade()) {
                    player.seekToPrevious()
                }
                updateWidgetUI(player.isPlaying)
            }
            MusicWidgetReceiver.ACTION_UPDATE_WIDGET -> {
                updateWidgetUI(player.isPlaying)
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Updates all app widgets with current playback state
     */
    private fun updateWidgetUI(isPlaying: Boolean) {
        scope.launch {
            try {
                val songData = currentSong.value
                val song = songData?.song
                val songTitle = song?.title ?: getString(R.string.no_song_playing)
                val artistName = songData?.artists?.joinToString(", ") { it.name } ?: getString(R.string.tap_to_open)
                val isLiked = songData?.song?.liked == true

                widgetManager.updateWidgets(
                    title = songTitle,
                    artist = artistName,
                    artworkUri = song?.thumbnailUrl,
                    isPlaying = isPlaying,
                    isLiked = isLiked,
                    duration = if (player.duration != C.TIME_UNSET) player.duration else 0,
                    currentPosition = player.currentPosition
                )
            } catch (e: Exception) {
                // Widget not added to home screen or other error
            }
        }
    }

    private var widgetUpdateJob: Job? = null

    private fun startWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    updateWidgetUI(true)
                }
                delay(200)
            }
        }
    }

    private fun stopWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = null
    }

    private fun shareSong() {
        val songData = currentSong.value
        val songId = songData?.song?.id ?: return

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=$songId")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(shareIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Get the stream URL for a given media ID.
     * This is used for Google Cast to send the audio URL to Chromecast.
     */
    suspend fun getStreamUrl(mediaId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val playbackData = YTPlayerUtils.playerResponseForPlayback(
                    videoId = mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    context = this@MusicService,
                ).getOrNull()
                playbackData?.streamUrl
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to get stream URL for Cast")
                null
            }
        }
    }

    /**
     * Initialize Google Cast support
     */
    private fun initializeCast() {
        if (dataStore.get(com.music.vivi.constants.EnableGoogleCastKey, true)) {
            try {
                castConnectionHandler = CastConnectionHandler(this, scope, this)
                castConnectionHandler?.initialize()
                timber.log.Timber.d("Google Cast initialized")
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to initialize Google Cast")
            }
        }
    }


    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            if(!nativePromoting)invalidateNative(NativeDjExecutionReason.USER_SEEK)
            intelligentAutoMix?.invalidate(com.music.vivi.playback.automix.AutoMixFailure.SEEK)
            if (isCrossfading) {
                crossfadeJob?.cancel()
                cleanupCrossfade()
                player.volume=if(isMuted.value) 0f else playerVolume.value
            }
        }
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            scheduleCrossfade()
        }
    }

    /** Read-only internal inspection used by actual-service instrumentation, never exported raw. */
    internal fun nativeServiceInspection()=NativeServiceInspection(
        autoMixEngineMode, nativeAutoMix?.nativeSelected==true,legacyAutoMixEnabled,
        nativeAutoMix?.currentExecutor?.report(),outputIdentities.size,player.currentMediaItem?.mediaId,
        player.volume,nativePlayback?.incomingVolume(),nativePlayback?.incomingPlaying(),
        outputIdentities.values.map(effectSessions::session).filter {it>0}.toSet(),nativePlayback?.incomingSessionId())

    private fun invalidateNative(reason:NativeDjExecutionReason) {
        if(nativePromoting)return
        nativeStartup=null;nativeStartCommand=null;nativePairKey=null;nativePairGeneration++
        if(!nativePromoting)nativeAutoMix?.invalidate(reason)
    }
    private fun observeNativeStartup() {
        val command=nativeStartCommand ?: return
        if(command.first!==player || !player.isPlaying)return
        val progress=player.currentPosition*1000-command.third
        if(progress in 1..100_000) {
            val source=nativeSources[player.currentMediaItem?.mediaId] ?: return
            nativeStartup=NativeDjStartupObservation(AnalysisSourceIdentity(source.cacheKey,source.identity),nativePairGeneration,
                command.second,command.third,System.nanoTime()/1000,player.currentPosition*1000)
            nativeStartCommand=null
        } else if(progress>100_000)nativeStartCommand=null // coarse/late observation: no guessed lead
    }
    /** Exact service source/queue boundary; no additional focus or output observers. */
    private inner class NativeServiceHost:NativeAutoMixServiceHost<AnalysisSource> {
        override fun pair():NativeServicePair<AnalysisSource>? {
            if(!playerInitialized.value || serviceDestroyed || intelligentAutoMix?.mixing==true)return null
            val current=player.currentMediaItem ?: return null
            val nextIndex=player.nextMediaItemIndex
            if(nextIndex==C.INDEX_UNSET || nextIndex==player.currentMediaItemIndex || player.duration<=0)return null
            val next=player.getMediaItemAt(nextIndex)
            if(current.localConfiguration?.drmConfiguration!=null || next.localConfiguration?.drmConfiguration!=null)return null
            if(current.mediaId==next.mediaId)return null
            val a=nativeSources[current.mediaId] ?: return null
            if(!a.fullyCached && a.uri.scheme in listOf("http","https") && songUrlCache[current.mediaId]?.first!=a.uri.toString())return null
            val currentUri=current.localConfiguration?.uri
            if(currentUri?.scheme=="file" && a.uri!=currentUri)return null
            val b=nativeSources[next.mediaId] ?: run {
                if(prefetchJob?.isActive!=true && (autoMixResolveNextId!=next.mediaId || System.currentTimeMillis()>=autoMixResolveRetryAt)) {
                    autoMixResolveNextId=next.mediaId;autoMixResolveRetryAt=System.currentTimeMillis()+30_000;prefetchNextTrack()
                }
                return null
            }
            val nextUri=next.localConfiguration?.uri
            if(nextUri?.scheme=="file" && b.uri!=nextUri) {prefetchNextTrack();return null}
            if(!b.fullyCached && nextUri?.scheme in listOf("http","https") && nextUri!=b.uri)return null
            if(b.uri.scheme in listOf("http","https")) {
                val resolved=songUrlCache[next.mediaId]
                if(resolved==null || resolved.first!=b.uri.toString() || resolved.second<=System.currentTimeMillis()) {
                    nativeSources.remove(next.mediaId);return null
                }
            }
            for(source in listOf(a,b))if(source.uri.scheme=="file") {
                val f=java.io.File(requireNotNull(source.uri.path))
                if(source.identity!="${source.cacheKey}|local|${f.length()}|${f.lastModified()}|${source.uri}")return null
            }
            if(bypassCacheForQualityChange.contains(current.mediaId) || bypassCacheForQualityChange.contains(next.mediaId))return null
            fun identity(source:AnalysisSource)=AnalysisSourceIdentity(source.cacheKey,source.identity)
            fun binding(item:MediaItem,source:AnalysisSource)=NativeQueuedSource(identity(source),
                item.localConfiguration?.uri?.toString(),item.localConfiguration?.customCacheKey,item.localConfiguration?.mimeType)
            val key=NativeQueueBinding(binding(current,a),binding(next,b),player.repeatMode,player.shuffleModeEnabled)
            if(nativePairKey!=key) {nativePairKey=key;nativePairGeneration++;nativeStartup=null}
            val outDuration=player.duration*1000
            val inDuration=(next.metadata?.duration ?: 0)*1_000_000L
            if(inDuration<=0)return null
            return NativeServicePair(NativeDjQueue(nativePairGeneration,identity(a),identity(b)),
                PlanningTrack(identity(a),outDuration,AudioIntervalUs((outDuration-32_000_000).coerceAtLeast(0),outDuration)),
                PlanningTrack(identity(b),inDuration,AudioIntervalUs(0,minOf(inDuration,32_000_000))),
                a,b,player.currentPosition*1000,player.isPlaying,
                a.fullyCached && a.uri.scheme!="file",b.fullyCached && b.uri.scheme!="file")
        }
        override fun incompatibility():String?=when {
            !AutoMixServiceHost().allowed -> "UNSUPPORTED_PLAYER_CONFIGURATION"
            player.playbackParameters.speed!=1f || player.playbackParameters.pitch!=1f -> "UNSUPPORTED_RATE"
            isNextItemGapless() || player.repeatMode==Player.REPEAT_MODE_ONE -> "GAPLESS_OR_REPEAT_ONE"
            isCrossfading || secondaryPlayer!=null || fadingPlayer!=null -> "OWNERSHIP_BUSY"
            else -> null
        }
        override fun legacyEnabled(enabled:Boolean) {
            legacyAutoMixEnabled=enabled
            intelligentAutoMix?.configure(enabled,nativePerformance)
        }
        override fun execute(plan:NativeTransitionPlan,outgoingLevel:NativeDjLevel?,incomingLevel:NativeDjLevel?):NativeDjTransitionExecutor {
            check(intelligentAutoMix?.mixing!=true)
            val expected=pair() ?: error("No resolved pair")
            require(expected.queue==NativeDjQueue(plan.generation,plan.outgoing.source,plan.incoming.source))
            val original=player
            val nextItem=player.getMediaItemAt(player.nextMediaItemIndex).buildUpon()
                .setUri(expected.incomingSource.uri).setCustomCacheKey(expected.incomingSource.cacheKey).build()
            val playback=Media3NativeDjPlayback(original,plan.outgoing,plan.incoming,nextItem,
                currentQueue={pair()?.queue ?: NativeDjQueue(-1,plan.outgoing.source,plan.incoming.source)},
                createIncoming={createExoPlayer(publish=false,autoMixDeck=true,pinned=expected.incomingSource)},
                commitPromotion={queue,incoming -> promoteNative(queue,incoming,original)},releasePlayer=::releaseAudioOutput,
                userVolume={if(isMuted.value)0f else playerVolume.value},focusGain={autoMixFocusGain},
                exclusiveControlAvailable={intelligentAutoMix?.mixing!=true && secondaryPlayer==null && !isCrossfading},
                startTiming={nativeStartup},allowServiceQueue=true)
            nativePlayback=playback
            return playback.execute(plan,outgoingLevel,incomingLevel)
        }
        private fun promoteNative(queue:NativeDjQueue,incoming:ExoPlayer,original:ExoPlayer):Boolean {
            if(player!==original || pair()?.queue!=queue)return false
            val items=(0 until original.mediaItemCount).map(original::getMediaItemAt)
            val index=original.nextMediaItemIndex
            if(index==C.INDEX_UNSET)return false
            nativePromoting=true
            try {
                // addMediaItems preserves the already-playing item/position, no seek/restart.
                if(index>0)incoming.addMediaItems(0,items.take(index))
                if(index+1<items.size)incoming.addMediaItems(items.drop(index+1))
                incoming.repeatMode=original.repeatMode;incoming.shuffleModeEnabled=original.shuffleModeEnabled
                if(original.shuffleModeEnabled)getCurrentShuffleOrderIndices(original)?.let {
                    incoming.setShuffleOrder(DefaultShuffleOrder(it,System.currentTimeMillis()))
                }
                releaseLoudnessEnhancer()
                original.removeListener(this@MusicService);original.removeListener(sleepTimer)
                player=incoming;promoteAudioOutput(incoming)
                player.addListener(this@MusicService);player.addListener(sleepTimer)
                sleepTimer.player=player;mediaSession.player=player;_playerFlow.value=player
                currentMediaMetadata.value=player.currentMetadata
                onMediaItemTransition(player.currentMediaItem,Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                sleepTimer.notifySongTransition();updateNotification();updateWidgetUI(player.isPlaying)
                return true
            } finally {nativePromoting=false;nativeStartup=null;nativeStartCommand=null}
        }
        override fun pause(){nativePlayback?.pauseForUserOrFocus()}
        override fun resume(){
            if(nativeAutoMix?.currentExecutor?.status?.stage==NativeDjExecutionStage.PAUSED) {
                // The service callback arrives after the primary's playWhenReady changed.
                // Restore the paired paused state before the executor resumes both decks.
                player.pause();nativePlayback?.resumeAfterUserOrFocus()
            }
        }
        override fun refreshVolume(){nativePlayback?.refreshUserVolume()}
        override fun cancel(reason:NativeDjExecutionReason){nativePlayback?.invalidate(reason);nativePlayback?.close();nativePlayback=null}
        override fun status(value:AutoMixStatus){
            if(value.phase in setOf(AutoMixPhase.UNAVAILABLE,AutoMixPhase.WAITING_SOURCE))nativeFallbackReason=value.reason
            else if(value.nativePlan!=null)nativeFallbackReason=null
            _autoMixStatus.value=nativeServiceVisibleStatus(value,intelligentAutoMix?.status?.value,legacyAutoMixEnabled)
        }
    }

    /** AutoMix's decks use the existing Media3 queue/source/equalizer infrastructure. */
    private inner class AutoMixServiceHost : AutoMixHost {
        private val analysisDecoder = AndroidWindowDecoder() // Immutable LEGACY production selection.
        private val analysisPipeline = com.music.vivi.playback.automix.WindowAnalysisPipeline(
            analysisDecoder, com.music.vivi.playback.automix.LegacyKotlinAnalysisEngine(),
            analysisDecoder.preprocessing.version)
        override val analysisProfile get() = analysisPipeline.profile
        override val allowed: Boolean get() = autoMixRequested && playerInitialized.value &&
            castConnectionHandler?.isCasting?.value != true && listenTogetherManager.roomState.value == null &&
            !player.skipSilenceEnabled && !sleepTimer.pauseWhenSongEnd && !player.isCurrentMediaItemLive &&
            kotlin.math.abs(player.playbackParameters.pitch - 1f) < 0.001f &&
            autoMixProcessors[player]?.isActive == true

        override fun snapshot(): MixSnapshot? {
            val current = player.currentMediaItem ?: return null
            val index = player.nextMediaItemIndex
            if (index == C.INDEX_UNSET || index == player.currentMediaItemIndex || player.duration == C.TIME_UNSET ||
                isNextItemGapless() || current.localConfiguration?.drmConfiguration != null) return null
            val next = player.getMediaItemAt(index)
            if (next.localConfiguration?.drmConfiguration != null) return null
            fun track(item: MediaItem, duration: Long): MixTrack {
                val uri = songUrlCache[item.mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.first
                    ?: item.localConfiguration?.uri?.toString() ?: item.mediaId
                val format = currentSong.value?.format?.takeIf { it.id == item.mediaId }
                val rendition = format?.let { "${it.itag}:${it.contentLength}:${it.codecs}:${it.sampleRate}" } ?: "unknown"
                // Only reuse completed results when the rendition is known. Mutable local URIs and
                // upcoming tracks without a resolved format are deduplicated, but not cached.
                val cacheable = format != null && uri.toUri().scheme !in listOf("file", "content")
                return MixTrack(item.mediaId, duration,
                    "${item.mediaId}|$uri|$audioQuality|$duration|$rendition", uri, cacheable)
            }
            return MixSnapshot(track(current, player.duration), track(next, (next.metadata?.duration ?: 0) * 1000L),
                "$autoMixQueueEpoch:${player.currentMediaItemIndex}:$index:${player.repeatMode}:${player.shuffleModeEnabled}",
                player.currentPosition, player.isPlaying,
                kotlin.math.abs(player.playbackParameters.speed - 1f) < 0.001f &&
                    kotlin.math.abs(player.playbackParameters.pitch - 1f) < 0.001f)
        }

        override fun sourcesReady(snapshot: MixSnapshot): Boolean {
            fun completeCache(cache: androidx.media3.datasource.cache.Cache, id: String): Boolean {
                val size=androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(id))
                return size>0 && cache.isCached(id,0,size)
            }
            fun ready(track: MixTrack) = track.sourceUri.toUri().scheme in listOf("http","https","file","content") ||
                completeCache(downloadCache,track.id) || completeCache(playerCache,track.id)
            if (!ready(snapshot.incoming) && prefetchJob?.isActive != true &&
                (autoMixResolveNextId!=snapshot.incoming.id || System.currentTimeMillis()>=autoMixResolveRetryAt)) {
                autoMixResolveNextId=snapshot.incoming.id
                autoMixResolveRetryAt=System.currentTimeMillis()+30_000
                prefetchNextTrack()
            }
            return ready(snapshot.outgoing) && ready(snapshot.incoming)
        }

        override fun scan(track: MixTrack, startMs: Long, lengthMs: Long, deadlineNanos: Long) =
            analyzeWindow(track,startMs,lengthMs,deadlineNanos,AnalysisDetail.SCAN)
        override fun analyze(track: MixTrack, startMs: Long, lengthMs: Long, deadlineNanos: Long) =
            analyzeWindow(track,startMs,lengthMs,deadlineNanos,AnalysisDetail.DETAIL)
        override fun analyzeWithDiagnostics(track: MixTrack, startMs: Long, lengthMs: Long, deadlineNanos: Long,
            detail: AnalysisDetail, diagnostics: com.music.vivi.playback.automix.DecoderDiagnosticSink) =
            analyzeWindow(track, startMs, lengthMs, deadlineNanos, detail, diagnostics)
        override fun diagnostic(message: String, verbose: Boolean) {
            if(!verbose || com.music.vivi.BuildConfig.DEBUG) Timber.tag("TiniumAutoMix").i(message)
        }
        private fun analyzeWindow(track: MixTrack, startMs: Long, lengthMs: Long, deadlineNanos: Long, detail: AnalysisDetail,
            diagnostics: com.music.vivi.playback.automix.DecoderDiagnosticSink = com.music.vivi.playback.automix.DecoderDiagnosticSink.NONE): TrackFeatures {
            checkAnalysisCancellation(deadlineNanos)
            val format = runBlocking(Dispatchers.IO) { database.format(track.id).first() }
            val length = format?.contentLength?.takeIf { it>0 }
                ?: maxOf(androidx.media3.datasource.cache.ContentMetadata.getContentLength(downloadCache.getContentMetadata(track.id)),
                    androidx.media3.datasource.cache.ContentMetadata.getContentLength(playerCache.getContentMetadata(track.id))).coerceAtLeast(0)
            val complete = length > 0 && (downloadCache.isCached(track.id, 0, length) || playerCache.isCached(track.id, 0, length))
            val uri = track.sourceUri.toUri()
            if (!complete && uri.scheme !in listOf("http", "https", "file", "content")) {
                throw com.music.vivi.playback.automix.AnalysisUnavailable("source-unresolved")
            }
            val source = AnalysisSource(uri, track.id, length, "${track.sourceIdentity}|${format?.itag}|$length",
                analysisDataSourceFactory, complete)
            val rendition=format?.let { "${it.itag}:${it.contentLength}:${it.codecs}:${it.sampleRate}" }
            if(track.cacheable && (rendition==null || !track.sourceIdentity.contains("|$rendition")))
                throw com.music.vivi.playback.automix.AnalysisUnavailable("source-rendition-changed")
            try {
                val request = com.music.vivi.playback.automix.DecodeRequest(
                    com.music.vivi.playback.automix.AudioIntervalUs(startMs * 1000, (startMs + lengthMs) * 1000),
                    com.music.vivi.playback.automix.AnalysisSourceIdentity(track.id, source.identity))
                return com.music.vivi.playback.automix.LegacyPlanningFeatures.require(
                    analysisPipeline.analyze(source, request, detail,
                        com.music.vivi.playback.automix.AnalysisControl(deadlineNanos, decoderDiagnostics = diagnostics)))
            } catch(error: Exception) {
                val classified: Exception=when(error) {
                    is com.music.vivi.playback.automix.AnalysisUnavailable, is java.util.concurrent.CancellationException -> error
                    is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException ->
                        com.music.vivi.playback.automix.AnalysisUnavailable(if(error.responseCode in listOf(401,403)) "SOURCE_DENIED" else "SOURCE_IO_FAILURE").apply { initCause(error) }
                    is java.io.IOException -> com.music.vivi.playback.automix.AnalysisUnavailable("SOURCE_IO_FAILURE").apply { initCause(error) }
                    is IllegalArgumentException -> com.music.vivi.playback.automix.AnalysisUnavailable("UNSUPPORTED_FORMAT").apply { initCause(error) }
                    else -> com.music.vivi.playback.automix.AnalysisUnavailable("DECODER_FAILED").apply { initCause(error) }
                }
                diagnostic("event=analysis-failed pass=$detail reason=${com.music.vivi.playback.automix.AutoMixFailure.of(classified)}",true)
                throw classified // never export/log the original exception's message or URL
            }
        }

        override fun currentDeck(): MixDeck {
            player.setPauseAtEndOfMediaItems(true) // the outgoing decoder must never advance into the incoming song
            return ServiceMixDeck(player)
        }

        override fun prepareIncoming(snapshot: MixSnapshot, plan: AutoMixPlan): MixDeck? {
            val current = this.snapshot()
            if (current?.queueIdentity != snapshot.queueIdentity || current.incoming.id != snapshot.incoming.id) return null
            val nextIndex = player.nextMediaItemIndex
            val next = createExoPlayer(publish = false, autoMixDeck = true)
            val deck = ServiceMixDeck(next, plan.incomingCueMs + (plan.durationMs * plan.incomingRate).toLong() + 500)
            try {
                val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                val shuffle = if (player.shuffleModeEnabled) getCurrentShuffleOrderIndices(player) else null
                next.volume = 0f
                // Prebuffer real PCM, not zero samples. AudioTrack volume keeps this paused deck silent.
                val initial=com.music.vivi.playback.automix.djAutomation(plan,0.0).incoming
                autoMixProcessors[next]?.parameters = AutoMixAudioProcessor.Parameters(1.0,initial.bassCut,initial.trebleCut)
                next.playbackParameters = PlaybackParameters(plan.incomingRate, 1f)
                next.setMediaItems(items, nextIndex, plan.incomingCueMs)
                next.repeatMode = player.repeatMode
                next.shuffleModeEnabled = player.shuffleModeEnabled
                if (shuffle != null && shuffle.size == items.size) next.setShuffleOrder(DefaultShuffleOrder(shuffle, System.currentTimeMillis()))
                next.prepare() // paused and not published: metadata and UI remain the outgoing song
                return deck
            } catch (_: Exception) { deck.release(); return null }
        }

        override fun promote(incoming: MixDeck) {
            val next = (incoming as ServiceMixDeck).source
            releaseLoudnessEnhancer() // deck gains already control overlap levels; no second hardware boost
            player.removeListener(this@MusicService)
            player.removeListener(sleepTimer)
            player = next
            promoteAudioOutput(next)
            player.addListener(this@MusicService)
            player.addListener(sleepTimer)
            sleepTimer.player = player
            mediaSession.player = player
            _playerFlow.value = player
            currentMediaMetadata.value = player.currentMetadata
            onMediaItemTransition(player.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            sleepTimer.notifySongTransition()
            updateNotification()
            updateWidgetUI(player.isPlaying)
        }

        override fun restoreNormalAudio() {
            player.setPauseAtEndOfMediaItems(false)
            autoMixDecks[player]?.process(1.0)
            autoMixDecks.remove(player) // normal/Crossfade volume control no longer belongs to an AutoMix deck
            if (isRunning && playerInitialized.value && !serviceDestroyed) {
                player.setOffloadEnabled(dataStore.get(AudioOffload,false) && !crossfadeEnabled && !autoMixRequested)
                setupLoudnessEnhancer()
            }
        }
    }

    private inner class ServiceMixDeck(val source: ExoPlayer, private val minimumDurationMs: Long = 0) : MixDeck {
        private var released = false
        private var deckGain = 1.0
        init { autoMixDecks[source] = this }
        override val trackId get() = source.currentMediaItem?.mediaId.orEmpty()
        override val positionMs get() = source.currentPosition
        override val failed get() = released || source.playerError != null
        override val ready get() = !released && source.playbackState == Player.STATE_READY && source.playerError == null &&
            !source.isCurrentMediaItemLive && source.duration >= minimumDurationMs && autoMixProcessors[source]?.isActive == true
        override val playing get() = source.isPlaying
        override val ended get() = released || source.playbackState == Player.STATE_ENDED ||
            (source.duration > 0 && source.currentPosition >= source.duration - 30)
        override var rate: Float
            get() = source.playbackParameters.speed
            set(value) {
                if (!released && kotlin.math.abs(source.playbackParameters.speed - value) > 0.0001f)
                    source.playbackParameters = PlaybackParameters(value, source.playbackParameters.pitch)
            }
        override fun play() { if (!released) source.play() }
        override fun pause() { if (!released) source.pause() }
        override fun process(gain: Double, bassCut: Double, trebleCut: Double) {
            if (released) return
            deckGain = gain
            applyUserVolume()
            // AudioTrack's gain applies to already-buffered PCM too; filtering stays in the PCM chain.
            autoMixProcessors[source]?.parameters = AutoMixAudioProcessor.Parameters(1.0, bassCut, trebleCut)
        }
        fun applyUserVolume() {
            if (!released) source.volume = if (isMuted.value) 0f else playerVolume.value * deckGain.toFloat() * autoMixFocusGain
        }
        override fun release() {
            if (released) return
            released = true
            autoMixDecks.remove(source)
            outputFormats.remove(source)
            formatTrackIds.remove(source)
            diagnosticTrackCounts.remove(source)
            source.removeListener(this@MusicService)
            source.removeListener(sleepTimer)
            playerEqProcessors.remove(source)?.let(equalizerService::removeAudioProcessor)
            playerSilenceProcessors.remove(source)
            autoMixProcessors.remove(source)
            source.stop(); source.clearMediaItems(); releaseAudioOutput(source)
        }
    }

    private fun scheduleCrossfade() {
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        if (!crossfadeEnabled || player.duration == C.TIME_UNSET || player.duration <= crossfadeDuration) return
        if (crossfadeGapless && isNextItemGapless()) return
        if (!player.hasNextMediaItem() && player.repeatMode != REPEAT_MODE_ONE) return

        val triggerTime = player.duration - crossfadeDuration.toLong()
        val delayMs = triggerTime - player.currentPosition
        if (delayMs <= 0) return

        val targetMediaId = player.currentMediaItem?.mediaId

        crossfadeTriggerJob = scope.launch {
            delay(delayMs)
            if (isActive && player.isPlaying && player.currentMediaItem?.mediaId == targetMediaId && !sleepTimer.pauseWhenSongEnd) {
                startCrossfade()
            }
        }
    }

    private fun isNextItemGapless(): Boolean {
        val current = player.currentMediaItem?.mediaMetadata ?: return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val next = player.getMediaItemAt(nextIndex).mediaMetadata
        return current.albumTitle != null && current.albumTitle == next.albumTitle
    }

    private fun startCrossfade(trigger: CrossfadeTrigger = CrossfadeTrigger.AUTO) {
        if (autoMixRequested) return
        if (isCrossfading) return

        // Preserve player state before creating the secondary player
        // Use runBlocking to ensure we get the correct state from DataStore
        val savedRepeatMode = runBlocking { dataStore.get(RepeatModeKey, REPEAT_MODE_OFF) }
        val savedShuffleEnabled = runBlocking { dataStore.get(ShuffleModeKey, false) }

        val targetIndex = when {
            // Manual "previous" skip: crossfade back into the previous track.
            trigger == CrossfadeTrigger.MANUAL_PREVIOUS -> {
                if (!player.hasPreviousMediaItem()) return
                player.previousMediaItemIndex
            }
            // For repeat-one at the natural end of the track, crossfade back into the same track.
            trigger == CrossfadeTrigger.AUTO && savedRepeatMode == REPEAT_MODE_ONE -> player.currentMediaItemIndex
            // Natural end-of-track advance, or a manual "next" skip.
            else -> player.nextMediaItemIndex
        }
        if (targetIndex == C.INDEX_UNSET) return

        secondaryPlayer = createExoPlayer(publish = false, autoMixDeck = true)
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)

        val itemCount = player.mediaItemCount
        val items = mutableListOf<MediaItem>()
        // Copy entire queue history + future
        for (i in 0 until itemCount) {
            items.add(player.getMediaItemAt(i))
        }

        // Capture the primary's current shuffle traversal before the swap.
        // Regenerating a fresh random order here was what caused shuffle to
        // re-randomize on every skip / song selection.
        val preservedShuffleOrder =
            if (savedShuffleEnabled) getCurrentShuffleOrderIndices(player) else null

        secPlayer.setMediaItems(items)
        // Seek to target track (next track, or current track for repeat-one)
        secPlayer.seekTo(targetIndex, 0)
        secPlayer.volume = 0f

        // Copy repeat and shuffle state to the new player
        secPlayer.repeatMode = savedRepeatMode
        secPlayer.shuffleModeEnabled = savedShuffleEnabled

        // Replay the captured shuffle order (same items, same order) instead
        // of generating a new random one. Track whether it was actually applied
        // so we can fall back to a fresh order below if it wasn't.
        val restoredShuffleOrder = savedShuffleEnabled &&
            preservedShuffleOrder != null &&
            preservedShuffleOrder.size == secPlayer.mediaItemCount

        if (restoredShuffleOrder) {
            secPlayer.setShuffleOrder(
                DefaultShuffleOrder(preservedShuffleOrder!!, System.currentTimeMillis())
            )
        }

        secPlayer.prepare()
        secPlayer.playWhenReady = true

        performCrossfadeSwap()

        // If we couldn't carry the order over (e.g. a size mismatch), build a
        // fresh shuffle order on the new primary player as a fallback.
        if (savedShuffleEnabled && !restoredShuffleOrder) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
    }

    /**
     * Attempts a crossfaded manual skip to the next track, in response to the
     * user pressing "next" (in-app button/gesture, notification, widget,
     * Bluetooth/headset button, Android Auto, etc.) rather than the track
     * ending naturally.
     *
     * Returns `true` if the crossfade transition was started, meaning the
     * caller should NOT also perform an instant [Player.seekToNext]. Returns
     * `false` when crossfade (or crossfade-on-manual-skip) is disabled, when
     * a crossfade is already in progress, or when there is no next item to
     * crossfade into — in which case the caller should fall back to its
     * normal instant-skip behavior.
     */
    fun manualSkipToNextWithCrossfade(): Boolean {
        if (!crossfadeEnabled || !crossfadeManualSkipEnabled) return false
        if (isCrossfading) return false
        if (castConnectionHandler?.isCasting?.value == true) return false
        if (!player.hasNextMediaItem()) return false
        if (player.duration == C.TIME_UNSET) return false
        if (crossfadeGapless && isNextItemGapless()) return false

        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        startCrossfade(CrossfadeTrigger.MANUAL_NEXT)
        return isCrossfading
    }

    /**
     * Attempts a crossfaded manual skip to the previous track. Same contract
     * as [manualSkipToNextWithCrossfade], but for the "previous" direction.
     * Callers that implement a "restart the current song if we're more than
     * a few seconds in" behavior should only call this from the branch that
     * actually moves to the previous track, not the restart branch.
     */
    fun manualSkipToPreviousWithCrossfade(): Boolean {
        if (!crossfadeEnabled || !crossfadeManualSkipEnabled) return false
        if (isCrossfading) return false
        if (castConnectionHandler?.isCasting?.value == true) return false
        if (!player.hasPreviousMediaItem()) return false
        if (player.duration == C.TIME_UNSET) return false

        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        startCrossfade(CrossfadeTrigger.MANUAL_PREVIOUS)
        return isCrossfading
    }

    /**
     * Attempts a crossfaded jump to an arbitrary track already loaded in the
     * current queue — e.g. tapping an upcoming song in the queue/playlist view
     * (which otherwise does an instant [Player.seekToDefaultPosition] and cuts
     * the audio). Behaves like [manualSkipToNextWithCrossfade] but targets an
     * explicit media-item index instead of the next/previous one.
     *
     * Returns `true` if the crossfade was started, meaning the caller should
     * NOT also perform an instant seek. Returns `false` when crossfade (or
     * crossfade-on-manual-skip) is disabled, when a crossfade is already in
     * progress, when [targetIndex] is the current item or out of range, or when
     * the player isn't actually playing — in which case the caller should fall
     * back to its normal instant-seek behavior.
     */
    fun manualSeekToIndexWithCrossfade(targetIndex: Int): Boolean {
        if (!crossfadeEnabled || !crossfadeManualSkipEnabled) return false
        if (isCrossfading) return false
        if (castConnectionHandler?.isCasting?.value == true) return false
        if (targetIndex < 0 || targetIndex >= player.mediaItemCount) return false
        if (targetIndex == player.currentMediaItemIndex) return false
        if (player.duration == C.TIME_UNSET) return false
        if (!player.isPlaying) return false

        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        startCrossfadeToIndex(targetIndex)
        return isCrossfading
    }

    /**
     * Like [startCrossfade], but crossfades into an explicit [targetIndex]
     * within the current queue (the item the user tapped in the queue view)
     * instead of the next/previous item. Copies the primary player's existing
     * (already-resolved) media items onto the secondary player so the target
     * track can start buffering immediately, then swaps and fades.
     */
    private fun startCrossfadeToIndex(targetIndex: Int) {
        if (autoMixRequested) return
        if (isCrossfading) return
        if (targetIndex < 0 || targetIndex >= player.mediaItemCount) return

        val savedRepeatMode = runBlocking { dataStore.get(RepeatModeKey, REPEAT_MODE_OFF) }
        val savedShuffleEnabled = runBlocking { dataStore.get(ShuffleModeKey, false) }

        secondaryPlayer = createExoPlayer(publish = false, autoMixDeck = true)
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)

        val itemCount = player.mediaItemCount
        val items = mutableListOf<MediaItem>()
        for (i in 0 until itemCount) {
            items.add(player.getMediaItemAt(i))
        }

        // Capture the primary's current shuffle traversal before the swap so we
        // can replay it on the secondary player instead of re-randomizing.
        val preservedShuffleOrder =
            if (savedShuffleEnabled) getCurrentShuffleOrderIndices(player) else null

        secPlayer.setMediaItems(items)
        secPlayer.seekTo(targetIndex, 0)
        secPlayer.volume = 0f

        secPlayer.repeatMode = savedRepeatMode
        secPlayer.shuffleModeEnabled = savedShuffleEnabled

        val restoredShuffleOrder = savedShuffleEnabled &&
            preservedShuffleOrder != null &&
            preservedShuffleOrder.size == secPlayer.mediaItemCount

        if (restoredShuffleOrder) {
            secPlayer.setShuffleOrder(
                DefaultShuffleOrder(preservedShuffleOrder!!, System.currentTimeMillis())
            )
        }

        secPlayer.prepare()
        secPlayer.playWhenReady = true

        performCrossfadeSwap()

        if (savedShuffleEnabled && !restoredShuffleOrder) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
    }

    /**
     * Attempts to crossfade from the currently playing track directly into a
     * manually selected queue — e.g. tapping a song in a playlist, album, or
     * queue screen — instead of abruptly cutting to it. Reuses the same
     * player-swap/fade mechanism as [startCrossfade], but loads a brand new
     * queue on the secondary player instead of items already queued on the
     * primary one.
     *
     * Returns `true` if the crossfade was started, meaning the caller should
     * skip the normal instant-replace path. Returns `false` if a crossfade
     * couldn't be started (e.g. one is already in flight), in which case the
     * caller should fall back to its normal instant-replace behavior.
     */
    private fun startQueueCrossfade(
        status: Queue.Status,
        persistShuffleAcrossQueues: Boolean,
    ): Boolean {
        if (autoMixRequested) return false
        if (isCrossfading || secondaryPlayer != null) {
            Timber.tag(TAG).d(
                "startQueueCrossfade: aborting, isCrossfading=%s secondaryPlayer!=null=%s",
                isCrossfading,
                secondaryPlayer != null,
            )
            return false
        }

        val savedRepeatMode = runBlocking { dataStore.get(RepeatModeKey, REPEAT_MODE_OFF) }
        val targetIndex = if (status.mediaItemIndex > 0) status.mediaItemIndex else 0

        // If the new queue is actually the same items as the current queue,
        // preserve the existing shuffle traversal instead of re-randomizing.
        val sameQueue = status.items.size == player.mediaItemCount &&
            status.items.indices.all { i ->
                status.items[i].mediaId == player.getMediaItemAt(i).mediaId
            }
        val carryingShuffle = persistShuffleAcrossQueues && player.shuffleModeEnabled
        val preservedShuffleOrder =
            if (carryingShuffle && sameQueue) getCurrentShuffleOrderIndices(player) else null

        secondaryPlayer = createExoPlayer(publish = false, autoMixDeck = true)
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)

        secPlayer.setMediaItems(status.items, targetIndex, status.position)
        secPlayer.volume = 0f
        secPlayer.repeatMode = savedRepeatMode
        secPlayer.shuffleModeEnabled = carryingShuffle

        val restoredShuffleOrder = carryingShuffle && preservedShuffleOrder != null &&
            preservedShuffleOrder.size == secPlayer.mediaItemCount

        if (restoredShuffleOrder) {
            secPlayer.setShuffleOrder(
                DefaultShuffleOrder(preservedShuffleOrder!!, System.currentTimeMillis())
            )
        }

        secPlayer.prepare()
        secPlayer.playWhenReady = true

        performCrossfadeSwap()

        // If shuffle is on but the order wasn't carried over (a genuinely
        // different queue, or a same-queue size mismatch), build a fresh order.
        if (player.shuffleModeEnabled && !restoredShuffleOrder) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
        return true
    }

    /**
     * Preload variant of [startQueueCrossfade] for queues that begin from a
     * single "preload" item (Charts, Explore, Stats, Listen Together sync,
     * "Start radio", etc.) whose full contents resolve asynchronously.
     *
     * Instead of waiting for the whole queue, we load just the preload item on
     * the secondary player and start the fade immediately. The caller still
     * runs the background coroutine that resolves the full queue; once it
     * arrives, the existing preload-merge logic inserts the remaining items
     * around the now-playing preload item on the new primary player (which is
     * the former secondary), preserving playback position.
     *
     * Returns `true` if the crossfade was started, meaning the caller should
     * skip the synchronous instant-replace of the preload item. Returns `false`
     * if a crossfade is already in flight, in which case the caller falls back
     * to the normal instant-preload behavior.
     */
    private fun startQueueCrossfadeWithPreload(
        queue: Queue,
        persistShuffleAcrossQueues: Boolean,
    ): Boolean {
        if (autoMixRequested) return false
        val preloadItem = queue.preloadItem ?: return false
        if (isCrossfading || secondaryPlayer != null) {
            Timber.tag(TAG).d(
                "startQueueCrossfadeWithPreload: aborting, isCrossfading=%s secondaryPlayer!=null=%s",
                isCrossfading,
                secondaryPlayer != null,
            )
            return false
        }

        val savedRepeatMode = runBlocking { dataStore.get(RepeatModeKey, REPEAT_MODE_OFF) }

        secondaryPlayer = createExoPlayer(publish = false, autoMixDeck = true)
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)

        secPlayer.setMediaItem(preloadItem.toMediaItem())
        secPlayer.volume = 0f
        secPlayer.repeatMode = savedRepeatMode
        secPlayer.shuffleModeEnabled = persistShuffleAcrossQueues && player.shuffleModeEnabled

        secPlayer.prepare()
        secPlayer.playWhenReady = true

        Timber.tag(TAG).d("startQueueCrossfadeWithPreload: starting crossfade into preload item")
        performCrossfadeSwap()

        // Rebuild shuffle order on the new primary player if it carried shuffle over
        if (player.shuffleModeEnabled) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
        return true
    }

    private fun performCrossfadeSwap() {
        val nextPlayer = secondaryPlayer ?: return
        isCrossfading = true
        crossfadeTargetId=nextPlayer.currentMediaItem?.mediaId
        val currentPlayer = player

        fadingPlayer = currentPlayer
        player = nextPlayer
        currentPlayer.setPauseAtEndOfMediaItems(true)
        promoteAudioOutput(nextPlayer)
        _playerFlow.value = player
        secondaryPlayer = null

        fadingPlayer?.removeListener(this)
        fadingPlayer?.removeListener(sleepTimer)

        // Add listener to sync play/pause state
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isCrossfading && fadingPlayer != null) {
                    if (isPlaying) {
                        fadingPlayer?.play()
                    } else if (!nextPlayer.playWhenReady) {
                        fadingPlayer?.pause()
                    } // buffering is not a user pause: keep the outgoing audio alive
                } else {
                    nextPlayer.removeListener(this)
                }
            }
        })

        nextPlayer.removeListener(secondaryPlayerListener)
        nextPlayer.addListener(this)
        nextPlayer.addListener(sleepTimer)

        sleepTimer.player = player
        try {
            (mediaSession as MediaSession).player = player
        } catch (e: Exception) {
            Timber.tag("TiniumAudioSession").w("event=promotion-failed restoring-outgoing")
            nextPlayer.removeListener(this)
            nextPlayer.removeListener(sleepTimer)
            player=currentPlayer
            player.setPauseAtEndOfMediaItems(false)
            player.addListener(this)
            player.addListener(sleepTimer)
            sleepTimer.player=player
            _playerFlow.value=player
            currentMediaMetadata.value=player.currentMetadata
            fadingPlayer=null; isCrossfading=false; crossfadeTargetId=null
            releaseAudioOutput(nextPlayer)
            promoteAudioOutput(player)
            return
        }
        currentMediaMetadata.value=player.currentMetadata
        onMediaItemTransition(player.currentMediaItem,Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        crossfadeJob = scope.launch {
            val duration = crossfadeDuration.toLong()
            val steps = 20
            val stepTime = duration / steps
            val startVolume = try { fadingPlayer?.volume ?: 1f } catch(e:Exception) { 1f }

            val readyDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
            fade@ for (i in 0..steps) {
                if (!isActive) break
                // Pause volume ramp if player is paused
                while (!player.isPlaying && isActive) {
                    if (player !== nextPlayer || player.playerError != null ||
                        player.playbackState in listOf(Player.STATE_IDLE, Player.STATE_ENDED) ||
                        (player.playWhenReady && android.os.SystemClock.elapsedRealtime() > readyDeadline)) break@fade
                    delay(50)
                }

                val progress = i / steps.toFloat()
                val fadeIn = crossfadeCurve.fadeIn(progress)
                val fadeOut = crossfadeCurve.fadeOut(progress)

                try {
                    player.volume = startVolume * fadeIn
                    fadingPlayer?.volume = startVolume * fadeOut
                } catch (e: Exception) { break }

                delay(stepTime)
            }

            try {
                fadingPlayer?.volume = 0f
                player.volume = startVolume
                cleanupCrossfade()
            } catch (e: Exception) { }
        }
    }

    private fun cleanupCrossfade() {
        fadingPlayer?.let { old ->
            autoMixDecks.remove(old)
            autoMixProcessors.remove(old)
            playerSilenceProcessors.remove(old)
            playerEqProcessors.remove(old)?.let(equalizerService::removeAudioProcessor)
        }
        fadingPlayer?.stop()
        fadingPlayer?.clearMediaItems()
        fadingPlayer?.let(::releaseAudioOutput)
        fadingPlayer = null
        isCrossfading = false
        crossfadeTargetId=null
        if(isRunning) player.setOffloadEnabled(dataStore.get(AudioOffload,false) && !crossfadeEnabled && !autoMixRequested)
        sleepTimer.notifySongTransition()
    }

    companion object {
        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val YOUTUBE_PLAYLIST = "youtube_playlist"
        const val SEARCH = "search"
        const val SHUFFLE_ACTION = "__shuffle__"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val MAX_CONSECUTIVE_ERR = 5
        const val MAX_RETRY_COUNT = 10
        // Constants for audio normalization
        private const val MAX_GAIN_MB = 300 // Maximum gain in millibels (3 dB)
        private const val MIN_GAIN_MB = -1500 // Minimum gain in millibels (-15 dB)

        private const val TAG = "MusicService"

        @Volatile
        var isRunning = false
            private set
    }
}

private data class SponsorBlockConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val skipNonMusic: Boolean,
    val skipSponsor: Boolean,
    val skipSelfPromo: Boolean,
    val skipInteraction: Boolean,
    val skipIntroOutro: Boolean,
    val skipPreviewFiller: Boolean,
    val showToast: Boolean,
)

internal data class NativeServiceInspection(val mode:AutoMixEngineMode,val nativeSelected:Boolean,val legacyEnabled:Boolean,
    val execution:NativeDjExecutionReport?,val outputs:Int,val primaryId:String?,val primaryVolume:Float,
    val incomingVolume:Float?,val incomingPlaying:Boolean?,val activeSessions:Set<Int>,val incomingSessionId:Int?)
