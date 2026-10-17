# Experimental native DJ transition execution — Phase 3.2

Base: merged PR #21, `a87611de2188436b7cc951afe4999199210b9d40`.
This phase adds an **explicitly invoked experimental executor**, not production activation.
`MusicService`, its legacy planner/controller, PCM selection, gains, filters, equalizers,
settings, notifications and queue remain unchanged. No release version is allocated.

**Device validation is currently blocked.** The owner supplied three S25 Ultra failure
reports: all reached `FALLBACK / EXCESSIVE_DRIFT` shortly after incoming `play()`. A passing
cloud build does not supersede those results. Do not treat Phase 3.2 as device-validated or
activate/merge it on that basis. See the startup investigation below.

## Implemented path

```mermaid
flowchart LR
    A[Authorized local source] --> B[Existing corrected decoder v3]
    B --> C[Existing BitChord JNI / internal 11025 Hz]
    C --> D[Phase 3.1 native planner]
    B --> E[Optional active-block level observations]
    D --> F[NativeDjTransitionExecutor admission]
    E --> F
    F --> G[Media3NativeDjPlayback]
    G --> H[Caller-owned outgoing ExoPlayer]
    G --> I[Paused prepared incoming ExoPlayer]
    H --> J[Existing Android audio outputs / effect sessions]
    I --> J
```

The native source remains pinned to BitChord `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`.
No DSP/JNI/native build source was copied or changed. The actual existing backend is reused.

### Source inventory

- `NativeDjExecutionContracts.kt`: deck/queue/host contracts, states, controlled reasons,
  config and privacy-safe reports. Positions are absolute **track-relative microseconds**.
- `NativeDjTransitionExecutor.kt`: serialized single-attempt coordinator, version
  `tinium-native-dj-executor-v1`. No threads, timers, analysis or raw PCM cache.
- `NativeDjGain.kt`: comparable active-block RMS measurements, bounded content adjustment,
  four curve implementations and correlated-signal sum guard.
- `Media3NativeDjPlayback.kt`: actual ExoPlayer adapter, exclusive ownership leases,
  exact-cue preparation, bounded Handler driver, atomic promotion callback and lifecycle hooks.
- `NativeDjExecutionFixtures.kt`: virtual media clocks used only in tests.
- New JVM tests: execution state machine, gain/PCM corpus, real-JNI-to-virtual execution,
  signal/performance reports. Existing tests/assertions remain unchanged.
- `NativeDjExecutionInstrumentedTest.kt`: 14 generated-media Android tests.
- `NativeDjLocalListeningInstrumentedTest.kt`: separately selected optional private-file evaluation.
- `NativeDjStartupClockInstrumentedTest.kt`: separately selected, bounded device diagnostic;
  records two seconds of muted incoming playback without approving a transition.

## Production and ownership boundary

Neither the service nor normal AutoMix constructs these classes. There is no public setting,
background native analysis, hidden service or conversion to a legacy `AutoMixPlan`.
The existing `PHASE32_NATIVE_PLAN_EXECUTOR` requirement is checked and satisfied by a real
executor; the planner's gate was not deleted or renamed.

The Media3 adapter receives caller-owned/configured players and promotion/release functions.
It does not resolve streaming URLs, construct a second music service or replace the audio sink.
It requires an isolated single-item outgoing deck and an explicit exclusive-control assertion.
The caller must exclude legacy fade timers. Admission also refuses a player-volume baseline
that differs from current user volume times focus gain, preventing a stale prior fade from
jumping to unity on ownership acquisition. A synchronized ownership registry prevents two
experimental adapters taking the same outgoing/incoming player, including across loopers.
A factory must return a fresh incoming player on the outgoing player's application looper.
The instrumentation harness supplies two real ExoPlayers and an isolated generation/queue,
without touching the user's normal queue or database.

A future service integration can supply its existing media-source/renderers/equalizer factory,
release hooks, authoritative queue snapshot and atomic handoff. **That service wiring is not
installed here.** The controlled isolated path is the current executable integration point.

## Admission and provenance

Admission checks planner version, native backend/profile, corrected preprocessing
`sinc64-blackman-initial-preroll-mono-22050-v3`, required capability, rate 1.0, generation,
recording/rendition identities, durations, seekability, cue regions, analyzed intervals,
remaining track time, bounded overlap and timing residuals. Pulse counts must be 8/16/32/64
intervals; overlap length, rank summary and predicted rate-1.0 drift must be consistent with
the tempo/alignment fields. A tampered short overlap cannot retain a longer beat grid. Duration
consistency uses the native evidence contract's 2% allowance (at least 70ms); it never changes
a selected cue/duration or relaxes the separate 40/70ms musical residual gates. It rejects nonfinite values and
arithmetic overflow. Native confidence is retained as uncalibrated evidence, never reinterpreted
as the legacy 0.65 confidence scale. Only `TIMING_AND_TRANSIENT_SUPPORTED` proposals are admitted;
`TIMING_ONLY_SIGNAL_UNVERIFIED` receives an explicit rejection.

The producer is the existing Phase 3.1 planner, which validates actual coverage, beats and PCM
provenance. The plan is an internal evidence summary, not a cryptographically authenticated
proof or arbitrary external input format. The executor revalidates that summary and current
playback identity; it does not independently rediscover the beat grid. No partial PCM is relabeled
full coverage. There is no new analysis cache: existing backend/preprocessing/version identity,
worker modes 1/2/4, waiting queue 8 and feature cache 64 are unchanged.

Renderer duration comparison allows at most 1 ms for Media3's millisecond API. Broader catalog,
container or rendition discrepancies are rejected, not silently reconciled. Decoder/renderer
duration disagreement on real sources remains a device-validation concern.

## State machine and scheduling

`IDLE -> VALIDATING -> PREPARING -> ARMED -> MIXING -> COMPLETING -> COMPLETED`.
Pause remembers its previous state. Terminal alternatives are `CANCELED`, `FALLBACK`, `FAILED`
and `RECOVERED`. One executor handles one attempt; terminal ticks cannot promote/release again.

1. Validate the real outgoing player, then acquire exclusive ownership.
2. Create one paused incoming deck, set `SeekParameters.EXACT`, seek to the selected cue,
   disable repeat/shuffle for this isolated item and prepare asynchronously.
3. Require actual READY, a known compatible duration, seekability, paused state and a position
   within 15 ms of the cue before arming. Millisecond seek quantization is explicit.
4. Dispatch incoming `play()` when the outgoing media position reaches the planned anchor.
   Polling interval is 10 ms (250 ms while paused, immediate rescheduling on resume); a command more than 40 ms late is rejected as `START_MISSED`.
5. Progress the fade using outgoing **media position**, not wall-clock elapsed time. Both player
   positions must progress. Watch for buffering, unexpected pause/EOS, rate changes and jumps.
6. Revalidate generation immediately before the compare-and-promote callback. Promote once,
   release outgoing, clear temporary end-of-item policy, and leave incoming playing at unity
   relative gain. Incoming is never sought to zero during promotion.

Preparation timeout is 10 seconds; preparation must also start with the plan's assumed lead time.
A prepared-but-late deck is not called synchronized. Incoming startup has a 1.5-second ceiling;
observed running media-clock separation must remain within 120 ms. A READY player pair whose
media clocks stop progressing for two seconds returns `PLAYBACK_STALLED`, rather than leaving
a fade/ownership lease alive indefinitely. No speed correction, repeated
seek, time stretching, detached thread or retry loop exists. Future DSP precision must be added
as an explicit capability rather than inferred from a good BPM estimate.

### What timing measurements mean

Reports distinguish command dispatch outgoing position from the first advancing incoming
media-clock estimate. Estimated start offset is `(outPos - outCue) - (inPos - inCue)` at first
observed incoming progression. Subsequent separation is monitored with the same reference.
Position progress common to both decks accumulates `concurrentProgressUs`. This is a sampled
player-level measurement, not acoustic overlap or a hardware presentation timestamp.

Maximum tick gap includes the slower paused polling cadence; it is not an audio-render jitter measurement.
The report includes maximum tick gap, preparation elapsed monotonic time, drift, actual command
position and completion/promotions. The 10 ms driver can finish up to one update late under normal
scheduling; a paused process or busy looper can do worse and is observable. Callback/volume calls
are **not sample accurate**. Independent AudioTrack output latency, Bluetooth buffering and
route changes remain important. No acoustic capture was performed in the cloud.

## Gain architecture and quiet-tail correction

Confirmed legacy code: `AutoMixPlanner.plan` computes `quiet=min(localOut,localIn)` and uses
`quiet/localOut` and `quiet/localIn`. A 0.005/0.2 RMS comparison yields an incoming content factor
near 0.025 before envelope/headroom. That is a commanded coefficient, not measured audible output.
The legacy calculation remains unchanged.

Alternatives considered: raw quiet-window ratios repeat the defect; permanent gain changes risk
handoff jumps; unbounded RMS boosts amplify silence/noise. This executor instead uses optional,
source/profile/interval-matched measurements and conservative transition-only attenuation.

`NativeDjLevel.measure` uses 100 ms mono blocks, excludes blocks below max(0.001, 0.1 times the
largest block RMS), and returns the median retained RMS, retained-block fraction and peak.
Version: `active-rms100ms-median-relative20db-v1`. It checks corrected PCM, real coverage and
cancellation. It is **not integrated LUFS or perceived loudness**.

Both observations must cover the selected overlap, have usable active fraction >=25%, active
RMS >=0.01 and finite coherent values. Otherwise content factors are **1.0**. This prevents a
quiet tail, unrelated window, silence or invalid measurement aggressively reducing the incoming
song. Comparable active measurements may attenuate the louder side by **at most 3 dB**, never
boost either track. The content adjustment ramps in/out during the transition; normal playback
returns to unity continuously. No normalization persists after promotion.

```
player.volume = currentUserVolume * currentFocusGain * relativeDeckGain
relativeDeckGain = guardedFadeWeight * [1 - (1 - contentFactor) * sin(pi*p)^2]
```

The Android system volume is never included or changed. PCM processor gain is not also changed,
so this path does not double-apply attenuation. Current user volume is consulted at every gain
update and rollback; a new user value is not overwritten by an old captured volume. After the
lease ends, normal caller volume ownership resumes and old adapter refreshes do nothing.

## Curves and output bounds

Let `s(x)=x*x*(3-2*x)`, `p` be media progress clamped to [0,1]. Raw weights:

| Curve | Incoming | Outgoing |
| --- | --- | --- |
| Linear | p | 1-p |
| Equal power | sin(pi*p/2) | cos(pi*p/2) |
| Smooth S | s(p) | 1-s(p) |
| Restrained DJ (default) | piecewise below | 1-incoming |

Default incoming: `0.42*s(3p)` in the first third; `0.42+0.16*s(3p-1)` in the middle;
`0.58+0.42*s(3p-2)` in the last third. Values and slopes join continuously. It introduces
incoming material sooner than a global smoothstep, holds both near the middle, and completes
without an endpoint jump. It is tunable internally, not claimed perceptually optimal.

All curves divide both raw weights by `max(1, out+in)` before content correction. Thus the
worst-case correlated **normalized** source sum is <=1 without a compressor/limiter or permanent
volume reduction. The equal-power option is therefore correlation-guarded, not an unconditional
constant-power promise. Uncorrelated sources can exhibit an energy dip, which the signal report
retains. Source clipping and subsequent internal/external EQ boosts are outside this mathematical
bound. No post-EQ/Bluetooth clipping or loudness guarantee is claimed.

The current implementation applies player volume automation only. It does not add bass/filter
sweeps or redesign DSP. Musical timing comes from the actual native two-track plan, rather than
extending an end-of-song fade. This phase makes no listening-quality claim.

## Interruptions, handoff and rollback

- Pause/focus loss: caller forwards pause to both decks. Positions and gains freeze; paused time
  does not consume preparation/start timeouts or overlap. Resume revalidates queue/positions.
- Seek/skip/repeat/shuffle/source changes: invalidate before mutation; per-tick generation checks
  also reject stale ownership. No attempt to rebase a running mix silently.
- Noisy-device/route change: forward invalidation; do not assume a previously measured output
  latency remains valid. No device routing override is installed.
- Incoming buffering/error/failed start: cancel experimental playback, release incoming and
  restore current outgoing gain, preserving its play/pause state and position.
- Outgoing failure/EOS: only an already-playing healthy incoming deck with meaningful commanded
  gain may be promoted as **RECOVERED**, with the reason retained. It is not an aligned success.
  EOS exactly at the planned endpoint can complete normally if incoming progression is valid.
- Promotion failure or generation race: no queue advance; release incoming and preserve outgoing.
- Shutdown/close: remove Handler callback, cancel unfinished attempt, restore end policy/lease.
  Successfully promoted incoming remains caller-owned. Cleanup errors are classified, not hidden.

Emergency rollback restores a continuing deck immediately; this is not a sample-smooth recovery
ramp. Pause/seek/route/error recovery may be audible. No recovery launches a simultaneous legacy
fade, fabricates legacy evidence, restarts a track or retries indefinitely. A caller may later
request an independent safe legacy plan after this lease is released.

## Audio focus, sessions and equalizers

The adapter does not make a second focus request. Its caller owns the existing focus policy and
forwards focus gain/loss/ducking plus user controls. The optional listening harness does not automatically arbitrate OS focus or observe every
Bluetooth route selection. Stop/restart it for each output route; its injected control tests
verify forwarding policy, not automatic system-event integration. The isolated generated playback tests use
focus-disabled Media3 players; they test forwarding policies, **not Android focus arbitration**.
For owner listening, pause other music first; there is no foreground production service for this
harness. Real service/focus integration is a future separately validated step.

Caller factories must use a fixed source timeline with silence skipping disabled (the isolated
test factory does), no live/DRM source and unity pitch/speed. Future service admission must retain
its existing incompatibility checks; this experimental adapter is not a generic replacement for
all service playback modes. Factories should disable offload for concurrent volume automation (the test factory reuses
`Player.setOffloadEnabled(false)`), retain their intended renderers/internal EQ, and supply actual
release/promotion hooks. No output-session ID is forced or equalizer reset. `NativeDjAudioOutputs`
reuses existing `AudioEffectSessions`/`AudioOutputLifecycle` with real AudioTrack callbacks and
public OPEN/CLOSE broadcasts, including shared-ID reference ownership. Attach **before prepare**;
READY alone never fabricates AudioTrack ownership. A service already observing sessions must use
its existing hooks, not attach a duplicate observer/effects chain.

The instrumentation suite checks real session IDs/open/close behavior. It cannot prove Poweramp
Equalizer processing or internal-EQ sound. Existing service audio-session code and effect tests
remain unchanged. Device EQ/routing validation is still required.

## Tests, measurements and limits

See [the execution report](benchmarks/phase32-native-executor/README.md) for actual counts, raw
JSON, timings and commands. The generated corpus includes correlated/uncorrelated tones,
aligned/offset kicks, quiet tails/intros, leading silence, silence and amplitude changes across
all curves; earlier/native tests cover noise, chords, drift and false grids. Virtual execution
covers deadline misses, long overlaps, state transitions, generation changes, cancellation,
concurrent control, rate/position changes, EOS and exact-once promotion.

Host JNI comparisons execute actual corrected capture -> actual native DSP -> real planner ->
virtual executor. They do **not** establish Android playback. Fourteen Android tests are separately
compiled/packaged: initialization/isolation; admission; cue preparation; actual two-player progress;
start timing; envelopes/volume; ~30s overlap; quiet-reference stress; handoff; cancellation; queue
invalidation; pause/resume; focus/route forwarding; real session lifecycle. One optional local
listening test is outside that generated suite. Prior owner-run 24 passes are historical.

Cloud has no KVM, system image or connected Android device. Compilation cannot validate the new
player-level assertions. Device timing tolerances are explicit engineering limits: failed starts
are reported, not relabeled aligned or fixed by lowering musical thresholds.

## S25 Ultra / Windows PowerShell

Check out this PR branch. Keep your existing working debug key. `-PautomixDecoderTests=true`
retains the isolated `.decodercheck` package; no uninstall or normal-app data deletion is needed.
Set a comfortable phone media volume, pause normal playback, and keep the phone connected and
awake for the first run. Generated tests play low-volume synthetic music; allow several minutes.

**14 new generated-media tests**, expected zero failures/skips:

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

**Previous 24 tests** afterward (or add the new class to run 38 together):

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Look for the actual executed count, not just BUILD SUCCESSFUL. On failure retain the connected-test
HTML/XML reports under `app/build/reports/androidTests/connected/` and
`app/build/outputs/androidTest-results/connected/`. Collect controlled diagnostics:

```powershell
adb logcat -d -s TiniumNativeDj:I '*:S' > phase32-player-observations.txt
```

Do not share unrestricted logcat/private source files. `NATIVE_DJ_ANDROID` reports versions,
BPMs, cues, gains, state/reason, estimated media timing and the last six bounded events; no song
names, URLs or local paths. Detailed history is bounded at 128 events per explicit attempt.
The existing Stats for Nerds export UI/schema is unchanged and does not automatically collect
this isolated session.

### Owner-reported startup failures and bounded diagnostic

Three supplied reports share the same terminal reason, `EXCESSIVE_DRIFT`:

| Test | Dispatch lateness relative to planned anchor | First measured offset | Maximum measured offset before abort |
| --- | ---: | ---: | ---: |
| Quiet-reference midpoint gain | 3,741 us | 125,004 us | 125,004 us |
| Pause/resume | 7,741 us | 119,004 us | 129,004 us |
| Extended overlap/handoff | 6,474 us | 139,163 us | 139,163 us |

The commands were within the 40 ms scheduling limit. These are **player-clock offsets**,
not acoustic measurements. All failures preceded the intended gain, pause or completion
assertions. In the quiet-reference case, both content gains were exactly 1.0; this report
does not identify a level-matching failure. The native pair's predicted tempo drift was
under 1.3 ms, much smaller than the observed startup separation. The failure reports do
not establish whether that separation persists after startup, nor prove native beat
accuracy or synchronized long playback. No complete connected-test XML/counts or exact
owner-tested commit were supplied with the three reports.

The executor currently treats the first advancing incoming player position as usable
alignment evidence. Media3 1.7.1's `audio/AudioTrackPositionTracker.java`,
`getCurrentPositionUs()`, can initially use a latency-adjusted playback-head estimate,
then switch to an advancing AudioTimestamp and smooth that mode switch over one second.
`DefaultAudioSink.play()` starts its AudioTrack asynchronously relative to the caller's
player command. These source facts support **two still-distinct hypotheses**: a real
independent-output startup delay, or an unstable early clock estimate. The supplied
reports end too early to distinguish them. Neither is repaired by changing the native
detector, cue, gain curve, confidence gate, or merely increasing the allowed drift.

Source references match the repository's pinned Media3 1.7.1:
[AudioTrackPositionTracker](https://github.com/androidx/media/blob/1.7.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackPositionTracker.java)
and [DefaultAudioSink](https://github.com/androidx/media/blob/1.7.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java).

The new passing JVM characterization explicitly reproduces all three reported offset
magnitudes and requires fallback, no promotion, incoming release and outgoing continuity.
**The 120 ms limit, timing assertions, production code and original 14 Android tests remain
unchanged. No startup repair is claimed yet.**

Run the separately selected probe after updating this branch:

```powershell
git fetch origin
git switch codex/automix-native-dj-executor
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeDjStartupClockInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase32-startup-clock.txt | Set-Content -Encoding utf8 .\phase32-startup-clock.txt
```

If your SDK is elsewhere, use its `platform-tools\adb.exe` path (Android Studio's SDK
Manager shows the SDK location). Keep the existing signing setup and isolated package;
do not uninstall the main app. Expect **one diagnostic test**, which probes both standard
and extended fixtures. Its pass only confirms observation/progression/cleanup, **not a
successful mix**. Share `phase32-startup-clock.txt` even if the probe passes; an absent or
empty report is not usable evidence. The fixed private test report is overwritten on
each explicit run; it does not alter Stats for Nerds export. It contains at most 128
position samples and 32 controlled callbacks per fixture, no media identities or audio.

The probe prepares the same selected cue and issues play at the original outgoing anchor,
keeps incoming volume zero for two seconds, and records positions/readiness, command
timing, AudioTrack configuration, advancing-position callbacks and underruns. It never
seeks after prepare, changes rate, promotes or labels a transition aligned. It deliberately
does not use the executor's early-abort policy, solely to observe the clocks after the
point where the failing tests stop. Sampling uses bounded main-looper callbacks with a
deadline; no sleeps block the player thread. This is diagnostic infrastructure, not a
replacement executor or a relaxation of acceptance tests.

If offsets remain above 120 ms after startup, the existing scheme cannot meet its stated
bound on that route; a measured preparation/scheduling correction must precede merge.
If offsets settle inside the bound, validate which clock evidence is usable before
changing admission/observation policy; do not silently rebase the incoming cue or declare
the early samples accurate. After any repair, rerun the original 14 plus prior 24 tests.
Actual output capture remains necessary for claims about acoustic beat alignment.

### Optional permitted private music

After the isolated package is installed by the generated suite, use only files you may play and
analyze. The harness reads two fixed **app-private** filenames; it does not access normal downloads,
request storage permissions or upload anything. Example (replace only the two local input paths):

```powershell
adb shell run-as com.yren411.tinium.decodercheck mkdir -p files
adb push "C:\Music\permitted-out.flac" /data/local/tmp/phase32-out.audio
adb push "C:\Music\permitted-in.flac" /data/local/tmp/phase32-in.audio
adb shell run-as com.yren411.tinium.decodercheck cp /data/local/tmp/phase32-out.audio files/phase32-out.audio
adb shell run-as com.yren411.tinium.decodercheck cp /data/local/tmp/phase32-in.audio files/phase32-in.audio
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeDjLocalListeningInstrumentedTest" "-Pandroid.testInstrumentationRunnerArguments.phase32Listen=true" "-Pandroid.testInstrumentationRunnerArguments.phase32OutgoingStartMs=150000" --no-configuration-cache --no-daemon --stacktrace
```

If this Android build denies the temporary-file copy, use Android Studio Device Explorer to
upload the files directly into the isolated package files directory with these exact names;
do not grant broad storage access or use root.

This selects **one** optional test. Choose an actual permitted outgoing analysis section with >=32s
available. Incoming analysis starts at zero. The planner can legitimately reject a pair: no cue
or gain is forced to make listening start. An unsupported codec, partial seek coverage or a duration
mismatch can also fail safely. Cancellation/end of instrumentation closes both test players; normal
Tinium remains independent. To stop listening immediately, use `adb shell am force-stop com.yren411.tinium.decodercheck`.
This stops only the isolated test package and does not erase normal Tinium data. Gradle Ctrl+C
alone is not a reliable audio-stop guarantee. Normal test completion runs the cleanup hooks;
force-stopping relies on Android process/AudioTrack teardown. Cleanup private inputs when finished:

```powershell
adb shell run-as com.yren411.tinium.decodercheck rm -f files/phase32-out.audio files/phase32-in.audio
adb shell rm -f /data/local/tmp/phase32-out.audio /data/local/tmp/phase32-in.audio
```

If debug signing conflicts, preserve the existing key and stop to resolve the certificate mismatch;
do not uninstall the primary app or change signing/application IDs. Windows/device steps above
are reproducible instructions, not claims they were executed in the cloud.

### Optional acoustic/output measurement

Player logs alone cannot prove beat synchronization. For generated media, optionally record the
output using a permitted external USB/audio loopback or microphone setup. Retain known source
beat times, measure each deck's distinguishable pulse/transient events and compare the captured
start offset/drift across repeats. Separate recording-path latency from relative timing. Android
AudioPlaybackCapture would require an explicit user-consent flow, which is not added here. Do not
collect private music recordings for shared diagnostics. Repeat wired/Bluetooth, screen-off,
pause/resume and Poweramp/internal-EQ enabled/disabled separately, recording route and observations.

## Phase 3.3 gate and licensing

Before any real production activation: run the 14 new tests plus previous 24 on the S25; evaluate
allowed real-song pairs, gain/timing/rollback listening, focus/session integration through the
existing service and external EQ on actual routes; consider captured-output timing; resolve
source duration discrepancies; validate startup/drift limits and longer overlaps under load.
Phrase/vocal/harmonic confidence, half-time ambiguity and active-content preservation remain
limitations inherited from Phase 3.1. An early exit is explicitly flagged, never proof that a
whole song's musical structure was understood. No rollout or public toggle is implemented.

Existing Orchard/BitChord notices and AGPLv3-or-later source obligations are preserved. New
experimental native-planning glue follows that provenance; root terms are unchanged. The owner's
Musixmatch permission working assumption is not public redistribution clearance. No lyrics change,
new code import, distribution signing or public APK/release publication is part of this phase.
