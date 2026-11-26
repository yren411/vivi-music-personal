# Tinium: compact context for a new AI coding agent

Read this first, then use the [complete handoff](TINIUM_COMPLETE_PROJECT_HANDOFF.md) for
architecture, code paths and historical evidence. The [status snapshot](TINIUM_CURRENT_STATUS.md)
has dated PR/check links. Paste the [starting prompt](TINIUM_NEW_AI_STARTING_PROMPT.md) as the
initial instruction. **Do not begin implementation until the owner supplies a specific task.**

## 1. Identity, source of truth and current state

Tinium is an Android music-player fork of Vivi Music, repository
<https://github.com/yren411/vivi-music-personal>. Application ID is **`com.yren411.tinium`**;
internal Kotlin namespace is **`com.music.vivi`**, Gradle root project `vivimusic`. Preserve
internal names, data, branding and permanent signing. Do not perform a broad package rename.
Development defaults are 6.0.8/code 77, minSdk 26, compile/target SDK 37. These defaults do
not assign a new release: immutable version reservations already extend through code 83.

Observed **2026-11-09 UTC**, default/main commit is
**`76eda458c3ac2dc0efd307d24116bf4c68cd31df`**, merging PR #26 / Phase 3.3.3.
This documentation branch is `codex/tinium-project-handoff`, based on main. Recheck remote
branches, PRs and source before relying on this snapshot.

The sole existing open application PR at inspection is **draft
[PR #27](https://github.com/yren411/vivi-music-personal/pull/27)**, Phase 3.3.4 adaptive analysis
and stable evidence reuse, branch `codex/automix-adaptive-analysis`, head
**`f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2`**. Its code is implemented on that branch,
not merged into main. Do not recreate it or claim it is absent everywhere. Read its
[pinned design/evidence](https://github.com/yren411/vivi-music-personal/blob/f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2/docs/AUTOMIX_ADAPTIVE_ANALYSIS_AND_REUSE.md)
before any adaptive-analysis work. Retained branches from merged PRs do not imply active work.

Native DJ remains experimental and explicitly opt-in. AutoMix defaults off; when enabled,
its engine defaults to Legacy. No current broad production-readiness or acoustic-sync claim
is justified. The immediate engineering priority is validation of existing PR #27 and
real-source/lifecycle behavior, rather than advanced DJ effects. That is a recommendation,
not authorization to implement or merge it.

## 2. Whole-app architecture

There are twelve Gradle modules. `app` consumes eleven supporting modules: `innertube`,
`lyricsProvider`, `canvas`, `applecanvas`, `vivimusiccanvas`, `artistvideo`, `jiosaavn`,
`spotify`, `lastfm`, `kizzy`, `shazamkit`. Inspect `settings.gradle.kts`,
`gradle/libs.versions.toml` and each module build file instead of inferring functionality
from dependencies alone.

`app/src/main/kotlin/com/music/vivi/App.kt` initializes Hilt, preference-driven integrations
and network/player helpers. `di/AppModule.kt` provides application scope, Room and caches;
`di/NetworkModule.kt` provides connectivity state. UI uses Compose, navigation, ViewModels
and Flow; begin at `MainActivity.kt`, `ui/screens/NavigationBuilder.kt` and `ui/player/`.
`utils/DataStore.kt` stores ordinary preferences; `constants/PreferenceKeys.kt` defines
persisted settings. Ordinary DataStore does not establish encryption of account credentials.

`db/MusicDatabase.kt` wraps the Room DAO; `InternalDatabase` is schema **34**, database
`song.db`, with exported schemas in `app/schemas`. Songs, artists, albums, playlists and
mapping tables, formats, lyrics, history/events and recognition records are persisted.
Preserve migrations and transaction semantics; never fix schema problems by deleting data.

`playback/MusicService.kt` is the **normal music playback authority**, a MediaLibraryService
using Media3/ExoPlayer and MediaLibrarySession. It owns current/secondary decks, queue/radio,
source resolution, focus, notifications, AutoMix, effects sessions and lifecycle.
`playback/PlayerConnection.kt` connects UI/control state to the current player.
`MediaLibrarySessionCallback.kt`, `queues/`, `models/PersistQueue.kt` and `models/PersistPlayerState.kt`
are key boundaries. Do not create another normal playback service or independent focus owner.
A separate Wrapped presentation audio service exists; normal queue ownership is still here.

`utils/YTPlayerUtils.kt` resolves playback sources: optional JioSaavn matching and YouTube
player strategies. `innertube/YouTube` supplies browse/search/player requests. Metadata
matches do not prove sample-identical renditions. `playback/DownloadUtil.kt` and
`ExoDownloadService.kt` use Media3 downloads. AppModule creates separate playback and download
caches; complete encoded bytes are different from complete decoded presentation coverage.
File-URI playback exists; a full local MediaStore/folder library scanner was not established.

Implemented UI/network flows include home/search/radio/album/artist browsing, personal library,
likes/playlists/downloads, lyrics/karaoke, visual canvas providers, widgets, sleep timer,
recognition, listening statistics/Wrapped, backup/restore, artist-release notifications,
Spotify playlist/liked-song import via YouTube matching, Last.fm scrobbling, Discord presence
and Listen Together WebSocket rooms. GMS has Cast implementations; FOSS has stubs. Do not
call all of these end-to-end tested: most live-service/account/device behavior has no current
comprehensive evidence. Spotify import is not Spotify audio streaming.

Lyrics ordering/cache is in `lyrics/LyricsHelper.kt` and `LyricsProviderRegistry.kt`.
Providers include Musixmatch, YouLyPlus, Paxsenix, BetterLyrics, LrcLib, Kugou, Unison,
BiniLyrics, YouTubeSubtitle and YouTubeMusic. Translation/AI settings can contact remote
DeepL/Mistral/OpenRouter services. Preserve functionality and notices; do not reopen or
rewrite lyrics during unrelated work. Recognition records permission-controlled microphone
input and sends a fingerprint through the Shazam HTTP module. `VibraSignature` delegates to
Kotlin fingerprinting despite a native-interface comment; selected BitChord JNI is unrelated.

Internal EQ lives in `eq/`; per-player processors are attached by service player construction.
`playback/effects/AudioEffectSessions.kt` coordinates primary/prepared/fading session roles,
OPEN-before-CLOSE replacement and cleanup. `AudioOutputLifecycle.kt` tracks actual AudioTrack
initialization/release. Session assertions do not prove audible Poweramp EQ equivalence.
Never manipulate the listener's global EQ to simulate future per-deck DJ effects.

## 3. Native DJ: exact versions and pipeline

Most classes below are under `app/src/main/kotlin/com/music/vivi/playback/automix/`.
Current main identities:

| Component | Identity |
| --- | --- |
| Native backend | `bitchord-native-dsp` |
| Native version | `fe95bb60-controlled-v1-jni1-evidence1-internal11025` |
| Corrected PCM | `sinc64-blackman-aac-preroll-mono-22050-v8` |
| Planner | `tinium-native-dj-planner-v2-duration-preservation` |
| Executor | `tinium-native-dj-executor-v5-bracketed-media-clocks` |
| Legacy PCM | `nearest-time-mono-22050-v1` |

`NativeAutoMixServiceController` coordinates the experiment through the existing service host.
`NativeQueueBinding`, `NativeRenditionRegistry` and `NativePlaybackSourcePin` bind the real
outgoing/incoming queue selection to authorized source/rendition/timeline identities. Pinning
must survive source preparation checks; changed renditions or obsolete queue generations
cannot execute a stale plan. Avoid copying URL secrets into diagnostics or identity strings.

Main uses beginning/end windows up to 32 seconds and an `ExperimentalNativePlanningSession`.
`AnalysisWorkers` bounds workers/queue and completed evidence; native and legacy have distinct
typed identities and share concurrency budgeting. Main completed immutable evidence can
survive session cancellation, but transient session invalidation cancels pending batches,
and local-file completed reuse is restricted. Thus “every seek always reanalyzes” and “all
seeks already reuse everything” are both inaccurate.

`AndroidWindowDecoder` opens the actual bounded source, selects an audio track, supplies
format-specific compressed support packets, drains MediaCodec and records numeric diagnostics.
`AacAnalysisTailTrim` preserves retained samples' original timestamps, while codec-owned
front delay is not trimmed again. `AacAnalysisWindow` supplies bounded genuine AAC preroll
for nonzero seeks. `OpusAnalysisWindow` supplies preroll and endpoint support. FLAC uses
`BoundedFlacPackets` and `PlatformFlacCodecPolicy` after vendor extractor/decoder defects.
`CorrectedPcmWindowCapture` validates continuity, frame ownership and precise coverage;
`BandlimitedResampler` creates mono Float32 / 22,050 Hz with explicit source origin.
No silence, duplicated samples, gap interpolation or dishonest FULL coverage is allowed.

`BitChordNativeAnalysisEngine` passes bounded canonical PCM to JNI and returns provenance-rich
`MusicalEvidence`: raw tempo/confidence and absolute beat-grid observations plus uncertainty.
`NativePlanningAnalysis` captures local signal/transient observations and comparable active
level evidence. BitChord beat timestamps are relative to the analyzed PCM origin and must
be mapped exactly once to absolute playback coordinates. Analysis outside a cue's supported
region cannot certify local beat alignment.

`NativeAwareAutoMixPlanner` validates both results through native contracts, never the legacy
confidence threshold. It evaluates one-to-one and uncertain octave hypotheses, fixed beat
correspondence, coverage, incoming content and active transients. Executor readiness requires
`TIMING_AND_TRANSIENT_SUPPORTED`; timing-only evidence is insufficient. Rough limits include
3% tempo difference, median 25 ms/P95 40 ms alignment and 70 ms worst/end/predicted drift;
read current constants rather than relaxing them. Overlaps use supported 8/16/32/64 pulses,
bounded to 3–48 seconds. No actual tempo stretching implements hypothetical rate fields.

Planner v2 ranks duration preservation and bounds discarded outgoing content to the smaller
of 5% of duration or eight effective pulses. Example owner-reported success: 163.865 s outgoing,
159.274221 s cue, 3.234207 s overlap, 1.356572 s remaining, approximately 99.17% preserved.
This supersedes an older technically completed transition discarding about 25 seconds.
No reliable phrase, vocal, harmony or meter detector exists. A supported timing plan is not
a promise of a good subjective transition.

`Media3NativeDjPlayback` adapts the service's configured players; `NativeDjTransitionExecutor`
prepares, seeks, arms and starts the incoming deck, applies gains, checks progress/drift,
and promotes exactly once **at completion**. Native and Legacy never own the same transition.
Old deck cleanup cannot release the promoted player. Existing controls/focus remain service-owned.
`NativeDjGain` compares active windows with conservative attenuation approximately 0.708–1,
never aggressive boost or the legacy quiet-tail ratio. Current native `RESTRAINED_DJ` mixing
is gain-based, not a bass swap or frequency-dependent Phase 3.4 effect.

Bracketed position queries associate each player observation with monotonic query intervals.
Raw and common-time offsets are retained; conservative bounds enclose both, so projection
cannot approve an unsafe raw measurement. Limits include **120 ms drift**, **40 ms late
command**, independent progress/stall tracking, bounded preparation/startup and exact cues.
Format/route/source-compatible startup prediction expires after roughly ten seconds; absent
or expired prediction uses explicit zero lead. Never substitute a fixed Samsung delay,
audible calibration tone, shifted musical cue or looser guard. Position and AudioTrack
telemetry do not prove acoustic synchronization. Intermittent `EXCESSIVE_DRIFT` remains open.

`NativeOutputRouteTracker` observes real AudioTrack endpoints/instances, not simply all
available devices. Equivalent observations are nonmaterial; genuine replacement or unresolved
unsafe output state can cancel/recalibrate. Starting screen capture is not proof of physical
route change. Samsung built-in recorder behavior still needs manual validation. A route
change must invalidate unsafe execution calibration, not necessarily musical source evidence.

Failures release native ownership before independent Legacy analysis/execution. Repeated
technical failures can suspend native attempts for the current service session after three
failures; the saved experimental preference is preserved. Legacy default and fallback remain.
Legacy has its own `AutoMixController`, `AutoMixPlanner`, `TrackFeaturesAnalyzer`, tiers and
planning features. Its gentle frequency/rate processing does not mean Native implements it.
Legacy promotion occurs at fade start; tests must wait for ownership to become idle before
asserting only one output. Never weaken that final output assertion.

## 4. Codec history and unresolved source boundaries

Read [AAC boundary coverage](AUTOMIX_AAC_BOUNDARY_COVERAGE.md) and
[real-world decoder reliability](AUTOMIX_REAL_WORLD_DECODER_RELIABILITY.md).
Corrected profiles evolved independently from legacy: bandlimited v2; startup/frame-clock
v3/v4; genuine FLAC packetization v5; AAC retained-tail ownership v6; Opus support v7;
AAC nonzero-seek preroll v8. Do not relabel old evidence under a changed PCM algorithm.

V6 fixed the 1,010-frame first output followed by 1,024-frame AAC output signature by preserving
14 retained tail frames' ownership, without inventing PCM. V7 repaired Opus windows that
previously decoded 2.001–6.9945 s for a 2–7 s request using genuine support packets.
V8 recovered a nonzero AAC request's missing 2.759 ms beginning through preroll:
123.063–155.063 s previously started at 123.065759 s, now starts at 123.063 s.

Remaining owner-reported v8 boundary cases include 0–32 s requested but
0.137006–32 s actual, and an outro ending at 155.062856 s rather than 155.063 s. Both were
fully cached, continuous after the first available sample, with no synthesized PCM.
Generated empty-edit and rounded-duration fixtures reproduce plausible mechanisms; the
private original MP4 metadata was not independently inspected. Do not declare those mechanisms
confirmed in the commercial songs. Main correctly keeps these original requests partial.
Complete cached bytes do not imply presentation samples exist at every catalog timestamp.

Draft PR #27 retries once using a separately requested interval wholly inside clean verified
coverage, aligned inward to millisecond request precision: e.g. 0.138–32 s or an endpoint
155.062 s. It does not rebase audio to zero or certify the old window. A failed/interior-gapped
source cannot authorize adaptation. Minimum supported region remains eight seconds.

## 5. What PR #27 actually adds, and what it does not

Read its code before modifying it. Version `native-regions-v1-verified-inset` changes analysis
strategy/cache identity, not v8 PCM or planner v2/executor v5. Independent evidence regions
retain source/profile/exact interval/origin. In-flight consumer leases coalesce compatible
work; same-source seek/route replanning can retain immutable pending evidence under its
original finite deadline. Queue/source/configuration/fallback/shutdown still cancel unsafe work.
Completed cache is bounded; no raw PCM or persistent analysis disk cache is added.

Optional enrichment uses only already-fully-cached offline reads, after a native plan exists,
with ample playback lead. It runs one optional job, at most three attempts/source, six-second
job deadlines, bounded region/catalog storage, and yields before preparation/mixing. It can
sample three independent regions of a short recording; long recordings remain selective.
There is no new streaming BitChord API, stitched global beat grid, whole-track authoritative
BPM or rescue of every nonrhythmic intro. Conflicting region tempos remain visible.

It separates reanalysis, replanning, rescheduling, cancellation and cache invalidation, adds
bounded reuse/decode counters, and preserves real route/executor safety. Twelve new Android
methods are compiled; phone acceptance is still missing. Do not call Phase 3.3.4 complete.

## 6. Build, validation and safe device workflow

Use JDK 21, wrapper 9.3.1, AGP 9.1.1, Kotlin 2.3.10, KSP 2.3.6, SDK 37,
NDK 27.0.12077973 and Android CMake 3.22.1. Media3 is 1.7.1, Room 2.8.4, Hilt 2.60.1.
Android Studio must support these pins. `local.properties`/environment supplies the SDK;
no Go or missing `generate_proto.sh` step is needed. See complete handoff for host-native,
packaging, sanitizer, Python, credential and troubleshooting commands.

```sh
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest --no-configuration-cache --no-daemon --max-workers=4
cmake -S app/src/main/cpp -B /tmp/tinium-host-jni -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/tinium-host-jni --parallel 4
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest -PautomixNativeHostLibrary=/tmp/tinium-host-jni/libtinium_automix.so --no-configuration-cache --no-daemon --max-workers=4
```

Host `.so` commands above are Linux-specific. Host-JNI tests can skip without that property.
Recorded main checkpoint: **563 GMS + 563 FOSS = 1126**, zero failures/errors/skips, including
17 actual host-JNI methods each. PR #27 records **599+599=1198**, including 19 each.
These are committed prior execution reports, not handoff reruns. This documentation task
only enumerated Gradle tasks and checked documentation; no new app/test build was run.

Owner-reported combined S25 Ultra / Android 17 milestones: 49, 59, 69, then **79** selected
Android tests, zero failures/skips reported. Raw runner XML/independently verified installed
SHA were not supplied. The 79 comprise corrected decoder 4, native 14, planner 6, executor 14,
service 11, real-world decoder 10, reliability 10, AAC boundary 10. PR #27 adds 12 => **91**,
still pending physical execution. Extra standalone diagnostic/listening classes are outside
these acceptance selections and can require opt-in inputs; avoid unfiltered count claims.

Windows safe target command, **only after fetching/checking out PR #27 when testing its code**:

```powershell
git fetch origin
git switch codex/automix-adaptive-analysis
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AdaptiveNativeAnalysisInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Expected targeted count 12, no failures/skips; expected is not a pass claim. Then select all
91 with the pinned PR guide's exact classes. The expanded run includes the prior 79; both
79 and 91 runs are unnecessary duplication unless isolating a failure. Reports are under
`app/build/reports/androidTests/connected/` and `app/build/outputs/androidTest-results/connected/`.
The property installs/updates **`com.yren411.tinium.decodercheck`**, preserving normal Tinium.
Keep the owner's debug key; never uninstall normal Tinium, clear cache/data, change signing
or overwrite an incompatible production installation. Some tests play generated audio.

Both main build workflows and both required PR #27 checks were verified successful against
their respective SHAs; exact URLs are in the snapshot. Conditional duplicate skips are not
failures; nonblocking lint is not clean lint. Green CI is not Android execution or listening.

## 7. Diagnostics, remaining work and guardrails

Settings path is **Settings → Player and audio → Player → AutoMix → AutoMix engine**;
AutoMix must be enabled to reveal the selector. Options are Legacy AutoMix (default) and
Native DJ AutoMix (experimental). Normal playback/queue is the listening interface.

Stats for Nerds can deliberately export a bounded AutoMix report, schema 2/native extension 1,
with salted session source IDs and typed decoder/planner/executor/route metrics. Long-press
the diagnostics area; modern Android saves through MediaStore Downloads. PR #27 adds reuse
counters. No automatic listening/audio upload is required. Other logs/backups are not
automatically equally redacted. Do not infer song names from salted hashes.

Debug in sequence: opted-in/gate/pair → source/rendition pin → exact PCM coverage → real JNI
result → planner admission/rejection → executor preparation/start/progress → queue/seek/route
invalidation → guarded drift → terminal completion and one promotion → subjective listening.
PLAN, MIXING, successful decoding, and COMPLETED are different facts.

Top unfinished issues: PR #27 device acceptance; genuine versus adaptable presentation edges;
intermittent drift with absent/expired startup observation; Samsung screen-recorder lifecycle;
real-song cue/vocal/phrase/harmonic suitability; Bluetooth and audible external EQ; extended
battery/thermal behavior; current remote-provider/account compatibility and security/license
review. Retest the owner-authorized sequence Oye Como Va → Eva Maria → El Viejo Joven,
then seeks and screen recording, preserving unfavorable fallback evidence. Do not claim
these post-PR tests already passed.

BitChord source is pinned to **`fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`**. The selected C++17
JNI path builds four ABIs with 16 KB alignment and source/license assets. Raw confidence is
uncalibrated, not probability or legacy confidence. No native advanced EQ/phrase model exists.
Preserve GPL/AGPL and restrictive module notices. Development permission assumptions are not
public redistribution clearance; human legal review remains necessary. Embedded third-party
credential material exists in the app build configuration: values are intentionally omitted;
human exposure/rotation/configuration review is a separate task. Never paste secrets into chat.

Future Phase 3.4 may add independent per-deck effects, but must preserve global EQ, Poweramp,
focus, source integrity and playback ownership. Do not start it automatically. Follow
`AGENTS.md` for new commit attribution and reviewable PRs; inspect/reproduce/add regressions,
record honest variant/host/device evidence, and never merge, publish, allocate versions,
retarget release tags or enable Native by default without appropriate explicit authorization.
