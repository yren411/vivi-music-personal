# Experimental Native DJ AutoMix — Phase 3.3

Starting source: `f3c96b1adb7607dbf71bb4c41933010fecb47fc0` (merged PR #22).
The BitChord/Orchard DSP remains pinned to `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`;
no native algorithm, JNI, resampler, musical threshold or distribution configuration was changed.

## Scope and current evidence

This phase connects the native pipeline to **MusicService and the normal queue**, behind
an explicit experimental opt-in. It adds no second service, output sink, focus requester,
remote flag, telemetry uploader or model. Legacy AutoMix remains the default.

The earlier owner-run 38 Android decoder/native/planner/executor passes are historical
prerequisite evidence. They do not validate this new service integration. New Android
service tests bind the actual service, use its configured players and generated local files,
and execute real corrected decoding/JNI/planning/execution. Compilation and execution are
reported separately in [the evidence directory](benchmarks/phase33-native-automix/README.md).
No real-song, acoustic, Bluetooth, Poweramp or phone battery result is claimed here.

## Architecture and ownership

```mermaid
flowchart LR
    P[Explicit engine preference] --> G[MusicService native gate]
    Q[Actual current and next queue items] --> R[Authorized resolved rendition registry]
    R --> W[Bounded corrected PCM jobs]
    W --> N[Existing BitChord JNI / internal 11025 Hz]
    N --> S[Signal observations and compact active RMS]
    S --> A[Existing native planner]
    A --> C[NativeAutoMixServiceController]
    G --> C
    C --> E[Existing v3 executor and Media3 adapter]
    E --> D[Configured service players / existing output sessions]
    E --> H[Atomic queue and MediaSession promotion]
    C --> F[Release native ownership then independently enable legacy]
```

`NativeAutoMixServiceController` is a main-thread, nonblocking pair coordinator. It uses
`ExperimentalNativePlanningSession`, not a second analyzer/planner implementation.
`NativeAutoMixServiceHost` is the small service boundary for queue/source snapshots,
legacy enablement, execution, control forwarding and status publication. Pure JVM tests
exercise this boundary; Android tests additionally bind `MusicService` itself.

Native selection suspends legacy scheduling before starting a native batch. A legacy
preparation is canceled through its existing controller. Native and legacy never intentionally
hold gain ownership concurrently. No native results are converted into `AutoMixPlan` or
passed through `LegacyPlanningFeatures.require`. On native rejection, the batch closes,
standby/executor ownership is released, and the existing legacy controller can independently
analyze through `nearest-time-mono-22050-v1`. It does not receive native evidence or cues. Legacy fallback retains its existing gain policy;
the experimental quiet-tail safeguards apply only while the native executor owns playback.

`Media3NativeDjPlayback` still defaults to isolated single-item admission. Its explicit
service queue option allows a caller-owned full outgoing playlist with pause-at-end and
exclusive ownership. The executor's plan/readiness/drift/rate/cue guards are unchanged.

## Settings, migration and rollback

Settings → Player and audio → Player → AutoMix → **AutoMix engine**:

- **Legacy AutoMix (default)**.
- **Native DJ AutoMix (experimental)** — attempts beat-synchronized transitions and falls
  back when sources, timing or musical evidence are unsuitable.

The new key is `intelligentAutoMixEngineMode`, with stable values `LEGACY` and
`NATIVE_DJ_EXPERIMENTAL`. Absent/unknown values read as legacy. Existing enablement,
performance and Crossfade keys are unchanged. AutoMix itself remains off for users who
have not enabled it. Crossfade mutual exclusion and saved Crossfade preferences are preserved.
The mode selector is visible only while AutoMix is enabled.

Switching modes invalidates pending work and cancels an active native attempt through the
v3 recovery/cleanup path. A healthy current outgoing deck continues at the current master/focus
volume; a completed/promoted incoming primary is never released by old callbacks. Rollback
can be audible, particularly late in a fade; seamless emergency reversal is not promised.
Select Legacy or turn AutoMix off to stop experimentation without reinstalling or erasing data.
Three recorded technical failures suspend further native attempts for the current service
session. The saved selection is not rewritten. Switching to Legacy and back explicitly resets
that safety suspension. Musical unsuitability alone does not trigger the technical-failure limit.

## Two-track planning, coverage and budgets

Analysis starts as soon as the actual current/next pair and its resolved sources are known.
Only that pair is analyzed. Outgoing uses up to the final **32 seconds**; incoming uses up
to its first **32 seconds**. These are bounded candidate regions, not asserted musical outros
or phrases. No full-song PCM is retained. Intro preservation, transient corroboration, beat
alignment, octave ambiguity and all Phase 3.1 gates remain intact.

The original batch deadline is bounded by remaining track time, a 20-second ceiling, and
three times the observed analysis cost (initial estimate two seconds, minimum budget four
seconds). There is no indefinite retry or nested future wait. A caller snapshot updates the
planner's earliest feasible outgoing time while analysis finishes, without decoding again.
The plan reserves two seconds for preparation. Actual standby preparation begins only within
4–10 seconds of the cue, using observed analysis cost as an additional conservative margin.
The actual READY/seek deadline and unchanged executor admission still decide feasibility;
these scheduling estimates are not measured network guarantees.

A bounded rendition registry rejects late resolver publications from an old mode/quality
epoch. Active-pair validity also includes both MediaItems' URI, cache key and MIME, even
when recording IDs remain unchanged; unrelated future queue additions do not invalidate the pair.
Complete downloaded/cache sources and existing authorized next URLs are reused
before any new network resolution, including offline listening.

The service path requests decoder-reported track durations for planning geometry, rather
than treating rounded catalog seconds as exact. Beat/sample coordinates are never shifted
to reconcile duration discrepancies. The renderer must still agree with the resulting source
duration within the existing 1ms allowance. Missing or inconsistent data causes safe fallback.
Full measured source coverage and valid corrected sample origins remain mandatory; repeated,
padded, discontinuous or unknown PCM is not relabeled trustworthy.

Legacy and native keep separate typed feature caches (64 entries each, no persistent PCM).
Existing backend, engine and preprocessing keys remain distinct. Native session keys also
include the signal-observation and active-level method versions. Mutable local files are not
cached as immutable. Completed compatible immutable results remain reusable after cancellation.

Each typed worker pool retains its existing bounded queue (8) and mode size (1/2/4).
A shared `AnalysisConcurrencyBudget` limits actual service work across both pools to the selected
**1/2/4** slots, including cancellation unwinding during fallback/mode changes. Budget waits are
interruptible on workers, never the main/audio thread. Planning is queued only after analyses
finish. There are no detached native threads or additional unbounded schedulers.

## Real sources and rendition provenance

The opt-in route records the actual authorized URI and known format/length when the existing
resolver opens a rendition. Upcoming resolution reuses the immediate-next prefetcher, with the
existing 30-second retry throttle; it does not resolve every future queue item. Signed URLs
remain in memory only. Incoming playback pins the analyzed authorized source and cache key;
it does not silently re-resolve to a different encoding at the cue. Subsequent songs in the
promoted playlist return to the normal resolver. The temporary current-item pin is also
released on successful promotion (or standby disposal), using a thread-visible source holder.
Later opens can therefore refresh an expired authorized URL through the normal resolver;
the analyzed rendition remains pinned throughout transition ownership. Existing cache authorization, byte/window
limits, cancellation and network timeout safeguards are retained.

Only fully cached, known-length data can establish a cached rendition without a URL. Unknown
incoming duration/source, a changed or expired upcoming URL, quality changes, mutable-file
length/mtime changes, DRM/live content, gapless album pairs, repeat-one, nonunity speed/pitch,
silence skipping, casting/shared listening or conflicting deck ownership prevent native execution.
Native mode permits authorized local file URIs through the existing DefaultDataSource and
standard extractors; it does not add a public local-library importer or bypass permissions.
A file's length/mtime is a bounded mutation check, not a cryptographic immutability guarantee.

If native mode is selected while a remote song is already buffered, its actual source may not
be observed until the next authorized resolver open. The system waits/falls back instead of
inventing a rendition identity. For controlled comparison, select the mode **before** starting
the pair. Unsupported providers/renditions may legitimately remain legacy.

AAC priming, FLAC sample origins and PCM coverage use the existing corrected v3 decoder.
There is no new timestamp offset or assumption that differently encoded renditions are sample
identical. Real streaming/container differences and network failure rates remain device work.

## Execution, startup timing, gains and queue promotion

Only `TIMING_AND_TRANSIENT_SUPPORTED` plans execute. Native confidence is not a probability;
unknown vocals/harmony/phrases/meter and octave hypotheses remain visible. No threshold is
lowered and no playback rate is changed to manufacture a supported plan.

The service measures an actual outgoing play command and first observable position progress.
It supplies that observation only with matching source/generation and the v3 validity bounds.
Format-aware prediction still requires known matching prepared formats. Route changes,
seeking, mode/quality changes and relevant player changes discard observations. A coarse or
late observation supplies **zero lead**, never a hardcoded Samsung delay.

The v3 observation expires after ten seconds. Consequently, a transition much later in a long
song will commonly have no valid startup prediction. Independent output startup can then exceed
the unchanged **120ms** drift limit and fall back on some devices. This is an important remaining
limitation, not a reason to reuse stale calibration or widen the limit. Phase 3.4 may need a
separately validated readiness/synchronization improvement. Media3 telemetry is not acoustic sync.

Prepared incoming starts paused at the exact approved cue. Fade progress uses the original
outgoing media anchor; early preparation stays muted. The existing **40ms** command-target
lateness and **120ms** measured drift bounds remain. Overlap duration is not shortened to a
legacy fade. Common progress and stall detection remain independent of clock polling phase.
Pause/resume forwarding handles the fact that normal user controls change the primary's
playWhenReady before the service callback. Reentrancy is guarded while resuming both decks.

Compact active-RMS observations are measured from the same corrected windows and retained
without PCM. The unchanged `NativeDjGain` validates source/preprocessing/overlap comparability,
rejects quiet/unrelated references to unity, caps attenuation at 3dB and never boosts. The
restrained smooth envelope has the existing correlated-source sum guard. Master, mute and
focus gain are applied once; valid content attenuation tapers to unity at completion. This
is not LUFS/perceived-loudness normalization or post-EQ clipping measurement. No new bass,
filter, time-stretch or pitch effect was added.

Promotion revalidates the authoritative pair, inserts the original queue prefix/suffix around
the already-playing incoming item, and preserves its current position. Repeat/shuffle order,
MediaSession, player flow, metadata, sleep timer, notification and widget ownership move once.
The old primary listener is detached and its output is released through the existing service
hook. Pause-at-end prevents outgoing EOS from independently advancing the queue. The service
forwards an explicit END_OF_MEDIA_ITEM notification to the adapter/executor; after failed
end-boundary recovery it releases native ownership before advancing/resuming the existing
queue once. This prevents a pause-at-end failure from permanently parking playback. Completion
is recorded before the next pair invalidates old analysis; no seek-to-zero occurs at promotion.

## Focus, routes and equalizers

The service's configured player/renderers/internal EQ and existing `AudioEffectSessions` /
`AudioOutputLifecycle` remain authoritative. Native output hooks are NONE because the service
already attaches observers before preparation. Promotion/release reuse those hooks; no duplicate
observer/effect chain, forced session ID or incoming focus requester is installed. The service
continues owning ducking, pause/resume, becoming-noisy behavior and preferred output routing.

The new Android tests observe actual session ownership and inject the real service focus
callback and an unexpected end-of-item callback. Injection verifies forwarding/cleanup,
**not OS focus arbitration or an actual truncated-file decoder EOS**. Session OPEN/CLOSE
correctness does not prove Poweramp audibly processes both decks. Speaker, wired and Bluetooth
listening with internal EQ and external EQ on/off must be evaluated separately. Test moderate
EQ changes on the owner device; do not require a Poweramp reinstall/configuration change as a fix.

## Diagnostics and local listening

The existing Appearance → Advanced → Stats for Nerds overlay shows native analysis/planning,
executor stage, selected cues/overlap or explicit native-to-legacy fallback. The existing timeline
marker uses the real native cue and current/next identities. Invalidated plans disappear; native
confidence is never converted into a legacy plan to draw a marker.

Long-press Stats for Nerds to export to Downloads using the existing deliberate export workflow.
The human-readable schema 2 report has an additive **Native DJ extension schema 1**. It includes
selected engine, session-salted track/rendition hashes, generation, backend/preprocessing,
planner/executor versions, both analyzed BPMs and raw native scores (including rejected pairs),
selected tempo hypotheses, uncertainty/readiness, residuals,
plan timing, analysis/preparation time, command lead, sampled drift/progress/gains, terminal
status and controlled fallback reasons. Native summary counters count attempts once, not every
heartbeat. History remains 30 attempts × 128 events; each native event retains only its latest
player sample, not another nested history. No PCM, titles, private paths, URLs, headers or
credentials are exported. No automatic upload or background disk recording is introduced.
Uncollected memory, acoustic, energy and battery metrics remain absent.

For basic listening after installing an appropriate local experimental build:

1. Stop playback in the other Tinium installation. Use a comfortable media volume.
2. Enable AutoMix, choose Native DJ AutoMix (experimental), and optionally enable Stats for
   Nerds and the existing transition marker in Appearance.
3. Start a pair you are authorized to play through Tinium's normal sources/queue. Let the
   current song play; seeking invalidates timing and can intentionally cause fallback.
4. Observe analyzing → plan → preparing/ready → STARTING/MIXING → completion, or an explicit
   rejection/fallback. Read planned cues and overlap; compare the actual player-clock report.
5. Long-press Stats for Nerds to save a redacted report to Downloads. Share that text and a
   voluntary description of what sounded wrong; never upload the audio or unrestricted logcat.
6. Select Legacy AutoMix to compare the same pair, route, volume and EQ settings. Start the
   pair again; preserve similar playback conditions. A longer overlap alone is not better.
7. Return to Legacy or disable AutoMix at any point. No reinstall/data deletion is needed.

A short optional rubric (good / mixed / poor / not assessed): beat alignment, incoming audibility,
outgoing continuity, volume smoothness, vocal clashes, cue/content preservation, overall flow,
stutter/buffering and handoff continuity. Note whether legacy fallback sounded preferable.
Negative reports are useful. Nobody must rate every transition; no ratings/history are uploaded.

Private coverage matrix: same/similar/different BPM; phase differences; half/double-time; vocals;
long intros/outros; quiet→loud and loud→quiet; strong peaks; compressed and dynamic recordings;
electronic/pop/live rock/syncopation; ambient/no percussion; variable tempo; short/long tracks;
FLAC/AAC and mixed renditions; cached/streamed; speaker/wired/Bluetooth; internal EQ and
Poweramp on/off; pause/resume; skip/shuffle; background/screen-off. Use only permitted material,
retain unfavorable cases locally, and distinguish measured timing from subjective ratings.

Classify issues specifically: BPM/octave/phase/grid; cue/content cutoff; vocal/phrase conflict;
overlap suitability; incoming quietness/combined loudness/handoff gain; startup/drift/stall;
source/rendition/cache; session/focus/route; queue promotion/stale work; unknown cause.

## Windows / S25 Ultra commands

Keep the working debug key, for example the already configured
`C:\Users\hurta\Tinium-Signing\tinium-debug.jks`. Do not paste passwords into shared logs.
The existing `-PautomixDecoderTests=true` selects **com.yren411.tinium.decodercheck** for debug
only. It does not overwrite normal com.yren411.tinium or share its preferences/download cache.
An existing isolated installation still needs the same certificate. Resolve signing conflicts
without uninstalling the normal app or erasing data. Select only the intended connected phone.

```powershell
git fetch origin
git switch codex/automix-native-dj-service
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

New selection: **11 tests, expected 11 executed, zero failures/skips**. It plays generated local
audio using the actual service. Allow several minutes; the repeated-attempt test has a bounded
150-second wait. At least one long native mix must complete; a later unsupported attempt may
legitimately fall back while queue continuity and cleanup remain required. No online provider
or copyrighted fixture is needed. Preferences are snapshotted/restored within the isolated app.

Previous **38** regression tests (all four original classes; none weakened):

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

To build/install the local isolated app for ordinary listening (not a signed distribution release):

```powershell
.\gradlew.bat :app:installArm64GmsDebug "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --stacktrace
```

Open the isolated Tinium app and configure authorized sources normally. It has separate data;
this task does not copy accounts, playlists or downloaded files from your normal installation.
No special instrumentation harness is needed for basic queue listening after installation.

On test failure retain actual counts and connected HTML/XML in
`app/build/reports/androidTests/connected/` and `app/build/outputs/androidTest-results/connected/`.
Tests emit `NATIVE_SERVICE_ANDROID` and a sanitized report under tag `TiniumNativeDj`.
For a normal listening failure, export Stats for Nerds and report mode, route, EQ state and
what was heard. Optional controlled log retrieval:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb logcat -d -s TiniumNativeDj:I '*:S' > .\phase33-service-diagnostics.txt
```

This command selects the experiment's tag, not unrestricted logcat. Logcat entries can be
truncated. Each service test also overwrites one bounded, sanitized private text report.
To retrieve the last test's report after the retained-APK command above:

```powershell
$report = @(& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase33-service-last.txt)
if ($LASTEXITCODE -ne 0 -or -not ($report -match '^Tinium AutoMix diagnostic report')) {
    $report
    throw 'No valid service diagnostic report was retrieved.'
}
$report | Set-Content -Encoding utf8 .\phase33-service-last.txt
```

Subsequent tests overwrite that file. To retain a particular failure, rerun the same command
with the class selector suffixed `#actualFailingMethodName`, then retrieve the report.
The app's deliberate Stats export remains the supported normal-listening report; private
file extraction is optional and does not access the normal Tinium installation.

## Verification, limitations and next phase

See the evidence directory for actual commands/counts and host measurements. Native C++ is
unchanged; host-JNI tests execute real DSP. Android service tests must actually run before a
service-connected runtime pass is claimed. The cloud has no configured Android runtime; ADB
could not enumerate devices because its home directory was not writable, and no KVM/system
image was provisioned. Compilation is not phone validation. No legal real-song corpus was
available in this environment; no ratings, acoustic results or phone performance are fabricated.

Phase 3.3 exposes experimental usability, not production readiness. Broader activation requires
owner-run new 11 + prior 38 Android passes on the candidate integration, real authorized-song
comparison, repeated queue/controls/background listening, supported-route focus/session and
Poweramp checks, bounded resource observations and correction of common failures. Review
startup synchronization without stale calibration, cue/phrase/vocal suitability and metrical
ambiguity as targeted Phase 3.4 work where real reports justify it. Do not broaden rollout or
weaken timing/coverage gates to raise success counts.

GPL/AGPL notices, source provenance and source-distribution obligations remain applicable.
The owner's Musixmatch permission assumption supports this development task; it is not an
independent public redistribution clearance. No lyrics changes, new BitChord import, public
APK, release/version allocation, distribution signing or automatic merge is performed.

## Device test hangs: bounded harness and progress capture

The owner reported two Phase 3.3 device invocations with audio but no completed test after
approximately seven and three minutes. No matching logcat messages were available, so neither
the stuck method nor the cause of the crackles is established. This is **not** a device pass.

The original service harness could block without a deadline in `runOnMainSync()` and
`waitForIdleSync()`, including cleanup after an already-failed playback assertion. A global
idle wait is inappropriate for a service that schedules work. The follow-up replaces those
calls with test-only bounded Handler/FutureTask calls (7s), bounds preference operations (10s),
and adds a 240s whole-test JUnit limit for the longest 150s progression case plus setup/cleanup.
Existing playback assertions and their 20/50/150s limits are unchanged. Queued timed-out
mutations are removed/canceled; already-running Android platform code is not interrupted or
claimed recoverable. Cleanup attempts remaining steps and preserves the original failure.
A bounded main-thread barrier replaces global idleness; it is not proof of service destruction.

Test/stage/progress markers use `TiniumNativeDj`. Four private files are bounded to the latest
stage, first body failure, latest filtered thread snapshot and latest sanitized AutoMix report:
`phase33-service-progress.txt`, `phase33-service-failure.txt`, `phase33-service-threads.txt`,
`phase33-service-last.txt`. These contain no audio, titles, URLs, credentials or media paths.
Thread snapshots include only the main, ExoPlayer and AutoMix threads (at most 8 × 18 frames).
Progress writes occur on the test thread, not playback callbacks or normal listening.

After stopping a stuck Gradle invocation with Ctrl+C, stop only the isolated test process:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb shell am force-stop com.yren411.tinium.decodercheck
```

This does not uninstall either app or erase its data. Update the PR branch and first run the
short single legacy-default test, which intentionally starts no audio:

```powershell
git fetch origin
git switch codex/automix-native-dj-service
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest#legacyDefaultDoesNotConstructNativePipeline" --no-configuration-cache --no-daemon --stacktrace
```

Expected: one executed test, zero failures/skips. If it fails, retain the original failure
and suppressed cleanup errors. If the runner itself remains stuck, a second terminal can
read these files while the process is alive, without relying on logcat:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase33-service-progress.txt
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase33-service-failure.txt
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase33-service-threads.txt
```

A failure/threads file may not exist before a failure; compare the recorded test, phase and
run-start timestamp to the current progress file. A missing progress file can indicate that
execution never entered the harness. A blocked Android main thread may also prevent the
framework from finishing the instrumentation invocation despite bounded test waits; stopping
the isolated process is then appropriate after retaining diagnostics. No arbitrary native or
platform deadlock is claimed fixed by a timeout. After the smoke test passes, run the codec
case alone by replacing the selector suffix with
`#actualAacAndFlacSourcesUseThePinnedServiceNativePipeline`, then return to all eleven.
