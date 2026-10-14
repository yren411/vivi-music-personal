# Tinium intelligent AutoMix

AutoMix is opt-in and uses local decoded audio to plan a transition between the
current and immediate next song. It is separate from the existing Crossfade
feature and does not change its stored preferences. Device listening and the
Android decoder/AudioTrack path still need verification before release; JVM PCM
tests cannot certify perceived musical quality or OEM output timing.

## Reference investigation and licenses

Reference: [BitChord](https://github.com/kushagrasinghx/BitChord), inspected at
[`fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`](https://github.com/kushagrasinghx/BitChord/tree/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f).
Credit Kushagra Singh for BitChord and SFG545 for its referenced Orchard work.
Existing VIVI Music attribution is preserved.

The inspected source includes:

| Reference component | Findings / Tinium approach |
| --- | --- |
| Android `playback/smart/TrackAnalyzer.kt`, `AudioDecoder.kt` | Ahead-of-playback analysis, cached/range-access sources, head/tail passes. Tinium implements bounded windows using Android MediaExtractor/MediaCodec and its own existing source/cache path. |
| Shared `TransitionPlanner.kt`, `TransitionPolicy.kt`, `TrackAnalysis.kt` | Evidence-gated timing, incoming cue, harmonic/vocal considerations and technique selection. Tinium independently implements a conservative policy; no source is copied. |
| JVM `BeatTracker.kt`, `VocalTracker.kt` | Beat This! and open-unmix ONNX inference. Not imported; Tinium uses DSP onset/energy analysis and a conservative vocal-risk proxy. |
| JVM `AnalysisStore.kt` | Persistent versioned analysis. Tinium initially keeps a bounded, rendition-keyed in-memory feature cache, not full decoded audio or a new database schema. |
| Android `CrossfadeController.kt`, `TransitionFilterProcessor.kt`, PlaybackService integration | Dual decks, tempo playback rate, equal-power ramps and filtering. Tinium preserves its existing Media3 service/session/queue and adds a separate controller and filter processor. |
| `native/analyzer/`, Android JNI files, `CMakeLists.txt` | C++ DSP, tempo/resampling, mel and vocal STFT front ends, JNI, 16KB library alignment. Not imported. |
| `AutomixPerformanceMode.kt` | 1/2/4 inference budget. Tinium applies 1/2/4 bounded analysis workers to decoding and DSP tasks, with no ONNX internal pools. |

**Important licensing distinction:** BitChord's root LICENSE is GPLv3, but its
planner/policy, beat/vocal trackers, native analyzer and JNI headers explicitly
retain **AGPLv3-or-later** licensing from Orchard. A root GPL label is not
permission to relabel those files. Its model comments describe MIT-licensed Beat
This! and open-unmix weights, and its separate DSD/DST decoding includes LGPL
notices. None of those source files, binaries, models or assets are redistributed
with this change; no assumption about their asset provenance is needed to ship
this implementation.

All new AutoMix implementation is independently written for Tinium under the
repository's existing license. Architectural concepts and published mathematical
methods are referenced, not copied executable code. Chroma key estimation uses
the numerical Krumhansl–Kessler major/minor profiles (see Krumhansl, *Cognitive
Foundations of Musical Pitch*, 1990). Android's decoder and the already-used
Apache-2.0 Media3/Sonic components are reused. No new dependency or native/model
license is introduced. Tinium's LICENSE contains existing additional Musixmatch
module restrictions; AutoMix does not copy/change that module or grant new rights
to distribute it. Existing distribution permissions remain the owner's
responsibility. Adding a BitChord credit alone would not have licensed copied
AGPL/model code; this implementation deliberately avoids that issue.

## Analysis and planning

- Decode at most 32 seconds of the outgoing tail and incoming head, split into
  four disjoint 16s jobs for ordinary tracks. This lets Performance actually use
  four workers; all modes merge the same analyzed windows. Shorter tracks use
  fewer jobs. A pair-wide 25-second deadline, per-job 12MiB read budget and four
  64KiB source blocks bound work. Inconsistent segment tempos disable beat matching.
- Use permitted resolved URLs and existing download/player caches. Streaming
  analysis requires demonstrated HTTP byte-range support; it never downloads a
  whole song to emulate seeking on a non-range server. Analysis cache writes are
  disabled. DRM/encrypted audio, live/unknown duration and unsupported decoding
  fall back safely.
- Convert decoded PCM to fixed 22,050Hz mono **for analysis only**, identically in
  all performance modes. This does not change audible bitrate/sample rate.
- Measure a 10ms amplitude-attack envelope, autocorrelation tempo and beat phase.
  Require enough beats, strong transients, energy variation and confidence before
  using that grid. A metadata BPM never authorizes beat matching.
- Compute FFT chroma and confidence-based key estimates, RMS/peak levels and
  time-local energy. Favor an energy valley near the end and a weak four-beat
  phrase-length heuristic. This is **not meter/downbeat or semantic song-structure
  detection**; only small end regions are eligible.
- Estimate vocal collision risk from spectral voice-band energy and tonality.
  This is **not stem separation or reliable vocal recognition**. Instruments can
  trigger it; uncertain/risky material gets a shorter, less ambitious transition.
- Match sufficiently confident tempos within 4%, ordinarily use eight measured
  beats (bounded to 4–10 seconds), align the outgoing start and incoming cue to
  measured beats, and choose a bass handoff/beat blend. Known incompatible keys
  or prominent vocal risk shorten the blend and disable stretching.
- Never boost a track to loudness-match another. Attenuate the louder analyzed
  window. Incoming cue skips only a small measured intro region (at most 2s), not
  an arbitrary later verse/drop. The outgoing track continues to its actual end.

## Actual transition execution

Two existing Media3 players render concurrently to Android's audio mixer. The
incoming player is prepared paused, at its planned cue, without publishing it to
the UI/session. It is promoted once at transition start, retaining the full queue,
shuffle traversal and repeat state; it is never restarted after the fade.

Gain shapes are equal-power with conservative worst-case correlation headroom.
AudioTrack deck volume applies those gains to **already-buffered PCM**, avoiding
the silent prebuffer problem caused by analyzing/muting decoded standby samples.
The PCM processor performs gentle low-end handoff or treble filtering with
smoothed coefficients. A temporary incoming playback-rate adjustment uses the
existing Media3 Sonic software path with pitch 1.0. Player-position drift receives
at most 1% additional correction; rate and transition attenuation ease back over
3s after the outgoing decoder is released. No Rubber Band or native stretcher is
added. Independent AudioTracks are not sample-locked: perceived beat alignment
and latency need actual-device testing, particularly on Bluetooth/Cast changes.

The controller uses player positions, not a wall-clock delay, for progress.
Pause/focus loss pauses both decks and freezes the blend; ducking/mute/user volume
affect both. Seek/skip, queue changes, a changed source, settings/mode changes and
decoder failure invalidate the old operation. The outgoing deck cannot advance
into the incoming song and is released once. MediaSession, player flow, metadata,
notifications and widgets follow the promoted incoming player. Temporary EQ,
silence-detector and AutoMix processor registrations are removed on release.

External/system audio effects can alter the signal after these safeguards;
generated-PCM peak tests do not guarantee unclipped output from an arbitrary
external EQ or Bluetooth device. Conservative correlation protection may make
the middle of a blend quieter than an ordinary equal-power sum.

## Settings and compatibility

Location: **Settings → Player and audio → Player**, immediately above Crossfade.
AutoMix defaults **Off**. Its performance selector is shown only while enabled:

| Mode | Analysis workers | Meaning |
| --- | ---: | --- |
| Efficient | 1 | Uses fewer system resources; upcoming analysis may take longer. |
| Balanced (default) | 2 | Balances analysis speed with CPU/battery usage. |
| Performance | 4 | Allows more concurrent analysis work. |

One service-owned executor has a four-task bounded waiting queue, deduplicates
identical rendition/window requests and keeps at most 64 small feature results.
No full PCM survives analysis. A mode change cancels obsolete tasks and adjusts
that executor live; cooperative loops stop promptly. An in-flight platform
decoder/network call may take until its bounded return/timeout to finish cleanup.
MediaCodec's internal/system threads, Media3 playback/rendering, network and
coroutine scheduling threads are outside the analysis-worker count. This is not a
claim that the whole app has only 1, 2 or 4 OS threads.

Crossfade's toggle and associated controls are disabled while AutoMix is enabled.
Its saved choice/duration/curve remain unchanged and become effective again after
AutoMix is disabled. Mutual exclusion is also enforced in the service. An AutoMix
safe fade is its own 2.5s fallback, independent of the saved Crossfade settings.
If analysis is late or a prepared decoder misses the start deadline, normal
playback wins; no delayed surprise transition is forced.

Offload is effectively disabled without erasing its preference. EQ remains in
the software path; hardware loudness normalization is suspended during an
overlap and restored afterward. Mixing/analysis are bypassed during Cast,
Listen Together, skip-silence, non-unit user speed/pitch, repeat-one, contiguous
same-album playback and sleep-timer end-of-song pauses. These paths keep normal
playback. Short, silent and live tracks do not force a mix. The original YouTube
radio/queue feature also called “automix” is unrelated and is preserved.

No ONNX/native/model assets are added, so all existing Android ABIs keep the same
native packaging. APK growth is limited to Kotlin code and string resources.

## Validation and phone checks

Automated checks use synthesized pulses/chords/tones, never copyrighted song
fixtures or live providers. They cover DSP tempo/confidence, policy/keys/vocal
risk, actual two-frequency rendered PCM and peaks, bounded workers/cancellation,
and the same controller's preparation/promotion/pause/seek/queue/error states.
Android MediaExtractor/MediaCodec and AudioTrack output require instrumentation
or a phone; JVM fake-deck tests do not validate the OS decoder or listening quality.

On the Galaxy S25 Ultra, start with downloaded tracks of similar tempo and then
try permitted streams. Verify next-song metadata/position and that the outgoing
audio stops. Compare Efficient/Balanced/Performance; test incompatible tempos,
vocals, silent intros and very short songs. Check screen-off/background playback,
pause/resume, headset buttons, seeks/skips, queue edits, repeat/shuffle, incoming
calls/focus ducking, speakers/wired/Bluetooth, EQ, offload and Cast/Listen Together
fallbacks. Check that disabling AutoMix restores the prior Crossfade choice and
that restarting the app preserves settings. Listen for timing drift, silence,
center-level dips, clipping, and tempo artifacts. Do not publish based solely on
unit tests; test the exact normally prepared release candidate separately.
