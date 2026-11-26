# Tinium complete project handoff

## Navigation and scope

- **This document:** complete technical handoff and evidence map.
- [Compact AI context](TINIUM_AI_CONTEXT.md): upload this first to a new AI platform.
- [Ready-to-copy starting prompt](TINIUM_NEW_AI_STARTING_PROMPT.md): initial message for that agent.
- [Current status snapshot](TINIUM_CURRENT_STATUS.md): dated branches, PRs, workflow links and next actions.
- Native DJ: [activation](AUTOMIX_NATIVE_DJ_ACTIVATION.md), [planner](AUTOMIX_NATIVE_DJ_PLANNER.md),
  [executor](AUTOMIX_NATIVE_DJ_EXECUTOR.md), [clock/route/placement](AUTOMIX_NATIVE_DJ_RELIABILITY_AND_PLACEMENT.md),
  [AAC/Opus repair](AUTOMIX_REAL_WORLD_DECODER_RELIABILITY.md), [AAC boundary coverage](AUTOMIX_AAC_BOUNDARY_COVERAGE.md).
- Build/release: [signing](APK_SIGNING.md), [release process](RELEASING.md), [updater](UPDATER.md),
  [release-note policy](../release-notes/README.md). Testing commands are in section 15 below.
- [Native prototype evidence](../experiments/bitchord-native-analysis/results/),
  [Phase 3.3 evidence](benchmarks/phase33-native-automix/),
  [codec evidence](benchmarks/phase331-native-decoder/),
  [route/clock evidence](benchmarks/phase332-native-reliability/),
  [AAC boundary evidence](benchmarks/phase333-aac-boundary/).

**Observation:** 2026-11-09 UTC (2026-11-08 in America/Chicago). Repository:
<https://github.com/yren411/vivi-music-personal>. Default branch `main` is
`76eda458c3ac2dc0efd307d24116bf4c68cd31df`, the PR #26 merge. This documentation branch is
`codex/tinium-project-handoff`, based on that main. Recheck GitHub before acting: this is a
snapshot, not a promise that branches, tests, artifacts or external services remain current.

**Most important distinction:** [PR #27](https://github.com/yren411/vivi-music-personal/pull/27)
is **open and draft**, head `f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2`. Its adaptive analysis
is implemented and has recorded local/CI validation, but is **not merged into main**.
Do not recreate it, describe main as containing it, or treat green checks as phone acceptance.
Its source and documentation are pinned externally in section 10 because they are absent
from this main-based checkout. This handoff implements no application changes.

### Evidence vocabulary

| Label | Meaning |
| --- | --- |
| Code fact | Verified implementation/configuration in the stated revision; not proof of live-service success |
| Recorded local execution | Committed prior test/benchmark evidence; not a fresh run during this handoff |
| Owner-reported device result | Owner's S25 Ultra / Android 17 report; not independent Codex execution or installed-SHA verification |
| CI result | GitHub run checked against its actual head SHA; build/test scope depends on workflow |
| Hypothesis | Plausible explanation still requiring source/device evidence |
| Planned | No verified implementation in main; an open implementation PR is identified separately |

No current raw commercial audio, private container metadata, new acoustic measurements or
new physical-device execution was supplied for this handoff. Test totals must always retain
revision, variant, selection and provenance. A zero-test run, compilation or successful
native cross-compile is not a device pass.

### Contents

- [1. Project identity](#1-project-identity-and-priorities)
- [2. Modules and repository](#2-repository-and-module-map)
- [3. Feature inventory](#3-feature-inventory-and-validation-scope)
- [4. Application architecture](#4-application-architecture-and-responsibility-boundaries)
- [5. Development history](#5-native-dj-development-history)
- [6. Native pipeline](#6-native-dj-pipeline-concrete-code-threads-and-invariants)
- [7. Corrected decoding](#7-corrected-decoding-and-source-timelines)
- [8. BitChord and JNI](#8-bitchord-native-integration-and-jni)
- [9. Planner, executor and Legacy](#9-native-planner-executor-and-legacy-coexistence)
- [10. Open adaptive implementation](#10-adaptive-analysis-and-stable-reuse--implemented-only-in-pr-27)
- [11. Settings and invalidation](#11-settings-route-safety-and-invalidation)
- [12. Diagnostics](#12-diagnostics-and-debugging-decision-tree)
- [13. Privacy and licensing](#13-security-privacy-and-licensing)
- [14. Build, CI and releases](#14-environment-dependencies-ci-signing-and-releases)
- [15. Testing and evidence](#15-testing-and-evidence-provenance)
- [16. Unfinished work](#16-known-issues-and-unfinished-work-register)
- [17. Documentation discrepancies](#17-documentation-discrepancies-and-authority-order)
- [18. Roadmap and workflow](#18-ordered-continuation-roadmap-and-workflow-for-a-new-ai)

## 1. Project identity and priorities

Tinium is a personal Android music-player fork of [Vivi Music](https://github.com/vivizzz007/vivi-music).
Public application identity is **Tinium**, `com.yren411.tinium`. Kotlin namespace remains
`com.music.vivi`; Gradle root name remains `vivimusic`. Internal Vivi/Metrolist names and
legacy preference/intent identifiers survive. They are not permission to mass-rename packages.
The fork has its own branding, application ID for coexistence, persistent signing/release
management, GitHub Releases updater, and extensive legacy/native AutoMix work.

Development defaults are versionName `6.0.8`, versionCode **77**, minSdk **26**, compileSdk
and targetSdk **37**. Release workflows reserve different codes; defaults are not release
assignments. Android 8+ is the configured target range, not a claim of a tested device matrix.
The principal owner test device is Samsung Galaxy S25 Ultra / Android 17.

The app includes online browsing/stream resolution, background playback, personal library,
playlists, downloads/cache, lyrics, audio settings/EQ, optional integrations, and experimental
DJ mixing. Live endpoints, account behavior and device integrations are not comprehensively
validated by the AutoMix tests. Native DJ is an experiment; **AutoMix itself defaults off**,
and its engine defaults to **Legacy** when enabled. The current engineering priority is to
finish validating PR #27 and real-source behavior before advanced native DJ effects.

Branding provenance and assets live in [branding](../branding/README.md). Preserve upstream
attribution, Tinium identity, existing data and the permanent signing identity. Do not use
upstream screenshots as verified current Tinium UI captures.

## 2. Repository and module map

```text
app/                       Android application, Compose UI, Room, service, tests, JNI
  src/main/kotlin/...      app/DI, db, playback, automix, eq, lyrics, UI, integrations
  src/gms/                 Cast-capable implementations and manifest additions
  src/foss/                matching Cast stubs; no Google Cast dependencies
  src/main/cpp/            actual BitChord JNI build + retained unrelated vibrafp sources
  src/nativeTestShared/    common generated-fixture/native test helpers
  src/test/                JVM contracts, analysis, planner, executor and updater tests
  src/androidTest/         genuine codec/JNI/Media3/service tests and generated assets
  schemas/                 Room migration schemas
innertube/                 YouTube/YouTube Music models, pages and request/extraction logic
lyricsProvider/            provider protocol/parsers, including restricted-notice material
canvas/, applecanvas/, vivimusiccanvas/, artistvideo/  visual background providers/components
jiosaavn/, spotify/, lastfm/, kizzy/, shazamkit/        optional service integrations
experiments/bitchord-native-analysis/                 authoritative vendored/native DSP
scripts/, tests/           signing/release/branding/fixture/packaging tools and Python tests
docs/, docs/benchmarks/    versioned engineering findings and bounded evidence
release-notes/, branding/, Changelog/                  notes, identity, historical content
.github/workflows/, .github/actions/                  CI, release and legacy workflow helpers
```

All 12 modules are declared in [settings.gradle.kts](../settings.gradle.kts). `app` depends
on the other eleven. Module visibility is mostly conventional Kotlin APIs consumed by app,
not independently published supported SDKs. Their build files are authoritative:

| Module / representative interface | Responsibility and dependencies |
| --- | --- |
| [app](../app/build.gradle.kts) | Android application; Hilt, Compose, Room/KSP, Media3, DataStore, WorkManager, protobuf-lite, JNI; orchestrates all providers |
| [innertube](../innertube/build.gradle.kts), `YouTube` | Android library; YouTube browse/search/player models and strategies; Ktor, Brotli, InnerTubeX and NewPipe extraction dependencies |
| [lyricsProvider](../lyricsProvider/build.gradle.kts) | Android library; Musixmatch, YouLyPlus, BetterLyrics/TTML, Unison and associated provider protocols; Ktor/serialization/Timber; app owns ordering and user settings |
| [canvas](../canvas/build.gradle.kts), `TidalCanvasProvider`, `CanvasUtils` | JVM library; provider results/utilities, Tidal and Apple artist background lookups; Ktor/serialization; has provider utility tests |
| [applecanvas](../applecanvas/build.gradle.kts), `AppleMusicCanvasProvider` | JVM library depending on canvas and Ktor; Apple visual lookup; its serialization dependency also has an explicit module-local version |
| [vivimusiccanvas](../vivimusiccanvas/build.gradle.kts), `ViviMusicCanvasProvider` | JVM library depending on canvas; another visual provider, not another playback service |
| [artistvideo](../artistvideo/build.gradle.kts), `ArtistVideoCanvasProvider` | Android library; artist visual lookup/display; Compose and Media3 dependencies |
| [jiosaavn](../jiosaavn/build.gradle.kts), `SaavnService` | Android library; search/matching/stream URL helpers; Ktor CIO; selected through app's source resolver |
| [spotify](../spotify/build.gradle.kts), `Spotify`, `SpotifyAuth`, `SpotifyMapper` | Android library; account/playlist/liked-song import and matching helpers; Ktor. Not a Spotify audio playback implementation |
| [lastfm](../lastfm/build.gradle.kts), `LastFM` | Android library; authentication/scrobbling/now-playing request layer; app `ScrobbleManager` owns integration |
| [kizzy](../kizzy/build.gradle.kts), `KizzyRPC` | Android library; Discord gateway/rich-presence support; app owns enablement/account state |
| [shazamkit](../shazamkit/build.gradle.kts), `Shazam` | Android library containing HTTP recognition protocol/models, not evidence of Apple's official ShazamKit SDK; app records/fingerprints audio |

All use the repository's Java 21 toolchain configuration. Android library and JVM-library
plugins differ; do not apply app flavor rules indiscriminately to every module. Source
paths can be found by module/class name; no Git submodule entries or `.gitmodules` were
present at inspection. CI still requests recursive submodules defensively.

## 3. Feature inventory and validation scope

Status abbreviations: **I/U** = implemented and wired but not comprehensively validated;
**I/T** = implemented with named automated evidence for the stated scope; **E** = experimental;
**P/D** = partial, legacy or not part of the active build; **F** = future work. Dependencies
and screenshots alone do not establish functionality.

| Feature | Status and actual evidence | Locate first |
| --- | --- | --- |
| Normal playback, pause/seek/queue, foreground notification | I/U broadly; generated Media3/service scenarios have owner-reported tests. Network/device breadth remains unverified | [MusicService](../app/src/main/kotlin/com/music/vivi/playback/MusicService.kt), [PlayerConnection](../app/src/main/kotlin/com/music/vivi/playback/PlayerConnection.kt) |
| YouTube Music home/search/browse/radio/album/artist | I/U; reachable Compose screens and resolver calls; provider changes can break runtime | [YTPlayerUtils](../app/src/main/kotlin/com/music/vivi/utils/YTPlayerUtils.kt), [YouTube](../innertube/src/main/kotlin/com/music/innertube/YouTube.kt), [NavigationBuilder](../app/src/main/kotlin/com/music/vivi/ui/screens/NavigationBuilder.kt) |
| Optional JioSaavn matching/audio source | I/U; resolver searches metadata, ranks matches, selects URL/content length and falls through to YouTube on failure; not guaranteed same rendition/timeline | [SaavnService](../jiosaavn/src/main/kotlin/com/music/jiosaavn/SaavnService.kt), [Jio settings](../app/src/main/kotlin/com/music/vivi/ui/screens/settings/JioSettings.kt) |
| Personal library, likes, local playlists, album/artist relations, searches/history | I/U; Room entities/DAO and ViewModels/screens; no blanket fresh migration/device audit | [MusicDatabase](../app/src/main/kotlin/com/music/vivi/db/MusicDatabase.kt), [DatabaseDao](../app/src/main/kotlin/com/music/vivi/db/DatabaseDao.kt), [library screens](../app/src/main/kotlin/com/music/vivi/ui/screens/library/) |
| Downloads and playback cache | I/U; Media3 DownloadManager, separate caches, source resolution/retry; offline playback is implemented, not every provider/expiry case proven | [DownloadUtil](../app/src/main/kotlin/com/music/vivi/playback/DownloadUtil.kt), [ExoDownloadService](../app/src/main/kotlin/com/music/vivi/playback/ExoDownloadService.kt), [AppModule](../app/src/main/kotlin/com/music/vivi/di/AppModule.kt) |
| Arbitrary local-file library/scanning | File-URI playback exists; a complete MediaStore/folder-scanning product is not established by this inspection. “Local search” primarily searches app library | [LocalSearchViewModel](../app/src/main/kotlin/com/music/vivi/viewmodels/LocalSearchViewModel.kt), MusicService URI handling |
| Lyrics and karaoke/TTML rendering | I/U; multiple providers, priority migration, cached rows and UI; live provider/lyrics catalog rights not guaranteed | [LyricsHelper](../app/src/main/kotlin/com/music/vivi/lyrics/LyricsHelper.kt), [LyricsProviderRegistry](../app/src/main/kotlin/com/music/vivi/lyrics/LyricsProviderRegistry.kt), [lyrics module](../lyricsProvider/) |
| Lyrics translation, romanization, AI settings | I/U; configurable DeepL/Mistral/OpenRouter paths, streaming translation and language utilities; remote cost/privacy and provider health require review | [LyricsTranslationHelper](../app/src/main/kotlin/com/music/vivi/lyrics/LyricsTranslationHelper.kt), [AiSettings](../app/src/main/kotlin/com/music/vivi/ui/screens/settings/AiSettings.kt), [api](../app/src/main/kotlin/com/music/vivi/api/) |
| Animated artwork/canvas/artist backgrounds | I/U; provider selection wired in player thumbnails/album/artist UI; individual remote catalogs not tested here | [Thumbnail](../app/src/main/kotlin/com/music/vivi/ui/player/Thumbnail.kt), canvas modules |
| Queue persistence, shuffle/repeat, radio continuation | I/U generally, I/T for specific native queue/promotion regressions; normal queue remains Media3/service-owned | [queues](../app/src/main/kotlin/com/music/vivi/playback/queues/), [PersistQueue model](../app/src/main/kotlin/com/music/vivi/models/PersistQueue.kt) |
| MediaSession / media browser / lockscreen / headset controls | I/U; service/session callbacks and manifest are wired; Android Auto physical-car validation not supplied | [MediaLibrarySessionCallback](../app/src/main/kotlin/com/music/vivi/playback/MediaLibrarySessionCallback.kt), [manifest](../app/src/main/AndroidManifest.xml) |
| Google Cast | I/U in GMS; intentionally unavailable/stubbed in FOSS. Native DJ gate rejects casting | [gms](../app/src/gms/), [foss](../app/src/foss/) |
| Internal EQ / profile controls / loudness and audio settings | I/U for audible quality, with session/gain regression coverage; shared player construction attaches processors per deck | [EqualizerService](../app/src/main/kotlin/com/music/vivi/eq/EqualizerService.kt), [eq](../app/src/main/kotlin/com/music/vivi/eq/), [effects](../app/src/main/kotlin/com/music/vivi/playback/effects/) |
| Poweramp/system external EQ | Session integration implemented; audibly equivalent processing of both decks is unverified | [AudioEffectSessions](../app/src/main/kotlin/com/music/vivi/playback/effects/AudioEffectSessions.kt), [AudioOutputLifecycle](../app/src/main/kotlin/com/music/vivi/playback/effects/AudioOutputLifecycle.kt) |
| Simple crossfade and Legacy AutoMix | Implemented; legacy analysis/planner/controller tests and generated playback coverage. Legacy is selected by default, not proof of high musical quality | [AutoMixController](../app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixController.kt), [legacy guide](AUTOMIX.md) |
| Native DJ AutoMix | E; merged through Phase 3.3.3; owner-reported 79 selected Android passes and some real-song completions; intermittent fallback remains | Sections 5–11 |
| Adaptive/native evidence reuse | E, implemented **only in unmerged draft PR #27**; recorded 599+599 JVM passes; 12 new Android methods compiled, no reported new-device pass found | Section 10 |
| Advanced native bass swaps/per-deck filters/time stretching | F; not implemented by the native executor. Existing legacy EQ/rate code must not be mislabeled Phase 3.4 | Roadmap, section 18 |
| Spotify import | I/U; maps external playlists/likes to YouTube matches and local DB playlists; does not stream Spotify audio | [SpotifyImportViewModel](../app/src/main/kotlin/com/music/vivi/viewmodels/SpotifyImportViewModel.kt) |
| Last.fm scrobbling / Discord presence | I/U, optional account-dependent network integrations; not local-only processing | [ScrobbleManager](../app/src/main/kotlin/com/music/vivi/utils/ScrobbleManager.kt), [DiscordRPC](../app/src/main/kotlin/com/music/vivi/utils/DiscordRPC.kt) |
| Listen Together / room chat/sync | I/U; WebSocket client/manager, protobuf messages, screen/settings and action receiver. Native DJ refuses active room sync | [ListenTogetherManager](../app/src/main/kotlin/com/music/vivi/listentogether/ListenTogetherManager.kt), [protocol](../app/src/main/proto/listentogether.proto) |
| Music recognition | I/U; permission-gated microphone recording, 16 kHz fingerprint and Shazam request/history. Kotlin signature implementation is active | [MusicRecognitionService](../app/src/main/kotlin/com/music/vivi/recognition/MusicRecognitionService.kt), [ShazamSignatureGenerator](../app/src/main/kotlin/com/music/vivi/recognition/ShazamSignatureGenerator.kt) |
| Listening stats / Wrapped / artist new-release notifications | I/U; DB events/counters, screens, WorkManager worker; not all notification/lifecycle scenarios audited | [StatsViewModel](../app/src/main/kotlin/com/music/vivi/viewmodels/StatsViewModel.kt), [Wrapped](../app/src/main/kotlin/com/music/vivi/ui/screens/wrapped/), [artist-release worker](../app/src/main/kotlin/com/music/vivi/vivimusic/release/NewReleaseCheckWorker.kt) |
| Backups/restoration / automatic backup | I/U; user-facing backup tooling and WorkManager; inspect payload/credentials before sharing any backup | [BackupRestoreViewModel](../app/src/main/kotlin/com/music/vivi/viewmodels/BackupRestoreViewModel.kt), [AutoBackupWorker](../app/src/main/kotlin/com/music/vivi/utils/AutoBackupWorker.kt) |
| Widgets / sleep timer / themes/fonts/locales/accessibility | I/U; concrete receivers/screens/preferences; no comprehensive fresh device/accessibility matrix | [widget](../app/src/main/kotlin/com/music/vivi/widget/), [SleepTimer](../app/src/main/kotlin/com/music/vivi/playback/SleepTimer.kt), [settings](../app/src/main/kotlin/com/music/vivi/ui/screens/settings/) |
| SponsorBlock/content controls | I/U; API/settings paths exist, service behavior remains provider/content dependent | [SponsorBlockApi](../app/src/main/kotlin/com/music/vivi/sponsorblock/SponsorBlockApi.kt) |
| GitHub updater / release/signing tooling | I/T for fixture/Python regression scopes; real published-update acceptance not established. No releases were returned by API at inspection | [updater](../app/src/main/kotlin/com/music/vivi/vivimusic/updater/), [scripts](../scripts/), [tests](../tests/) |
| Old vibrafp C++/FFTW build tree | P/D for current app: retained sources/helper workflow, not the selected Android native target. Recognition wrapper delegates to Kotlin | [vibrafp](../app/src/main/cpp/vibrafp/), [VibraSignature](../app/src/main/kotlin/com/music/vivi/recognition/VibraSignature.kt) |

## 4. Application architecture and responsibility boundaries

### Initialization, state and UI

[App.kt](../app/src/main/kotlin/com/music/vivi/App.kt) is `@HiltAndroidApp`, sets preference
cache/crash handling, initializes InnerTubeX/cipher helpers, and observes DataStore to
configure locale, account cookies/visitor data, proxy/IP preferences and Last.fm. It is not
a centralized repository for every feature. Do not equate these settings with immutable
source identity. Application scope is a Hilt-provided `SupervisorJob`/Default dispatcher.

[MainActivity](../app/src/main/kotlin/com/music/vivi/MainActivity.kt) hosts Compose/navigation,
binds the actual service and handles deep links/share intents. `NavigationBuilder` and
[Screens](../app/src/main/kotlin/com/music/vivi/ui/screens/Screens.kt) map routes. ViewModels
hold feature state and call DB/provider APIs; Compose observes flows through the inherited
state/preferences helpers. [PlayerConnection](../app/src/main/kotlin/com/music/vivi/playback/PlayerConnection.kt)
attaches to the currently authoritative player, exposes reactive state, and routes user queue,
seek, pause, library and like actions. Native promotion must update that player flow.

```text
Compose screens / navigation <--> ViewModels <--> Room and DataStore
             |                        |
       PlayerConnection          provider modules / network APIs
             |
         MusicService <--> MediaLibrarySession / notification / widgets
             |
     Media3 player(s), permitted source/cache factories, audio-output lifecycle
             |
     Native OR legacy AutoMix owns a transition; background analysis owns no deck
```

This is a responsibility map, not a claim that all features use one formal repository layer.
Network clients occur in modules and app utilities. There are other narrow audio consumers,
notably [WrappedAudioService](../app/src/main/kotlin/com/music/vivi/ui/screens/wrapped/WrappedAudioService.kt)
with its own presentation ExoPlayer; inspect it before making app-wide focus changes. Normal
music/queue authority and Native DJ remain inside MusicService.

### Persistence and networking

`AppModule` supplies `InternalDatabase`, `MusicDatabase`, database provider, caches and
integration managers. Room DB is **`song.db`, schema 34**, with exported schemas in
[app/schemas](../app/schemas/). It contains songs, artists/albums, playlist mappings, formats,
lyrics, search history, events/play counts, recognition and speed-dial data. `MusicDatabase`
wraps DAO access and asynchronous queries/transactions. Use the existing migrations and
transaction boundaries; never fix a migration by destroying user data.

[DataStore.kt](../app/src/main/kotlin/com/music/vivi/utils/DataStore.kt) defines ordinary
Preferences DataStore `settings`; [PreferenceKeys](../app/src/main/kotlin/com/music/vivi/constants/PreferenceKeys.kt)
and enum persistence determine compatibility. Some feature/updater/account state uses
SharedPreferences separately. No blanket encryption guarantee is established. DB schemas,
serialized queues and preference strings are part of the compatibility surface.

YouTube/InnerTube requests, extraction/cipher/PoToken helpers and [YTPlayerUtils](../app/src/main/kotlin/com/music/vivi/utils/YTPlayerUtils.kt)
resolve actual playback data. `playerResponseForPlayback` can first match JioSaavn when
selected, then fall through to YouTube. Matching metadata does not prove identical PCM,
leading delay or duration. Signed URLs expire; format/quality/cache/rendition pinning matters.
`MusicService` uses resolving data sources and caches, not arbitrary direct downloads by an
analysis engine. `DownloadUtil` owns a separate Media3 DownloadManager and download service.

`AppModule` creates player cache under app-private `files/exoplayer` (preference-controlled
LRU, or no eviction when configured) and download cache under `files/download` with no-op
eviction. A cached byte span is not a PCM or musical-analysis certificate. Keep streaming,
download and analysis caches distinct. PR #27 adds only bounded in-memory musical evidence,
not another downloaded-audio cache.

### Playback, queues, focus and effects

`MusicService` extends MediaLibraryService, owns normal ExoPlayer and the MediaLibrarySession,
queue operations, radio/next-track resolution, notifications, sleep timer and source recovery.
Queue classes provide finite/list, online/radio and empty queues; service maintains actual
Media3 items and traversal. PersistQueue/PersistPlayerState retain state across lifecycle.
Appending an unrelated future item is different from replacing the active upcoming item.

`createExoPlayer`/renderer helpers install custom EQ, silence handling and AutoMix processor
chains through the shared construction path. One existing AudioFocusRequest owner handles
pause/resume/ducking; standby Native DJ decks do not request independent focus. User volume,
mute, focus gain and deck-relative gain have separate ownership. Do not automate the user's
global EQ to simulate DJ effects.

`AudioOutputLifecycle` counts actual AudioTrack initialization/release. `AudioEffectSessions`
tracks primary/prepared/fading roles, opens a replacement session before closing an old one,
and avoids duplicate CLOSE while another output still owns the ID. Readiness alone is not
proof of a real output. MusicService attaches/removes EQ processors on deck creation/release.
Session IDs/broadcast tests do not prove audible external-EQ processing of both tracks.

## 5. Native DJ development history

All PRs #1–26 were verified merged. #27 was the only existing open application PR. The
following table is a map of scope and outcomes, not a statement that every historic test
was independently rerun at the latest head.

| PR / phase | Purpose, implementation and outcome |
| --- | --- |
| [#1–2](https://github.com/yren411/vivi-music-personal/pull/1) | Fork identity/side-by-side app ID and Tinium branding; #2 completes official logo/header work |
| [#3–5](https://github.com/yren411/vivi-music-personal/pull/5) | Persistent debug/release signing, certificate verification, global code allocation and publish-tested-artifact process |
| [#6–10](https://github.com/yren411/vivi-music-personal/pull/6) | GitHub updater; branding/release-note drafting; draft candidate handling; About channel and startup icon/attribution corrections |
| [#11](https://github.com/yren411/vivi-music-personal/pull/11) | Independent Kotlin analysis-driven Legacy AutoMix with bounded performance modes |
| [#12–14](https://github.com/yren411/vivi-music-personal/pull/14) | Quality/session integration, planning/diagnostics, DJ techniques and deliberate log export. Legacy bass/filter/rate effects precede Native DJ |
| [#15 / Phase 1](https://github.com/yren411/vivi-music-personal/pull/15) | Feasibility/real-device investigation; poor old beat confidence and BPM limitations motivated actual native DSP. It was not the native port |
| [#16 / 2.1](https://github.com/yren411/vivi-music-personal/pull/16) | Replaceable AnalysisEngine/Profile/Result, explicit evidence provenance, PCM coverage and cache identities, exact legacy adapter |
| [#17 / 2.2](https://github.com/yren411/vivi-music-personal/pull/17) | Corrected bandlimited mono preprocessing, coverage/timestamp diagnostics and decoder instrumentation. Production legacy preprocessing remained distinct |
| [#18 / 2.3](https://github.com/yren411/vivi-music-personal/pull/18) | Licensing/provenance reassessment: public reuse rules, historic GPL evidence and simultaneous restrictive notices. Engineering permission assumption is not redistribution clearance |
| [#19 / 2.3A](https://github.com/yren411/vivi-music-personal/pull/19) | Actual selected BitChord/Orchard C++ DSP, controlled cancellation, independent host build, deterministic benchmarks and Android cross-compilation |
| [#20 / 2.3B](https://github.com/yren411/vivi-music-personal/pull/20) | Android CMake/JNI and Kotlin backend, host-JNI/Android tests, packaging. FutureTask completion/cache race and initial codec coverage required repairs |
| [#21 / 3.1](https://github.com/yren411/vivi-music-personal/pull/21) | Native-aware two-track planner, local beat alignment and signal evidence; mathematical plan validity separate from listening quality |
| [#22 / 3.2](https://github.com/yren411/vivi-music-personal/pull/22) | Real two-deck Media3 executor; active-content gain, format-aware startup prediction and independent progress tracking; owner reported 14 executor passes |
| [#23 / 3.3](https://github.com/yren411/vivi-music-personal/pull/23) | MusicService/queue integration, experimental selector, pinning, ownership/fallback and diagnostics; owner ultimately reported 49 combined tests passed after targeted repairs |
| [#24 / 3.3.1](https://github.com/yren411/vivi-music-personal/pull/24) | AAC retained-tail source ownership and Opus preroll/support packets; owner reported 59 combined passes |
| [#25 / 3.3.2](https://github.com/yren411/vivi-music-personal/pull/25) | Actual-output route classification, conservative bracketed clocks, duration-preserving planner v2; owner reported 69 combined passes. Intermittent startup/drift was not proven fixed |
| [#26 / 3.3.3](https://github.com/yren411/vivi-music-personal/pull/26) | Bounded AAC seek preroll, v8 PCM, strict boundary diagnostics; owner reported 10 targeted then 79 combined passes. Genuine/uncertain presentation edges remain partial |
| [#27 / 3.3.4](https://github.com/yren411/vivi-music-personal/pull/27) | Implemented adaptive verified intervals, source leases and bounded cached-region enrichment; **open draft**, not main. Recorded 599+599 JVM passes and 12 compiled new Android methods; no new phone result found |

### Failed experiments and debugging lessons retained in the repository

- **Cache race (#20):** FutureTask wakes `get()` before `done()` finishes. `cancelAll()` must
  preserve a completed immutable success even when its callback waits for the map lock.
  [AnalysisWorkers](../app/src/main/kotlin/com/music/vivi/playback/automix/AnalysisWorkers.kt)
  and `AnalysisPipelineContractsTest.completedResultsAreReusedAfterInvalidationWithoutRepeatingDecode`
  preserve object identity with `assertSame`; do not replace this with weaker equality or sleeps.
- **Harness hangs (#23):** waiting for global UI idleness/unbounded synchronization stalled
  real service tests. `BoundedTestCall` and explicit lifecycle predicates replaced that path.
- **Stale fixture cache (#23):** reused identities served old WAV bytes for AAC/FLAC requests.
  `NativeServiceFixtureIds` isolates test runs; only owned fixture entries are removed.
- **Output readiness (#23):** source resolution can precede PCM processor/AudioTrack setup.
  The service waits for actual readiness rather than permanently rejecting a transient state.
- **FLAC investigations:** vendor extraction could reset packet PTS; frame-clock v4 exposed
  additional EOS losses. Bounded Media3 frame extraction and supported platform decoder
  selection in v5 recovered real frames. No coverage threshold was loosened. Preserve the
  packet/EOS probes and historical measurements in activation/codec documents.
- **Pause/resume reentrancy (#23/#25):** service callbacks can reenter executor controls;
  guards prevent pause/play loops and maintain both decks' state.
- **History scope:** service instances/history can outlive one instrumentation method;
  attempt/session scoping prevents old handoffs satisfying new assertions.
- **Completion boundary:** legacy promotion happens at fade **start**, native promotion at
  transition **completion**. Successive-transition tests wait for ownership idle before
  asserting the retained one-output condition. The one-output assertion was not relaxed.
- **Startup/drift (#22/#25):** initial observations around 125–139 ms and later much larger
  startup offsets were real failure evidence. A healthy later probe/69-test pass did not
  establish a synchronization cure. Keep original failure artifacts alongside successes.
- **AAC/Opus:** 1010/1024 AAC buffers exposed 14 tail frames with incorrect ownership;
  Opus seeking/request cutoff lost real head/end support. Format-specific repairs recover
  real packets, never copied/synthetic PCM. See sections 7 and 16 for remaining edges.

## 6. Native DJ pipeline: concrete code, threads and invariants

The following describes **merged main**. Section 10 explicitly marks PR #27 differences.
Definitions are sometimes grouped: `PcmPreprocessing` is in `AnalysisContracts.kt`,
`AnalysisProfile`/`WindowAnalysisPipeline` in `AnalysisEngine.kt`, `NativePlanningAnalysis`
in `PlanningSignalObservation.kt`, `NativeDjQueue` in `NativeDjExecutionContracts.kt`, and
`NativeServiceHost` inside `MusicService.kt`. A class name is not always a file name.
All paths below are under [playback/automix](../app/src/main/kotlin/com/music/vivi/playback/automix/)
unless linked elsewhere. Player/service calls belong to the player application thread;
decoding and DSP belong to the existing bounded analysis workers.

| Stage | Class/function and inputs → outputs | Invariants, cancellation and failures |
| --- | --- | --- |
| Opt-in / gating | `MusicService` settings observer → `NativeAutoMixServiceController.configure` | Engine LEGACY by default; no native job/library requirement for ordinary playback. Unsupported configurations fall back |
| Pair selection | `NativeServiceHost.pair`, `NativeQueueBinding`, `NativeDjQueue` → outgoing/incoming identities, durations, cue regions, generation | Actual next queue traversal, repeat/shuffle and source rendition, not title equality. Active legacy mixing prevents native ownership |
| Resolve/pin | normal resolver/prefetch, `NativeRenditionRegistry`, `NativePlaybackSourcePin` → authorized `AnalysisSource` and prepared-player source | URI/cache key/rendition must match. DRM/live/expired or changed sources and incompatible playback states are guarded |
| Byte/source access | permitted read-only analysis factory; `AnalysisSource.fullyCached`; `AndroidWindowDecoder.RangeSource` | Source length/range and byte/deadline budgets enforced. Complete cache means encoded bytes, not complete presentation PCM |
| Request creation | `NativeAutoMixServiceController.tick` → outgoing final ≤32 s and incoming initial ≤32 s | Track ≥8 s; original pair deadline tied to remaining preparation time and observed cost, bounded up to 20 s; no main-thread decode |
| Worker coordination | `ExperimentalNativePlanningSession.begin/acquire/poll`, `AnalysisWorkers` | Shared windows deduplicate in a batch; 1/2/4 workers, queue 8, completed LRU 64, shared `AnalysisConcurrencyBudget`. No raw PCM cache |
| Extract/decode | `AndroidWindowDecoder.decode/decodeWindow`, `DecoderPacketAccounting` → `DecodedWindow` or typed failure | Actual extractor/codec PTS, format and source origin; software codec controls, finite output. Source gaps, unknown or malformed material remain failures |
| Codec-specific timing | `AacAnalysisWindow`, `AacAnalysisTailTrim`, `OpusAnalysisWindow`, `BoundedFlacPackets`, `PlatformFlacCodecPolicy` | Genuine support packets, codec-owned front trim, application-owned bounded AAC tail, no timestamp rebasing or source synthesis |
| Capture/resample | `CorrectedPcmWindowCapture.accept/finish`, `BandlimitedResampler` | Contiguous frame clock; mono Float32 22050 Hz; millisecond materialization origin; explicit real coverage and edge support |
| Validate/analyze | `NativePlanningAnalysis`, `WindowAnalysisPipeline`, `BitChordNativeAnalysisEngine.analyze` | Source/request/profile/coverage before JNI; backend input bounded to 705600 floats. Failed/legacy evidence cannot masquerade as native |
| Native call | `NativeDspPacket` / bindings / call control → `native_analysis_jni.cpp` → prototype API | Synchronous bounded copy; no long-lived Java pin, no worker thread spawned by DSP; per-call cancellation/deadline; controlled statuses |
| Evidence mapping | `BitChordNativeAnalysisEngine.map`, `relativeBeatUs`, `AnalysisResultValidation` | Ordered finite local beats converted once with actual PCM origin. Confidence is explicitly uncalibrated. Unsupported key/vocal/phrase/stability remain unavailable |
| Signal audit | `PlanningSignalObservation.measure`, `NativeDjLevel.measure` → compact RMS-rise and active-level evidence | No PCM retained. Signal/transient observations are not vocal, harmonic, meter or phrase detectors |
| Planner | `NativePlanningEvidence.validate`, `NativeAwareAutoMixPlanner.plan` → `Selected` or typed `Fallback` | Source/profile/duration/local coverage, intro preservation, tempo/alignment/transient gates; no forced octave/rate correction |
| Plan publication | controller polls completed results, records evidence/PLAN, publishes `AutoMixStatus` | Update earliest position while analyzing; generation/queue validity checked before publication/execution. PLAN is not an executed transition |
| Prepare | `NativeServiceHost.execute`, `Media3NativeDjPlayback.execute/prepare` → incoming paused ExoPlayer from service factory | Exclusive deck ownership, exact approved source/cue, shared focus/EQ infrastructure; startup prediction compatibility checked |
| Execute | `NativeDjTransitionExecutor.start/tick` | Validate → prepare → arm → start → mix; actual readiness/progress, command timing and drift guards; pause freezes media progress |
| Observe / automate | `NativeDjClockPair`, actual AudioTrack route/timestamp callbacks, `NativeDjGain` | Conservative raw/projected clock bounds; independent progress; bounded deck gains; output timestamps are diagnostics, not acoustic proof |
| Promote | executor success → `NativeServiceHost.promoteNative` | Recheck current queue/source, promote once; preserve queue/shuffle/repeat and continuing incoming position. Update MediaSession/player flow/UI; release outgoing resources |
| Fail / recover | executor cancel/recovery, controller `fallback`, independent legacy controller | Release native ownership before legacy work. No competing fades or false native labels; cancellation does not authorize stale promotion |
| Report | `AutoMixRecorder`, `PlayerDiagnostics` → deliberate redacted text export | Typed bounded records, salted identities, no titles/URLs/tokens/PCM; distinguish evidence/plan/mixing/completion |

`LegacyPlanningFeatures.require` is deliberately a barrier: native results cannot enter
legacy-only planner semantics. Missing native library/linkage is handled on the opt-in
analysis path and must not break normal playback. Technical failures may suspend native
attempts after three recognized failures in a service session; saved preference remains
selected. Read the actual recognized reason set in `fallback`; not every musical rejection
counts as a technical failure. Switching back to Legacy is supported without reinstalling.

## 7. Corrected decoding and source timelines

### Timeline terms that must not be collapsed

- **Encoded access units:** AAC/Opus/FLAC packet time and decoded frame counts, including
  priming, padding or support packets where relevant.
- **Container/media duration:** metadata in the encoded file; can be quantized or include
  different timing ownership than presented samples.
- **Presentation timeline:** edit lists/priming/padding determine where real audible samples
  belong. It need not begin at zero. MediaExtractor may already apply container edits.
- **Actual source coverage:** measured accepted source intervals/frame counts. Not inferred
  from catalog duration or complete cache bytes.
- **Decode interval:** the effective requested interval after legitimate known-duration
  handling; not automatically identical to the original request.
- **Materialized PCM interval:** `sampleTimelineStartUs` plus canonical frames/rate; filter
  edges and integer rounding are explicit. Never use the seek request as a substitute origin.
- **Analysis support / execution:** native beats are local to supplied PCM before mapping;
  planner/executor use track-relative microseconds in that same rendition's playback timeline.

[AnalysisContracts](../app/src/main/kotlin/com/music/vivi/playback/automix/AnalysisContracts.kt)
contains `AudioIntervalUs`, `AudioCoverage`, `DecodedWindow`, `DecodeRequest` and
`AnalysisControl`. Intervals are start-inclusive/end-exclusive. `AudioCoverage.status` is
not an epsilon-based “nearly full” checker. `DecodedWindow.invalidReason` validates structure;
engine/pipeline/planner add coverage and musical admission. Legacy permissive preprocessing
must never be labeled corrected.

### Preprocessing identities and repairs

| Identity | Meaning/history |
| --- | --- |
| `nearest-time-mono-22050-v1` | Legacy production preprocessing, preserved separately |
| `sinc64-blackman-mono-22050-v2` | Corrected bandlimited mono path and explicit coverage (2.2) |
| `sinc64-blackman-initial-preroll-mono-22050-v3` | Initial support/preroll repair after real codec instrumentation |
| `sinc64-blackman-preroll-frameclock-mono-22050-v4` | Source frame-clock/timestamp handling during FLAC investigation |
| `sinc64-blackman-flacframes-mono-22050-v5` | Bounded FLAC frame packets and platform codec corrections; strict EOS coverage |
| `sinc64-blackman-aac-tailclock-mono-22050-v6` | AAC tail-padding ownership across buffers/real retained timestamps |
| `sinc64-blackman-opus-support-mono-22050-v7` | Opus bounded preroll and endpoint support packets |
| **`sinc64-blackman-aac-preroll-mono-22050-v8`** | Current: genuine AAC support before nonzero seeks with fresh-codec delay handling |

Historical constants exist for cache rejection/provenance, not selection as current corrected
PCM. Old identities must not be silently reinterpreted. PR #27 leaves v8 unchanged and
versions its window strategy separately because decoder arithmetic itself is unchanged.

### AAC details

`AacAnalysisTailTrim` preserves the timestamp ownership of retained source PCM. In the
Phase 3.3.1 signature a 1010-frame output followed by a 1024-frame output exposed **14
retained tail frames**, not a license to duplicate/interpolate frames. Codec front-delay
ownership remains; corrected analysis configures codec tail padding separately and holds
bounded trailing PCM. At natural EOS genuine padding can be discarded; artificial request
boundaries must not discard valid support samples. Negative initial compressed PTS are
legitimate and cannot simply be normalized away.

A fresh codec configured with declared delay after a nonzero seek can trim needed samples.
For the reported seek: returned packet `123042539 µs` plus `1024/44100 s` yields first
complete output `123065759 µs`, after requested `123063000 µs`. **V8 recovers this head**
using real earlier packets: support is declared delay (or zero if absent) plus 2048 frames,
rounded up by configured rate. Bounded delay metadata/rates are validated; when support
reaches the origin, no seek discards negative priming packets. The codec's front-delay
metadata is not removed or applied twice. Source bytes/deadline limits remain finite.

The separate `0..32000000` → `137006..32000000` case has positive first compressed and PCM
PTS, no interior gap, zero declared delay/padding in the reported source. Generated valid
empty edits reproduce it without deleting `mdat` bytes; that does **not** prove the private
file has the same edit list. The outgoing endpoint `155062856` versus request `155063000`
can be reproduced by rounded movie duration versus real PCM frames. The exact private
endpoint is likewise unverified. Main correctly leaves those old requests partial. Do not
classify 137 ms or six-ish frames as ordinary microsecond rounding, prepend silence, clamp
all durations, or move sample origins to force FULL. PR #27 instead verifies a new interval.

### Opus and FLAC details

`OpusAnalysisWindow` supplies genuine pre-seek and post-request support packets. A reported
`2001000..6994500` result for `2000000..7000000` was not fixed by changing the assertion:
compressed packet cutoff and codec preroll/trim required support beyond nominal boundaries.
Near-end windows cannot imply audio beyond verified natural EOS. Preserve cancellation,
range/byte checks and separate duration provenance.

For corrected FLAC, `BoundedFlacPackets` uses the existing Media3 parser through the permitted
bounded source bridge. `PlatformFlacCodecPolicy` avoids unsupported/problematic vendor paths.
The Samsung/vendor investigation included repeated extractor timestamps and real missing
output at EOS; v4 frame clocks alone did not recover those missing frames. V5 fixes were
specific and measured, not a generic tolerance. Test codec/container behavior, not just
PCM math, when changing these classes.

`CorrectedPcmWindowCapture` accepts unique nearest-frame timestamp quantization below half
a frame, rejects ambiguous ties/genuine gaps/regressions, and accounts for overlapping
source ownership rather than manufacturing extra coverage. `BandlimitedResampler` materializes
22050 Hz mono Float32 with explicit filter-edge support. Front trim, filter support and
valid source samples are different concepts. A complete cache or a successful resample
cannot certify source samples that never arrived.

## 8. BitChord native integration and JNI

Authoritative source is [experiments/bitchord-native-analysis](../experiments/bitchord-native-analysis/),
pinned upstream **`fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`**. See
[provenance.json](../experiments/bitchord-native-analysis/provenance.json),
[NOTICE](../experiments/bitchord-native-analysis/NOTICE.md) and
[COPYING](../experiments/bitchord-native-analysis/COPYING). Selected files are
`tempo_analysis.cpp`, `resampler.cpp`, `resampler.h` and a declarations-only adapted
`audio_analysis.h`. CMake verifies hashes, then `ControlledSources.cmake` generates the
cancellable compilation copies. Do not edit generated outputs, reformat pinned files or
import another BitChord application/JNI layer. Models/Beat This!/ONNX/source separation
are not part of this native detector.

The actual algorithm uses FFT spectral onset/positive flux, autocorrelation, fractional
BPM estimation, phase tracking and heuristic bass-accent downbeats. The unchanged raw
confidence combines internal tempo/phase/separation evidence; it is **not a calibrated
probability** or the legacy Kotlin confidence scale. Noise/static tones can yield confident
false grids. Half/double BPM alternatives are unverified hypotheses. Key, phrasing, vocals,
meter and harmonic compatibility are not magically provided by native tempo analysis.

Standalone API `tinium::native_analysis::analyze(Request, Control)` borrows mono Float32,
explicit rate/origin and call control. It accepts bounded 8–96 kHz inputs, ≤32 s and
≤3,072,000 samples; API deadlines/cancellation are polled within expensive loops, with a
10-second native hard budget. Android engine accepts the narrower canonical **22050 Hz,
mono, ≤705600 floats** and uses the actual upstream sinc path to **11025 Hz** internally.
This offline symmetric conversion does not introduce a fitted acoustic correction. Beats
and heuristic downbeats are seconds relative to supplied PCM; Kotlin adds the actual
materialized origin once using checked arithmetic.

[Android CMake](../app/src/main/cpp/CMakeLists.txt) adds that tree as a subdirectory, selects
`tinium_automix` only, links the static core/runtime, hides internal symbols, and requests
16 KB link alignment. Host benchmark/reference/Python tools are excluded from APKs.
[JNI](../app/src/main/cpp/native_analysis_jni.cpp) implements Tinium-specific synchronous
bindings: version handshake, bounded copying in cancellation-aware chunks, per-call
control/owned references, finite dimensions/rates, statuses and result transport. Java
arrays are not retained/pinned long term. No mutable global DSP instance or detached native
thread is introduced. Native exceptions/allocation/validation errors become statuses;
arbitrary native corruption, segmentation faults and process termination are not recoverable
by catching a Kotlin exception.

[NativeDspPacket](../app/src/main/kotlin/com/music/vivi/playback/automix/NativeDspPacket.kt)
contains transport/bindings/lazy-loading control. Backend ID is **`bitchord-native-dsp`**,
version **`fe95bb60-controlled-v1-jni1-evidence1-internal11025`**. Profile combines that
backend and v8 PCM. `AnalysisResultValidation` and native/planner validators enforce source,
format, ordered finite in-range beats, downbeat membership and honest provenance. Every
result lacks unsupported key/vocal/phrase values rather than inventing defaults.

Both historical named Arm64 GMS and Universal FOSS debug artifacts actually contained
**arm64-v8a, armeabi-v7a, x86, x86_64**: flavor names do not currently impose ABI filters.
[Packaging checker](../scripts/check_automix_native_apks.py) inspects actual ELF segments,
ZIP offsets/alignment, native exports and generated notice assets. ELF alignment alone
is insufficient. APK assets include `licenses/bitchord-automix/`, corresponding notices,
AGPL/provenance and the NDK runtime notice. Preserve the relevant ProGuard/JNI names.

Historical prototype corpus: the internal-11025 path was within 2% BPM for 34/36 non-short
rhythmic fixtures versus 29/36 direct-rate cases. These generated-corpus outcomes do not
prove music-wide BPM or beat accuracy. Older seven-tempo feasibility comparisons use
another corpus; never present them as fresh controlled measurements. Refer to raw beat
precision/recall/F1, phase error, octave mistakes and non-rhythmic cases in prototype results.

## 9. Native planner, executor and Legacy coexistence

### Native planner v2

Version **`tinium-native-dj-planner-v2-duration-preservation`**. `NativePlanningEvidence.validate`
requires the right source/profile/backend, valid actual duration/support, ordered finite
beats and compatible local grid/tempo. It checks the native result/audit rather than
trusting a BPM number. Beat arrays remain local evidence; no extrapolation across an
unobserved song. Optional RMS-rise audit uses 20 ms blocks, not trained transients/vocals.

`NativeTempoCompatibility` enumerates 1:1 and explicit stride-2 octave hypotheses, retaining
uncertainty. Native rate stays **1.0**; `hypotheticalIncomingRate` is diagnostic, not applied
speed. Relative tempo compatibility is capped at **3%**. `NativeBeatAlignment.evaluate`
compares fixed corresponding pulse indices, with no nearest-beat phase rotation.
Defaults evaluate 8/16/32/64 pulse intervals, overlap 3..48 s within local available support,
bounded outgoing/incoming anchors/candidate counts. Timing limits are median **25 ms**,
P95 **40 ms**, maximum/end/predicted drift **70 ms**. Transient support must corroborate
for executable readiness; timing-only signal-unverified plans are not admitted to execution.

Incoming unverified intro skip is limited to **1 s**, extended only by appropriately
measured leading quiet evidence under existing rules. An adapted nonzero window is not
proof that all preceding content was expendable. Vocals/phrases/harmony/meter remain unknown.
`NativeCuePreservation.describe` permits unheard remainder no larger than the minimum of
**5% of outgoing duration** and **eight effective pulses**. Ranking retains alignment/signal
gates, prefers supported late endpoints and never invents semantic outro boundaries.

Concrete owner baseline (not a new measurement): duration `163865000`, cue `159274221`,
overlap `3234207`, endpoint `162508428`, remainder `1356572 µs`, about **99.17% preserved**,
one successful promotion. An earlier cue `132097200` with 6.49 s overlap discarded about
25.3 s after handoff; that technical success motivated the v2 preservation rule. A later
candidate is not always available; rejection can be better than an early cut.

### Executor v5 and gain

Version **`tinium-native-dj-executor-v5-bracketed-media-clocks`**. The executor validates the
plan again against current queue, exact source/rates/coverage/readiness and capability;
claims exclusive control; prepares incoming paused at the approved cue; arms and starts;
then applies progress-based gain while both players genuinely advance. Success promotes
incoming once at **completion**, leaves its position/queue intact and releases outgoing.
Pauses freeze progress; reentrant pause/resume guards prevent callback loops. Stall and
position-discontinuity detection uses independent deck progress, not simultaneous clock ticks.

Core configuration: measured/guarded drift **≤120 ms**, late command **≤40 ms**, cue readiness
**≤15 ms**, preparation timeout **10 s**, start timeout **1.5 s**, progress timeout **2 s**.
Limits are validated and cannot be loosened merely to make more outcomes green.

`NativeDjClockPair.capture` brackets each sequential position query with monotonic times.
For cue-relative outgoing progress A and incoming progress B:

```text
raw offset = A - B
common-instant point = raw offset + (incoming query midpoint - outgoing query midpoint)
uncertainty = half-widths of both query brackets, rounded conservatively
bounds enclose BOTH raw and projected points plus uncertainty
guarded drift = max(abs(lower bound), abs(upper bound))
```

The adjusted point cannot excuse an unsafe raw offset. Media3 positions are not sample
clocks or acoustic timestamps. Actual AudioTrack timestamps remain observation-only.
Tempo-induced beat divergence at rate 1.0 is a different quantity from media-clock drift.
A 1% BPM difference can create meaningful phase error while both clocks progress normally.

Startup prediction uses a fresh compatible source/queue/format/route observation. It expires
after **10 s**, and a missing/expired/incompatible observation means explicit zero lead.
No universal Samsung delay, audible calibration tone or cross-route historical offset is
used. The v3/v4 startup and reentrancy repairs plus v5 clock bounds improved observability
and safety; intermittent `EXCESSIVE_DRIFT` remains **unresolved**, including zero-lead cases.
First offset near 10–15 ms followed by >120 ms does not alone distinguish renderer startup,
position-reporting phase, actual delay or sustained mismatch. Preserve full traces.

[NativeDjGain](../app/src/main/kotlin/com/music/vivi/playback/automix/NativeDjGain.kt) uses
active 100 ms block RMS medians, not a quiet outgoing tail-to-loud incoming ratio. Comparable
content attenuation is bounded to approximately **0.708..1**, never gain boost. Missing,
quiet or incompatible observations use unity. Default curve is **RESTRAINED_DJ** with
meaningful incoming introduction through the overlap; correlation-safe envelopes restore
continuing incoming unity. This is not LUFS normalization, per-deck bass swapping or
professional musical phrasing. External EQ can change post-processor peaks/level.

### Legacy AutoMix is a separate system

`LegacyKotlinAnalysisEngine` retains exact `TrackFeatures` for `TrackFeaturesAnalyzer`,
legacy preprocessing and local energy/key/bass/vocal-risk proxies. `AutoMixController`
coordinates scan/refinement and cached feature work; `AutoMixPlanner`/`DjCandidateSelector`
select tiers A/B/C and techniques `BEAT_BLEND`, `BASS_HANDOFF`, `GENTLE_FILTER`, `ENERGY_FADE`,
`SAFE_FADE`. Existing confidence and phase gates (including the 0.65 rhythm gate in candidate
selection) are legacy semantics, not a target scale for native confidence.

Legacy can apply its own low-frequency/treble automation and bounded Sonic rate adjustment.
Those implemented effects do **not** mean Native DJ has Phase 3.4 DSP. Read
[AutoMixAudioProcessor](../app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixAudioProcessor.kt)
and [legacy quality guide](AUTOMIX_QUALITY_AND_EXTERNAL_EQ.md) before modifying them.
Legacy promotes at fade start, uses media positions for progress, and restores temporary
rate/gain/effect registrations on cleanup. Native promotes at completion. MusicService's
mutual-exclusion/host checks ensure only one controller can own a transition.

Native failure before execution may enable independent legacy analysis with legacy profile
and cache keys. Once native execution owns decks, rollback/recovery must complete before
another fade. Low raw confidence alone is not a technical reason to disguise legacy evidence
as native. Three recognized technical failures may suspend native attempts for that service
session without editing the user's engine preference. Ordinary unsupported musical pairs
can legitimately fall back; successful decoding is not a command to mix.

## 10. Adaptive analysis and stable reuse — implemented only in PR #27

Pinned review source: [PR #27](https://github.com/yren411/vivi-music-personal/pull/27),
[guide](https://github.com/yren411/vivi-music-personal/blob/f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2/docs/AUTOMIX_ADAPTIVE_ANALYSIS_AND_REUSE.md),
[raw evidence](https://github.com/yren411/vivi-music-personal/tree/f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2/docs/benchmarks/phase334-adaptive-analysis).
These paths must not be assumed to exist on main. Do not cherry-pick a second duplicate port.

`AdaptiveNativeRegions` defines **`native-regions-v1-verified-inset`** separate from v8 PCM.
One successful continuous partial decode may guide a new request inset by ceil-start/floor-end
milliseconds. It must contain 8..32 s; the new full request is independently decoded and
validated. Unknown/multiple/failed/gapped/synthetic coverage cannot simply authorize recovery.
Examples: `138000..32000000` after a verified 137006 µs start; or endpoint `155062000` after
verified end 155062856. Neither rebases the source nor relabels the old request FULL.

`NativeRegionAudit` retains original intent, selected interval and originating decode cost.
The result metadata retains actual source/support/origin. Job keys include source/profile
and backend, detail, signal/gain versions, strategy, exact intended interval and immutability.
This is an **intent lookup with explicit selected provenance**, not aliasing different
requested windows. Different overlapping inputs are not silently treated as identical DSP.

`AnalysisWorkers.acquire` adds consumer-specific leases. Equivalent work coalesces; canceling
one observer cannot destroy another. Immutable work may survive seek/route observers under
its original deadline, including while waiting for the shared concurrency budget. The existing
service tick expires it without new timers/threads. Completed source evidence is generation
independent; pair plans/executions remain generation bound. Queue/source changes, fallback,
configuration changes and shutdown still cancel pending old jobs. Unverified mutable sources
are not granted durable reuse. Local files gain reuse under existing rendition/length/mtime
checks. `cancelAll` still preserves eligible completed successes and never caches failures.

`NativeRegionCatalog` retains at most eight sources × six compact regions; existing worker
LRU is 64, so independently evicted catalog references can yield at most 112 distinct compact
results. No PCM/disk evidence cache is introduced. Optional enrichment requires an already
selected plan, playback and ample lead; one job at a time, ≤3 jobs/source, ≤6 s/job. Each job
can make its one bounded boundary retry. Lead reserve is `max(10 s, observed pair cost*3+2 s)`.
MusicService rechecks local identity or complete cache bytes before every optional source;
remote optional factories have **no upstream or writer**. Eviction fails closed. Pause,
preparation/mixing/fallback and lifecycle invalidation stop optional work.

This is selective progressive coverage, not unconditional full-song scanning. A short song
can be covered by overlapping independent windows; long recordings remain sampled. Regional
BPM agreement/conflict/octave uncertainty is reported without averaging or stitching grids.
The planner still uses local support and unchanged preservation rules. Enrichment does not
rescue every non-rhythmic intro or implement vocals/phrase detection, and it requires a
usable initial plan in this conservative implementation.

New diagnostics separate originating cached audit cost from cumulative **actualDecoderCalls**,
measured frames, pool executions/hits/coalescing, region union/bounds, optional progress and
seek/route replans. A cache hit must not be summed as a new decode just because its audit
contains an earlier cost. Same-source seek replans/cancels unsafe schedules; unchanged
route/capture notifications preserve safe state. Actual route changes still reset startup
calibration and execution. In-flight reuse inherits an earlier deadline, never extends it.

One old AAC service fallback method retains its assertions with a **30 s genuine empty-edit
fixture**, because the 137 ms edge is intentionally recoverable under this strategy. The
strict 137 ms native rejection and real-gap/14-frame/Opus tests remain. Twelve new Android
methods are compiled; expanded acceptance is 91. Recorded local suites are 599+599, including
38 real host-JNI executions. No owner-device pass for this new code was found in the PR
body/comments or supplied follow-up. Both PR workflows are green, not evidence of merge.

### Recorded host performance and calculated budgets

The pinned PR evidence records a generated/simulated source transport with real corrected
capture, resampling and host JNI. A boundary discovery plus adapted request took about
**358.0–358.2 ms** cold, with two source-decode executions; native work was about
**191.5–193.7 ms**. One hundred completed-cache hits took **0.50–0.67 ms total**, without
new source decoding. These are host wall times for those fixtures, not Android MediaCodec,
real cache/network throughput, phone CPU use or acoustic measurements.

Three independently analyzed 32 s regions of a generated 96 s model reported BPMs
128.0793 / 128.0866 / 128.0743. Initial-region wall time was about 262–299 ms, optional
remaining regions about 479–490 ms. The result reports regional agreement, not a global
BPM or stitched grid. Preserve the raw initial and final measurements in that PR.

Calculated canonical Float32 PCM sizes: 32 s = **2,822,400 bytes**, 180 s = **15,876,000**,
1800 s = **158,760,000**. A boundary retry may temporarily retain another canonical window;
source-rate capture, JNI copies, codec buffers and native FFT working memory add to this.
The catalog/LRU bound limits result count, not a precise heap-byte cap. No current measured
phone RSS, battery or thermal result establishes the experiment's extended-use cost.
See the pinned `resource-budgets.json` and host JSON files, rather than treating arithmetic
as a measured memory profile.

## 11. Settings, route safety and invalidation

UI path: **Settings → Player and audio → Player → AutoMix → AutoMix engine**. The engine
selector appears while AutoMix is enabled. Options are **Legacy AutoMix (default)** and
**Native DJ AutoMix (experimental)**. Existing enum preference values are stable. Return to
Legacy there; do not clear preferences or reinstall as an emergency workaround.

`NativeOutputRouteTracker` compares actual output endpoint/AudioTrack identity, not merely
AudioManager's device availability list. It distinguishes repeated/equivalent observations,
material output replacement, unknown/mismatched unsafe state, and paused/deferred observations.
Incoming standby initialization or capture-device availability is not automatically a physical
route change. An actual track replacement may invalidate timing even when speaker category
is unchanged. Route IDs remain ephemeral/in-process; exports use controlled categories.

| Event | Merged main | PR #27 differences |
| --- | --- | --- |
| Ordinary progress | Keep valid pairing/schedule; no unconditional per-tick decode | Same, plus bounded optional enrichment |
| Same-source seek | Cancel pair/unsafe execution and pending native batch; immutable completed LRU can survive; local files are not cacheable in main | Rejoin compatible in-flight leases and reuse validated local-file evidence; replan rather than preserve armed schedule |
| Unchanged capture/route notification | Existing tracker retains safe plan, no material invalidation | Same; explicit reuse counters clarify apparent UI refresh |
| Material output/track change | Cancel/recalibrate; completed compatible musical evidence may survive | Preserve eligible in-flight source evidence too, while canceling unsafe schedules |
| Incoming/source/quality change | Invalidate stale pair and pending work; compatible completed cache remains separate | Same strict ownership; no stale observer may promote a replaced song |
| Disable/shutdown | Cancel work/owned decks, preserve user data; close clears worker cache | Same, plus catalog cleanup and optional-job cancellation |

A screen-recording-associated UI refresh is not proof that the physical route changed, nor
proof of fresh DSP. Compare source/profile, decoder records, cache counters (in #27), plan
generation and actual route materiality. Built-in Samsung capture behavior remains a manual
validation item; injected notification tests do not automate it.

## 12. Diagnostics and debugging decision tree

[PlayerDiagnostics](../app/src/main/kotlin/com/music/vivi/ui/component/PlayerDiagnostics.kt)
exposes Stats for Nerds / transition marker information and a deliberate long-press/accessibility
log export. Show the existing diagnostics setting, then long-press the displayed diagnostic
area. On API 29+ the log writer saves to Downloads through MediaStore; older Android uses
a document picker. The owner device is API 37. No broad storage permission or upload is
required by this small AutoMix report. See [pipeline/export guide](AUTOMIX_PIPELINE_AND_DIAGNOSTICS.md).

`AutoMixRecorder` render schema is **2**, native extension **1**. It retains bounded history
(default 30 attempts, 128 events each), salted service-session source hashes and typed values.
Reports are cumulative retained attempts, not independent unique song pairs. Song titles
cannot be recovered from hashes without separate owner context. `NativeServiceEvent` separates
PAIR/EVIDENCE/PLAN/EXECUTION/FALLBACK/CANCELED/ROUTE; PR #27 adds reuse records. Decoder timing
subschemas and clock schema 3 are additive, not a promise every value was collected.

Key fields: requested/effective/actual source coverage, real frame counts, first/last packet
and output PTS, delay/padding/preroll, source byte/cache/range status and stage elapsed time;
raw native BPM/confidence/status; planner readiness/hypotheses/residuals/candidate rejections;
planned/actual cue/overlap, content gains, startup lead/reference/prediction state, raw/common
clock offset/brackets/guarded drift, independent progress/stalls, route materiality and
promotion count. A source can be fully cached and still have partial presentation coverage.

Debug in this order:

1. **Mode and pair:** Native selected? Session safety suspended? Actual next source resolved
   and pinned? No native attempt may simply mean output/source/configuration readiness is absent.
2. **Decoder:** Identify role and exact request. Compare effective interval, coverage and
   actual frames. `SOURCE_GAP` is different from an edge-only partial request, empty output,
   natural EOS, request cutoff, byte/range denial or deadline. Do not fix by relabeling FULL.
3. **Native:** Did JNI actually complete? Library/linkage/invalid output is technical failure;
   valid NO_EVIDENCE or a wrong/ambiguous BPM is a separate analytical limitation.
4. **Planner:** Were both source/profile/duration/local-support results admitted? Read rejection
   category and readiness. PLAN is a proposal, not player execution. Musical rejection is not
   necessarily a decoder defect.
5. **Executor:** Did preparation reach readiness, arming and start? Compare exact cue, lateness,
   prediction validity and real progress. STARTING/MIXING does not equal COMPLETED.
6. **Invalidation:** Queue/source/generation change, user seek, focus or actual route/track
   replacement? Preserve safety; do not suppress events to keep a slider marker.
7. **Drift:** Read raw/common/bounded offsets and query widths plus per-deck progress. Preserve
   >120 ms failures. Output timestamps or small point estimates are not acoustic synchronization.
8. **Handoff:** Verify terminal COMPLETED/appropriate recovery and exactly one promotion;
   ensure a single surviving output, continuing incoming position and restored volume.
9. **Listening:** Separately record cue appropriateness, vocals, beat feel, loudness, stutter,
   external EQ and whether fallback sounded preferable. No subjective score is invented.

Only the AutoMix export's typed/redacted design is established here. Do not assume every
other app log/crash/backup path redacts credentials. Never paste cookies, signed URLs,
private paths, account tokens, audio or unredacted personal history into public issues.

## 13. Security, privacy and licensing

Account/login/visitor data, proxy settings, Spotify tokens, Last.fm/Discord sessions and AI
provider credentials pass through feature-specific settings/stores. Use Android's sandbox
and current permission checks; do not claim all stores are encrypted without an audit.
Backups may carry sensitive data. Recognition records microphone audio only through its
permission-controlled flow and sends fingerprints for matching; optional integrations send
network requests. The README's “100% local / absolutely zero background activity” language
must not be treated as an audited privacy guarantee.

**Embedded credential material exists in `app/build.gradle.kts` for a third-party integration.**
Values are intentionally omitted from this handoff. Commented environment-based examples
do not mean the active code uses them. Human review should assess exposure/rotation and
future secure configuration. Do not reproduce those literals, dump environment files,
commit keystores or “fix” this documentation task by changing production authentication.

[LICENSE](../LICENSE) contains GPLv3 text plus a restrictive Musixmatch exception. [rules.md](../rules.md)
provides conditional reuse/attribution rules for Vivi Music/Musixmatch and JioSaavn, while
specific module headers carry restrictive wording. [Licensing reassessment](AUTOMIX_BITCHORD_NATIVE_LICENSING.md)
records historical GPL grants, later changes and unresolved revision/rights-holder scope.
Do not discard either side of that evidence, assume the last header overrides every prior
grant, or assert rules alone clear every later contribution. The owner supplied development
permission as a working assumption; it is not independently verified public redistribution
permission. Do not remove/rewrite Musixmatch during unrelated engineering work.

BitChord/Orchard native files and adapters require **AGPLv3-or-later** attribution/source
and license compliance. Root GPL text does not relabel AGPL files. Preserve native hashes,
notices, generated license assets, corresponding source/build modifications, runtime notices,
and assess interactive/network obligations with qualified human review. Review requested
provider UI credits, JioSaavn terms and source/code rights separately from access to lyric
catalogs or copyrighted music. Third-party packages, recognition provenance (SongRec-derived
Kotlin port), artwork/canvas assets, fonts and branding deserve an independent full license/
asset inventory before public distribution. No complete legal clearance or SBOM is claimed.

## 14. Environment, dependencies, CI, signing and releases

### Pinned build facts

| Component | Verified value/source |
| --- | --- |
| Java | Toolchain/JVM target 21; [daemon JVM file](../gradle/gradle-daemon-jvm.properties) |
| Gradle | Wrapper 9.3.1; [wrapper properties](../gradle/wrapper/gradle-wrapper.properties), not an installed global Gradle |
| AGP / Kotlin / KSP | 9.1.1 / 2.3.10 / 2.3.6 |
| Compose UI / Material3 | 1.11.4 / 1.5.0-alpha27; inherited alpha dependencies require care |
| Media3 | 1.7.1; Android Cast split in app flavors |
| Room / Hilt / DataStore / WorkManager | 2.8.4 / 2.60.1 / 1.2.0 / 2.10.0 |
| Ktor / Coil / protobuf | 3.4.0 / 3.3.0 / 4.33.5; protobuf plugin 0.9.6 |
| InnerTubeX / NewPipe | v0.7.0 / v0.26.1 dependency pins; remote service reliability separate |
| Android | min 26; compile/target 37 |
| NDK / Android CMake | 27.0.12077973 / 3.22.1; C++17, static libc++ target |
| Host native tools | CMake ≥3.20 for prototype (≥3.22.1 for JNI CMake), C++17 compiler, Python ≥3.9; JDK/JNI for host JNI |
| Build Tools | No explicit app pin; historical packaging checks use SDK Build Tools 37.0.0 `zipalign`. Install AGP-requested defaults as needed |
| Repositories | Google/Maven Central/JitPack and configured Maven mirror; preserve TLS/artifact verification |

Complete dependency coordinates/version aliases: [libs.versions.toml](../gradle/libs.versions.toml),
[root build](../build.gradle.kts), app/module build files. Do not silently upgrade an alpha
Compose dependency, AGP/Kotlin or Media3 during unrelated work. `gradle.properties` enables
parallel/configuration-cache behavior, 6 GiB JVM options and disables Gradle build caching;
its comments are not stronger than actual wrapper/DSL settings. Use conservative worker
counts on small machines, and `--no-configuration-cache` for the documented device workflow.

Linux CI and owner Windows/Android Studio workflows have evidence; generic macOS/Windows
host-native runtime parity is not established. Install Android Studio supporting these pins,
JDK 21, SDK Platform 37, accepted SDK licenses, NDK and CMake side-by-side packages. Keep SDK
path in ignored `local.properties` or secure environment configuration. Protobuf Java/Kotlin
lite generation is handled by Gradle. No Go code/submodules/manual proto-generation script
was present; the old development guide's missing script/Go instructions are not prerequisites
for the selected Android build. FFTW helper remains in CI, but `tinium_automix` has no FFTW
link dependency and unrelated vibrafp is not a selected target.

PowerShell example, adapt the SDK path to the real local installation:

```powershell
git clone https://github.com/yren411/vivi-music-personal.git
cd vivi-music-personal
git status --short
git switch main
git pull --ff-only
# Set JAVA_HOME to your JDK 21 through Android Studio/environment settings.
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-37" "build-tools;37.0.0" "ndk;27.0.12077973" "cmake;3.22.1"
.\gradlew.bat --version
.\gradlew.bat :app:tasks --all --no-configuration-cache --no-daemon
.\gradlew.bat :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug --no-configuration-cache --no-daemon --stacktrace
```

SDK Manager command-line-tools location can differ; Android Studio SDK Manager is equivalent.
Accept licenses deliberately under the SDK's terms. Debug needs no release credentials.
Existing debug key matters: do not regenerate/overwrite it to fix a signing conflict. Normal
debug and release use the same production app ID with different certificates; careless
installation can fail or require a data-destructive uninstall. Use the isolated property
in section 15 for device work. A flavor label alone does not prove its ABI contents.

### CI and release authority

| Workflow | Actual purpose / boundary |
| --- | --- |
| [Build PR](../.github/workflows/build_pr.yml) | PR unit task Universal FOSS, FOSS app/lint, disposable-key Python signing/release regressions and debug artifact |
| [CI & Debug Build](../.github/workflows/build.yml) | Universal GMS unit/build; push/PR deduplication and trusted/disposable debug-key paths |
| [Build Tinium Development APK](../.github/workflows/build-apk.yml) | main/manual Arm64 GMS debug build using persistent debug identity |
| [Beta](../.github/workflows/beta_release.yml) / [Production](../.github/workflows/release.yml) | Manual main candidate builds, reserved versions, permanent signing and exact provenance/artifacts; not automatic public publication |
| [Publish Tested Tinium Release](../.github/workflows/publish_release.yml) | Explicit manual approval of exact tested run/attempt/tag/notes; no rebuilding or silent retargeting |
| [Nightly](../.github/workflows/nightly.yml) / [Telegram nightly](../.github/workflows/nightly-telegram.yml) | Legacy beta-branch jobs explicitly paused with false job conditions, not active scheduled release infrastructure |
| [Stale issue handler](../.github/workflows/stale.yml) | Scheduled issue maintenance; green does not validate an APK |

Lint currently has `abortOnError=false`; a green workflow is **not clean lint**. Optional
host-JNI tests skip without the host-library property. CI build success does not execute
Android instrumentation, prove remote providers work or establish redistribution permission.

Read [APK_SIGNING](APK_SIGNING.md) and [RELEASING](RELEASING.md) before any candidate operation.
Secure CI secret names include `DEBUG_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; local release
process environment additionally supplies `ANDROID_KEYSTORE_PATH`. Names are configuration
identifiers, not values. Never request passwords/tokens in chat or store them in Markdown.
Trusted CI restores existing identities outside checkout; PRs use disposable debug keys.

`release_tools.py`, `manage_releases.py`, `release_apks.py`, `release_notes.py` and signing/
verification helpers coordinate immutable `tinium-code/<10-digit-code>` reservations and
publication. Codes **78–83** already exist; the latest inspected reservation is
`6.0.8-beta.6` / 83 pinned to `801915d816941c6176016abbc808a62972867abc` (older legacy-era
source). No releases were returned by the GitHub Releases API; this does not negate reserved
codes or externally installed candidates. Never reuse an old version/tag for newer code.
No new version is assigned by this handoff. Draft notes and retained notes files do not
constitute owner approval to publish. Public repository Actions artifacts are not private.

## 15. Testing and evidence provenance

### What is recorded, and what is not

| Checkpoint | Evidence and scope |
| --- | --- |
| Phase 2.2 | Owner-reported four decoder device tests; historical, not a fresh whole-app check |
| Phase 2.3A | Standalone generated-corpus/CTest/sanitizer/NDK cross-build artifacts; not Android runtime or commercial-song proof |
| Phases 2.3B/3.1/3.2 | Owner reported native 14, planner 6, executor 14; corrected decoder 4 → combined prior 38 |
| Phase 3.3 | Later prose records 497 GMS + 497 FOSS, 994 total including 17 host-JNI cases/variant; owner reported combined 49 Android passes. Initial raw validation JSON has 456/variant, a different earlier checkpoint |
| Phase 3.3.1 | Initial local 507/variant, then 508 fixture-contract checkpoint and separate Opus follow-up. Owner reported 59 combined Android passes |
| Phase 3.3.2 | Initial raw 546/variant; later documentation records 550/variant, 1100 total. Owner reported 69 combined passes; probes did not cure intermittent startup failures |
| Phase 3.3.3 / current main | Recorded 563+563=1126, zero failures/errors/skips, 34 actual host-JNI executions included. Owner reported ten new then 79 combined Android passes; no raw runner XML or independently verified installed SHA supplied |
| PR #27, unmerged | Recorded 599+599=1198, zero failures/errors/skips; 38 host-JNI executions included. Both app/test APK variants and four-ABI/16 KB/license checks. Twelve new Android methods **compiled**, Codex execution 0, expanded 91 **pending** |
| This handoff task | Repository/API/source inspection and Gradle `:app:tasks --all` succeeded. No application, JVM/native or Android test suite rerun; documentation checks only |

Do not add totals from reruns or double-count host-JNI methods inside JVM totals. Raw JSON
is evidence for its stated revision; later prose can describe a distinct later run. Preserve
both, rather than “fixing” initial artifacts to newer counts. Read the [snapshot](TINIUM_CURRENT_STATUS.md)
for exact checked workflow URLs/SHAs. The owner's latest 79 pass does not establish PR #27's
new 12/91 pass, acoustic alignment, real-source gap recovery or global EQ equivalence.

### JVM, host JNI, standalone native and packaging

Linux shell, with JDK 21/SDK configured and CMake/C++/Python installed:

```sh
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest --no-configuration-cache --no-daemon --max-workers=4 --console=plain
# Genuine host JNI (not an Android runtime test):
cmake -S app/src/main/cpp -B /tmp/tinium-host-jni -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/tinium-host-jni --parallel 4
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest -PautomixNativeHostLibrary=/tmp/tinium-host-jni/libtinium_automix.so --no-configuration-cache --no-daemon --max-workers=4 --console=plain
# Standalone native core and sanitizers:
cmake -S experiments/bitchord-native-analysis -B /tmp/tinium-native -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/tinium-native --parallel 4
ctest --test-dir /tmp/tinium-native --output-on-failure -V
cmake -S experiments/bitchord-native-analysis -B /tmp/tinium-native-asan -DCMAKE_BUILD_TYPE=RelWithDebInfo -DTINIUM_SANITIZERS=ON
cmake --build /tmp/tinium-native-asan --parallel 4
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 ctest --test-dir /tmp/tinium-native-asan --output-on-failure -V
```

Host library `.so` commands are Linux-specific; do not claim Windows host-JNI parity from
Windows phone testing. Preserve native `.gitattributes` LF rules and pinned hashes. Optional
FFmpeg/ffprobe fixture generation is not required to compile committed Android fixtures.
Prototype benchmark commands are in its README; use a fresh external results directory,
not overwrite historical evidence. Kotlin/FFmpeg comparator inputs must be the same fixtures.

```sh
bash gradlew :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest -PautomixDecoderTests=true --no-configuration-cache --no-daemon --max-workers=4 --console=plain
python3 scripts/check_automix_native_apks.py --readelf "$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" --zipalign "$ANDROID_HOME/build-tools/37.0.0/zipalign" --output /tmp/tinium-packaging.json app/build/outputs/apk/arm64Gms/debug/app-arm64-gms-debug.apk app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk
python3 -m unittest discover -s tests -v
```

Signing regressions that need APK/key fixtures have explicit environment prerequisites;
without them some Python checks skip. The last command does not authorize release/signing
changes. Branding has its own [validator](../scripts/validate_tinium_branding.py).

### Android: actual selections and safe isolated installation

Configured suffix property **`-PautomixDecoderTests=true`** changes debug app to
**`com.yren411.tinium.decodercheck`**; production ID stays unchanged. Existing test helpers
check that suffix. Connected tasks install/update app and runner; preserve the working
local debug key. Do not uninstall normal Tinium, clear app/cache data, change global app ID
or replace signing keys to work around an install conflict.

Main acceptance inventory, verified by counting current source annotations:

| Class in `com.music.vivi.playback.automix` | Methods |
| --- | ---: |
| `AndroidWindowDecoderInstrumentedTest` | 4 |
| `BitChordNativeAnalysisInstrumentedTest` | 14 |
| `NativeAwarePlannerInstrumentedTest` | 6 |
| `NativeDjExecutionInstrumentedTest` | 14 |
| `NativeAutoMixServiceInstrumentedTest` | 11 |
| `RealWorldDecoderInstrumentedTest` | 10 |
| `NativeDjReliabilityInstrumentedTest` | 10 |
| `AacBoundaryCoverageInstrumentedTest` | 10 |
| **Selected main acceptance total** | **79** |
| `AdaptiveNativeAnalysisInstrumentedTest` (**only PR #27**) | **12**, expanded **91** |

[Android source directory](../app/src/androidTest/kotlin/com/music/vivi/playback/automix/)
also contains one-method `NativeDjStartupClockInstrumentedTest`,
`NativeServiceStartupDiagnosticInstrumentedTest`, `NativeFlacEosInstrumentedTest` and
`NativeDjLocalListeningInstrumentedTest`. These diagnostic/opt-in classes are **not** the
79/91 acceptance selection. Read their inputs and assertions before running them. An
observation-only probe can pass with a reported playback fallback; local-listening tests
may require private external inputs. Do not count them or their skips as acceptance proof.

Windows PowerShell, repository root, stable speaker output, no concurrent test runs:

```powershell
.\gradlew.bat :app:assembleArm64GmsDebug :app:assembleArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --stacktrace
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AacBoundaryCoverageInstrumentedTest,com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest,com.music.vivi.playback.automix.NativeDjReliabilityInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Expect 79 executed, zero failures/skips for that main selection. Generated playback methods
make sound; pause normal music and choose comfortable volume. Test results:
`app/build/reports/androidTests/connected/` and `app/build/outputs/androidTest-results/connected/`.
For one method append `#methodName` to its exact class. A missing runner/class or zero executed
tests is not a pass. Do not change assertions just because the phone timing is unfavorable.

To validate the **existing draft** adaptive implementation instead, first inspect/stash only
your own changes safely, then fetch its branch (do not merge it automatically):

```powershell
git fetch origin
git switch codex/automix-adaptive-analysis
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AdaptiveNativeAnalysisInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Expect 12 methods; after they pass, add that class to the complete 79 selector for **91**.
Running both full 79 and full 91 duplicates coverage. The [pinned PR #27 guide](https://github.com/yren411/vivi-music-personal/blob/f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2/docs/AUTOMIX_ADAPTIVE_ANALYSIS_AND_REUSE.md)
has the complete 91 command and manual acceptance procedure. Its native library/ABI execution
still needs actual Android results. Installing a test APK is not updating normal Tinium.

Useful deliberate diagnostic retrieval after a failed selected method:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb devices
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase333-aac-boundary-last.txt
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase33-service-last.txt
# PR #27 additionally writes files/phase334-decoder-last.txt
```

`run-as: unknown package` means the isolated app is absent, was removed by the test runner,
or a different package/device was targeted—not proof that a codec failed. Use the correct
property and leave-installed runner flag; do not uninstall the real app. No logcat output
may reflect tag/process/history scope, not successful execution. Preserve current assertions,
exact tested source, command, device/route, executed count and XML where possible. Service
harness progress/failure/thread files are bounded troubleshooting tools; don't add sleeps
or wait forever for global idleness.

## 16. Known issues and unfinished work register

Severity is an engineering prioritization for the experiment/release, not a claim that normal
legacy playback is broken. P1 = release/reliability blocker; P2 = targeted follow-up; P3 =
maintenance/clarity. “Not established” is intentionally different from a reproduced defect.

| Issue / severity / state | Evidence and root-cause status | Previous work and recommended next investigation | Files/tests/PRs |
| --- | --- | --- | --- |
| Boundary-only AAC partial coverage, P1 experimental; main remains conservative | Owner: initial +137006 µs and trailing −144 µs on complete caches. Private edit/endpoint cause unconfirmed; these old requests genuinely lack certified coverage | V8 recovered separate +2759 µs head via real preroll. Validate #27's new fully supported intervals on genuine codecs and original authorized sequence; do not force FULL | `AndroidWindowDecoder`, `AacAnalysisWindow`, `AacBoundaryCoverageInstrumentedTest`; #26/#27 |
| Intermittent `EXCESSIVE_DRIFT`, P1; unresolved | Both completed handoffs and >120 ms/large startup failures exist. Bracketing shows conservative bounds, not a physical cure | Preserve failures; capture command/query/progress/output-route traces; distinguish startup delay/reporting phase/sustained error. Never widen guard | `NativeDjClockObservation`, executor, startup/route probes; #22/#25 |
| Repeated detection after seeks, P2; partially addressed only in draft | Main cancels pending work, local files not completed-cacheable. Immutable completed successes already survive cancellation | Validate #27 leases/cache hits versus actual decode calls, deadline behavior and UI markers; changed/mutable sources may legitimately decode again | `AnalysisWorkers`, controller, `AnalysisPipelineContractsTest`; #20/#27 |
| Screen-recorder-associated refresh, P2; partially explained | Material=false stable speaker observations exist. Actual track replacement/unknown timing may still cancel; built-in recorder not automated | Compare actual endpoint/track, focus/session and generation before/after Samsung recording; preserve source evidence without preserving unsafe schedules | `NativeOutputRouteTracker`, MusicService, reliability instrumentation; #25/#27 |
| Adaptive region selection, P1 validation gap | #27 has actual code/host evidence, compiled 12 Android cases and green CI; not merged and no new phone pass found | Run targeted 12 then 91; inspect private-song source coverage and planner/executor outcomes separately. Do not implement a duplicate | #27 branch, `AdaptiveNativeRegions`, new service tests |
| Optional enrichment limits, P2 known design limitation | Selective ≤32 s segments; not unconditional whole-song processing or a streaming API. Requires an already useful plan | Measure phone memory/CPU, cancellation and benefit; don't mask missing phrase/vocal evidence or keep PCM for a whole long recording | #27 catalog/leases, progressive host evidence |
| Native false-positive confidence/octave ambiguity, P2; known | Generated noise/chord/tonal controls and wrong metrical estimates can be confident | Keep raw scores and uncertainty. Broaden lawful corpus/beat metrics before any calibration/detector proposal | prototype results, native adapter, planner validation; #19/#21 |
| Musical cue/overlap quality, P2; real listening incomplete | Early technically completed handoff discarded ~25 s; later v2 example preserved 99.17%. Vocals/harmony/phrases unknown | Keep v2, evaluate authorized pairs and negative feedback; advanced musical detectors need separate justified work | `NativeCuePreservation`, planner, #25; future Phase 3.4+ |
| Real-source rendition matching, P1 safety invariant; not universally validated | JioSaavn/YouTube/quality/expired URL changes can alter timing/content despite same title | Reproduce with pinned authorized bytes and compare playback/analysis coordinates; never remove source pin gates | resolver, registry/pin, service/native validation |
| EQ/Bluetooth/acoustic compatibility, P1 before broad activation | Session lifecycle tests do not prove audible Poweramp processing or sample lock | Manual speaker/headphones/Bluetooth and EQ on/off; acoustic measurement only with authorized suitable equipment | effects lifecycle, actual Media3 tests; #12/#22/#25 |
| Phone battery/thermal/heap, P2; unmeasured | Host RSS/wall time and calculated PCM sizes are not Android battery/RSS | Bounded ordinary-listening profiling; preserve playback-first scheduling and worker budget | #27 resource evidence; existing worker/decoder bounds |
| Live provider/cipher/PoToken/canvas/lyrics availability, P2 operational risk | Source paths exist; no fresh external-service acceptance in handoff | Diagnose provider-specific errors with private data redacted; avoid unrelated dependency/auth rewrites | `YTPlayerUtils`, innertube, provider modules and UI |
| Embedded credential material, P1 security review; not repaired here | Active app Gradle material differs from environment-based comments; values intentionally omitted | Owner/human security review, rotate if appropriate and design reviewed configuration separately; do not copy credentials into migration prompt | `app/build.gradle.kts`, `App.kt` |
| Token/backup/debug-log privacy, P2 audit gap | Ordinary preference stores and optional remote integrations; no whole-app encryption/privacy audit | Audit storage/backup/export/consent and logging before distribution; AutoMix's typed redaction does not cover all app logs | DataStore/account/integration/backup/logging code |
| Licensing/public redistribution, P1 human release gate | GPL/AGPL and conflicting Musixmatch wording/reuse rules; provider/media/asset rights distinct | Obtain revision-specific permission/compliance review, preserve credits and corresponding source; do not remove features automatically | LICENSE, rules, native NOTICE, licensing doc; #18–20 |
| Documentation drift, P3; identified | README privacy/independent-DSP marketing, development missing script, old phase “unmerged/no switch” statuses, legacy queue capacity 4 vs actual 8 | Use this dated state and code. Update old docs only in a separate requested task; preserve historical measurements | README, development guide, phase docs, current worker code |
| Release readiness, P1 distribution gate | Reservations 78–83 exist; Releases API returned none. No current candidate approval implied | Fresh owner-assigned version, notes review, exact artifacts/certificates and manual publish approval after legal/device gates | release docs/scripts/workflows; #3–8 |

## 17. Documentation discrepancies and authority order

Use actual selected revision/code → exact PR/head/API status → revision-specific raw results
and dated narrative → older guides/README. Neither one raw JSON nor the newest prose alone
establishes that all historical runs used identical source/fixtures.

- README's independent-DSP/no-copy wording reflects early legacy scope; actual BitChord
  AGPL native code is now integrated. Native downbeats are heuristic and models are absent.
- README's sweeping local-only/privacy and effortless OTA claims exceed demonstrated
  source behavior. Optional network integrations and browser/installer update boundaries exist.
- `development_guide.md` names a missing `app/generate_proto.sh`, Go prerequisites and old
  FFTW/persistent-keystore setup. Actual Gradle protobuf/native/debug-signing configuration wins.
- `VibraSignature` comment calls it native, but its active method delegates to Kotlin.
- Phase 2.3 licensing text saying PR #18 is unmerged is a historical checkpoint; #18–26
  are merged. Phase 2.3B “no native switch/MusicService unchanged” describes that phase,
  superseded by merged Phase 3.3 opt-in service integration.
- Older Legacy guide says waiting queue 4; current `AnalysisWorkers` uses queue 8.
- Initial benchmark JSON counts (456, 507, 546 per variant) and later (497, 508, 550)
  belong to successive revisions; do not overwrite initial files or add rerun totals.
- Internal ABI flavor names are labels, not actual ABI filters. Verify APKs every time.
- A green scheduled stale-issue run is not a recent Android build. A green draft PR is not
  merged main. Default versionCode 77 is not the latest reserved release code.

The handoff does not edit those older files or claim a comprehensive audit of all third-party
code/assets or every UI route. Gaps are explicit so a new AI does not infer unsupported facts.

## 18. Ordered continuation roadmap and workflow for a new AI

1. **First orientation, read-only:** inspect current main, status and all open PRs; read
   [AGENTS](../AGENTS.md), this snapshot and relevant phase docs. Verify versions/identities,
   authorization, owner priorities and whether PR #27 has since changed/merged. Do not begin
   implementation until the owner supplies a specific task.
2. **Finish active Phase 3.3.4 validation:** review existing #27 rather than duplicate it.
   Obtain real 12/91 runner outcomes, use generated-codec/service regressions and exact keys/
   deadlines. A test failure requires diagnosis, not threshold relaxation. Commit repairs
   to that existing branch while preserving the earlier 79 assertions and old failure evidence.
3. **Real-source acceptance:** authorized Oye Como Va → Eva Maria → El Viejo Joven; verify
   new full requests, native results, planner readiness, arming/mixing/completion, preservation,
   drift and one promotion. Repeat seek/recorder/real-route checks; separate reanalysis from
   replanning and subjective quality. Don't upload commercial audio or private history.
4. **Reliability blockers:** investigate intermittent startup/drift using actual traces;
   do not assume caching fixes clocks. Profile bounded ordinary-listening resource costs,
   external EQ/session and Bluetooth behavior. Preserve fallback and experimental gating.
5. **Only then consider Phase 3.4 under a new owner task:** independent per-deck DSP, bass
   swaps/high-/low-pass/mid/treble techniques and new curves. Keep listener global EQ/focus
   untouched; add actual audio-chain tests, conservative gain/headroom and device listening.
6. **Later scoped work:** phrase/vocal/harmonic evidence, local tempo changes or time alignment,
   resource optimization and broader devices. Do not add speculative models/source separation
   or pitch/time stretching merely to increase accepted transition counts.
7. **Before broader activation/distribution:** human licensing/security/credential review,
   safe settings migration, controlled real-world reliability/musical testing, exact signed
   artifact testing, reviewed release notes and explicit owner publication approval. Legacy
   remains default unless a future task separately authorizes activation.

For each requested change: reproduce → inspect affected source/contract → implement narrowly →
meaningful deterministic regression → both relevant JVM variants/host JNI → app/test builds →
actual ABI/alignment/notices → owner-device execution when unavailable locally → truthful
versioned evidence → one reviewable PR. Check cancellation, source identity, cache versioning,
queue ownership and cleanup before optimizing success rate. Do not merge automatically.

Conventions: Kotlin/Compose/Flow and Hilt follow local style; preserve persisted enum strings
and Room migrations. Native C++17 has pinned vendored hashes, generated controlled sources
and explicit ownership. JNI catches controlled failures, never claims recovery from a crash.
Gradle wrapper/toolchain pins are source of truth. Instrumentation uses generated distributable
media, bounded calls and explicit class selection. Diagnostics use typed fields and redaction,
not arbitrary exception/source strings. Reports must distinguish compiled/executed/owner/CI.
PR descriptions lead with concrete behavior and validation/limits. [AGENTS](../AGENTS.md)
requires owner-named/noreply attribution for new authorized commits; check local identity
before each commit, never rewrite historic authors. User-visible changes need draft notes;
this documentation-only handoff adds no application feature or release-note assignment.
