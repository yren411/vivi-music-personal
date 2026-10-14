# AutoMix pipeline correction and player diagnostics

This change follows PR #12. It keeps Tinium's Media3 decks, internal processing,
external audio-effect session ownership, performance preferences, signing,
release allocation and existing Crossfade behavior.

## Reproduced causes, and what remains device-dependent

A generated 120 BPM pair reproduces the reported **near-end** symptom through
`TrackFeaturesAnalyzer → mergeTrackFeatures → AutoMixPlanner → AutoMixController`.
The outgoing track lasts 216 seconds, drops into a sustained instrumental outro
at 181 seconds, and the incoming track has eight seconds of leading silence.
Before correcting the audio measurements, the test selected an end-window beat
blend at **212 seconds with a four-second overlap**, despite finding the outro.
This was not an assertion against fabricated TrackFeatures.

Two measurement errors contributed:

- Grid stability counted predicted beats in leading silence as missing beats.
  A usable incoming entry consequently failed the rhythm-confidence gate.
  Stability now uses the measured audible interval and preserves absolute timing.
- Each 500ms RMS energy bin included frame 50, which belongs to the next bin.
  The next kick was sampled into a silent interval, inventing a cue 500ms early.
  Bins now contain exactly 50 frames, have a center timestamp, and exclude partial
  trailing bins. The rhythm, key and vocal-risk thresholds were not broadly lowered.

The controller also had confirmed scheduling/lifecycle defects:

- Refinement reused the scan's original 40-second deadline, including time spent
  waiting for the worker pool. It could start already expired.
- Advancing between stages called `workers.cancelAll()`, including unfinished
  refinement five seconds before the candidate boundary.
- Playlist edits invalidated all preparation even if only future songs changed.

We cannot attribute every transition heard on a Samsung phone to one cause without
its diagnostics. Android MediaExtractor/MediaCodec, remote range access, real
buffering/output latency and actual audio quality still require device testing.
A public audio-effects API and correct session ownership do not prove third-party
Poweramp processing on every route. No audio was listened to during JVM tests.

## Corrected scheduling

Analysis begins when the actual current/next pair and permitted source are known.
Unresolved sources wait; next-source resolution uses the existing prefetcher, with
30-second retry spacing for the same unresolved song. Changing the pair allows an
immediate retry. No URL, cookies or authentication data are exposed in new state/logs.

Coverage stays bounded: adaptive outgoing scan of up to 90 seconds, up to 32 incoming
seconds, at most 32 seconds per decoded window. Coarse work has a 90-second phase
budget and a 25-second per-window budget starting when that job actually executes.
A candidate requires at least five seconds' planning clearance. Refinement gets a
fresh budget of up to 40 seconds, constrained by the candidate's remaining lead,
with a 25-second maximum per executing window. At 1.5 seconds before the preview
boundary unfinished refinement is canceled explicitly and safely falls back.
Only still-unfinished jobs of that stage are canceled; aggregation/planning never
waits on another task within the same executor. Source/seek/disable invalidation
still cancels all obsolete work.

All jobs, including aggregation, share the existing bounded executor: Efficient
1, Balanced 2, Performance 4 workers; queue capacity 4 and feature cache 64.
No new pool, native component, model, dependency, whole-song download or quality
reduction is introduced. Codec/network infrastructure can have its own internal
threads; these counts bound Tinium analysis tasks, not the whole Android process.

Future queue append/reorder preserves features when current and next source
identities remain unchanged. A prepared deck with an outdated copied playlist is
released/recreated without restarting analysis; genuine next-track/source changes
still invalidate it. Explicit seeks and shuffle/repeat controls retain their
existing cancellation hooks. Pause preserves valid preparation.

Refinement must cover the selected outgoing/incoming overlap. An analyzer/player
outgoing duration mismatch above one second is rejected, rather than plotting a
point on another timeline. Deck preparation starts up to 30 seconds before the
final plan. Ready means buffered, at the requested cue (100ms tolerance), suitable
PCM and sufficient actual incoming duration. A start more than 250ms late is
rejected, logged and replaced with a separately identified later fallback when
there is time; otherwise normal playback continues. A failed preparation is
similarly identified. No displayed early plan survives this replacement.

## What is actually mixed

The existing candidate scorer pairs measured outro boundaries with safe incoming
entries. It uses local rhythm/phase confidence, measured repeated bass accents
when available, conservative harmonic compatibility, energy and vocal-risk proxy
checks. It preserves important intros and rejects later vocal/buildup returns.
No uniform-beat modulo is promoted to semantic phrase/chorus recognition.

The deterministic PCM fixture now produces, in every mode:

| Field | Measured/planned result |
| --- | --- |
| Outgoing duration | 3:36 |
| Outgoing start | 3:01.020 |
| Incoming cue | 0:08 |
| Overlap | 16 seconds |
| Technique | Beat blend |
| Rate | 1.0 for the identical tempos |
| Grid phase confidence | approximately 0.79 |

The test actually advances the controller at this start, checks `Mixing` and its
actual timestamp, applies its gains through the PCM kernels, checks both sources
contribute and bounds the summed peak. It releases the outgoing deck around 3:17,
before the original file end, and promotes the incoming track only once without
restarting its cue. Existing numerical bass/filter and pitch-preserving Sonic tests
remain applicable. This is simulated PCM output, not captured Android AudioTrack
output. Equal-power envelopes with correlation headroom, selective bass handoff,
gentle filtering, level matching and bounded Sonic tempo adjustment are retained.
This is not a new stem-separation or semantic phrase detector.

A legitimate short energy fade, a confirmed rhythmic plan, an unavailable/refinement-
failed fallback, a missed boundary, a deck failure and no mix are separate outcomes.
The 2.5-second safe fallback remains. Unsupported/non-range/encrypted sources,
unknown duration, decoder or byte-budget errors, low-confidence/unusable music,
late refinement and missed preparation must not force an early mix.

## State and Appearance settings

`AutoMixStatus` is a read-only service/controller StateFlow. It carries current/next
IDs, outgoing duration, final plan (cue, start, duration, technique, score/grid
confidence), controlled reason codes and actual start. It never includes source URLs.
It distinguishes waiting for next/source, current/next scanning, refining a candidate,
confirmed plan, decoder preparation/readiness, mixing, fallback, recovery and normal
playback. Candidate refinement alone is not a final plan. State is deduplicated;
Compose observes it with lifecycle-aware collection and performs no analysis.

**Settings → Appearance → Player → Show AutoMix transition point**, immediately
below Player slider style, defaults off. A small theme-colored mark is drawn for a
confirmed rhythmic plan of the visible current/actual next pair. Its fraction is
`plannedStart / visibleDuration`; unknown/mismatched durations, invalidation,
fallbacks, scrubbing and track promotion hide it. It also clears the thumb visually.
It is non-interactive and adds a descriptive accessibility label without changing
slider seeking/value semantics.

Classic/New sliders (Default, Slim, Wavy, Squiggly, Expressive) and the V2 Slim
slider are supported. All have linear time X coordinates, even when the wave's
Y varies. Mapping includes each pinned widget's real thumb inset (Material3 2dp,
Expressive 8dp, others zero), horizontal padding, and the Material3/Slim RTL mapping.
The marker's shape/visibility and all touch behavior still need device confirmation.

**Settings → Appearance → Advanced → Stats for Nerds**, at the bottom, defaults off.
It adds subtle wrapping label text immediately below the full-player slider:

- **Source**: renderer input MIME/codec, positive sample rate, average bitrate and
  channel count; source PCM depth only when raw PCM encoding establishes it.
- **App output**: reported AudioTrack encoding/sample rate, when known. This is
  not the Bluetooth DAC's actual output format. Lossy decoded PCM is not labeled
  as a lossless source. Unknown values are omitted/identified, never guessed.
- **AutoMix**: the actual phase, final technique/start/cue/overlap or a concise
  fallback reason. It distinguishes ready from mixing. Audio info remains available
  with AutoMix off; casting reports remote information unavailable rather than
  displaying stale local audio format.

Input-format changes and player promotion update audio stats. Released deck metadata
is cleaned up and a new track without confirmed renderer data shows Unknown.
Appearance settings persist independently and do not alter AutoMix, Crossfade,
performance mode, user audio preferences or effect sessions.

## Galaxy S25 Ultra test checklist

1. Merge/review the implementation and manually build a fresh private candidate
   through the existing beta/production workflow. Do not reuse a reserved version
   with another commit; unreleased notes remain an unreviewed private-build draft.
2. Settings → Player and audio → Player: enable AutoMix, choose Balanced initially.
   Crossfade must be unavailable while retaining its saved preference.
3. Enable both new Appearance toggles above. Keep Stats enabled with AutoMix off
   briefly too, to verify audio data is independent. Test classic/new and V2 player,
   every slider style, light/dark/pure-black, landscape and larger system text.
4. Queue songs with a sustained instrumental outro and rhythmic intro, then begin
   playback normally well before that outro. Waiting/Analyzing/Refining are not a
   promise that a mix will occur. `Transition confirmed` and then `Incoming deck
   ready` with Beat blend/Bass handoff establish a real rhythmic plan.
5. Confirm the mark matches the Starts timestamp and that audible overlap and
   `Mixing` begin there. Early metadata promotion should show the incoming song
   without restarting it. Several consecutive transitions should advance once.
6. `Fallback fade` with a reason is not intelligent-mix success. Unavailable with
   no marker means normal playback. Record which stage/reason occurs on problem
   songs. Test leading silence, vocal endings, incompatible rhythms, cached/downloaded
   tracks, network loss, short tracks and missing next tracks.
7. Change future queue entries, actual next song, shuffle/repeat, seek, pause/resume
   and disable AutoMix. A stale mark must disappear; no prepared deck should keep
   playing. Check Efficient and Performance too.
8. Verify screen-off playback, lock-screen controls, Bluetooth/wired/speaker output,
   calls/focus loss, normal Crossfade and Poweramp with internal EQ disabled, then
   internal EQ separately. Use an obvious external EQ preset temporarily to hear
   whether the incoming song keeps the effect; restore your preset afterward.

For logs, enable USB debugging and use Android platform-tools on Windows:

```powershell
adb logcat -c
adb logcat -v threadtime TiniumAutoMix:I TiniumAudioSession:I '*:S' | Tee-Object -FilePath tinium-automix.txt
```

Reproduce a transition and press Ctrl+C. New tags contain timings, techniques,
controlled failure reasons and session IDs, not stream URLs or credentials.
Debug builds add per-window start/result/rejection diagnostics. `event=plan` is
not execution proof: compare its start with `event=start`, then
`event=handoff-complete`. `start-missed`, `analysis-failed`, `invalidated` or fallback
reasons explain lost plans. Share only these filtered tags after reviewing the file.
No automatic pause/resume workaround or Poweramp-specific hidden intent is used.

## Reference and licensing

BitChord revision `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` was re-inspected:
`CrossfadeController.kt` publishes a guarded normalized transition window;
`sharedUi/.../NowPlayingScreen.kt` hides it while scrubbing;
`PlayerControls.kt` displays lifecycle-collected diagnostics, and
`shared/.../NerdStats.kt` separates authoritative audio telemetry. Its repository
root is GPLv3, but TransitionPlanner/Orchard-derived files have AGPLv3+ notices.
These are design references only. No BitChord/Orchard UI/engine source, models,
assets or new dependencies were copied here. Existing attribution and licensing
in [AUTOMIX.md](AUTOMIX.md) remain intact. The UI and corrections are independently
implemented for Tinium. Pinned AndroidX/Expressive slider source was inspected to
verify geometry; neither library nor its implementation was changed.

## Cloud validation and changed files

Passed **152 JVM tests per flavor**, zero failures/errors/skips (Arm64 GMS Debug
and Universal FOSS Debug). This includes seven new real-analyzer/controller
pipeline tests and five diagnostic/metadata/geometry/preferences tests, plus the
existing planning, DSP/Sonic, worker, playback lifecycle and external session tests.
Both Universal GMS Release and Universal FOSS Release source-compilation targets
passed, including resources/generated code; merged manifests retain
`com.yren411.tinium`. XML parsing and `git diff --check` passed. No APK was packaged,
signed or distributed. Full Android Lint and device/instrumentation/rendered UI,
MediaExtractor/MediaCodec fixture playback and captured AudioTrack output were not
run; the JVM fixture supplies decoded PCM and simulated decks. The full legacy lint
suite was not repeated for this focused change. No claim is made that a phone's
acoustic mix or Poweramp behavior has been verified.

Exact repository paths (unchanged signing/workflows/dependencies are omitted):

- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixController.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixPlanner.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/TrackFeaturesAnalyzer.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AndroidWindowDecoder.kt`
- `app/src/main/kotlin/com/music/vivi/playback/automix/AutoMixStatus.kt`
- `app/src/main/kotlin/com/music/vivi/playback/MusicService.kt`
- `app/src/main/kotlin/com/music/vivi/playback/AudioDiagnostics.kt`
- `app/src/main/kotlin/com/music/vivi/constants/PreferenceKeys.kt`
- `app/src/main/kotlin/com/music/vivi/ui/component/PlayerDiagnostics.kt`
- `app/src/main/kotlin/com/music/vivi/ui/player/Player.kt`
- `app/src/main/kotlin/com/music/vivi/ui/player/Player_v2.kt`
- `app/src/main/kotlin/com/music/vivi/ui/screens/settings/AppearanceSettings.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/com/music/vivi/playback/automix/AutoMixPipelineTest.kt`
- `app/src/test/kotlin/com/music/vivi/playback/automix/AutoMixDiagnosticsTest.kt`
- `app/src/test/kotlin/com/music/vivi/playback/automix/AutoMixEarlyExecutionTest.kt`
- `docs/AUTOMIX.md`
- `docs/AUTOMIX_QUALITY_INVESTIGATION.md`
- `docs/AUTOMIX_QUALITY_AND_EXTERNAL_EQ.md`
- `docs/AUTOMIX_PIPELINE_AND_DIAGNOSTICS.md`
- `release-notes/unreleased.md`
