# BitChord AutoMix port feasibility — Phase 1

## 1. Executive summary

**Verdict: Conditionally feasible.** BitChord's native DSP is a credible replacement candidate for Tinium's rhythm estimator, but copying the native directory alone will not fix the complete system. Recommend **Option B as a staged, selective analysis integration**, starting with the DSP core and a replaceable analysis interface. Keep Tinium's source access, Media3 decks, controller, EQ lifecycle, settings, diagnostics and UI. Benchmark the optional Beat This! tracker before deciding whether to include it; do not initially import the vocal model or BitChord's playback service.

The supplied device report establishes a measurement failure, not merely a preference about fade style. All 61 completed windows fall below Tinium's rhythmic confidence gates. On identical generated PCM, this investigation also reproduced incorrect tempos and low confidence in Tinium without Android decoding or networking. BitChord's DSP tracked six of seven generated tempos accurately, but selected half-time at 174 BPM and produced a fairly high score on an unchanging chord. It is an improvement candidate, **not an accuracy guarantee**.

Three independent workstreams are required:

1. **Rhythm measurement:** resolve integer-period quantization, sparse onset indexing, fixed-grid drift, octave ambiguity and uncalibrated confidence.
2. **PCM correctness/source coverage:** diagnose failed Android windows and replace nearest-time conversion with validated, anti-aliased resampling. A native analyzer cannot recover audio the decoder did not supply.
3. **Planning/output policy:** validate key and density vetoes; fix the legacy policy that attenuates a loud incoming track to a nearly silent outgoing tail. Preserve the working handoff and external-effect lifecycle.

This PR contains **only this report**. No code, dependencies, models, binaries, settings, notes, build configuration, release allocation or signing were changed. The attached report is summarized rather than committed. No device listening, APK creation or public release was performed.

### Evidence terminology

- **Confirmed:** directly observable source behavior, recomputed log values or executed controlled experiments.
- **Strong hypothesis:** source behavior explains evidence, but missing decoded PCM/device instrumentation prevents causal proof for those particular songs.
- **Unverified:** plausible behavior requiring Android/device measurements.
- A confidence number is an algorithm's score, not a calibrated probability of being musically correct.
- The installed app's versionName/versionCode do **not** identify its source commit.

## 2. Exact source baseline and inspection scope

| Source | Branch inspected | Commit |
|---|---|---|
| Tinium | origin/main; audit branch codex/bitchord-automix-feasibility | **e026fecc3bb7984654e9b10e22454af46f1af6de** — merge PR #14, 2026-10-14 |
| BitChord | main fetched from origin | **fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f** — 2026-10-11 |
| Beat This! licensing evidence | main | b95c8ab0c58c2d9fcfd40508ae8dffbc05ac4f5c |
| open-unmix licensing evidence | master | fb672c9584997c2b05e148eeaa65b4c23ed4693b |
| ONNX Runtime license | v1.28.0 tag | MIT LICENSE examined; no AAR/Android binaries evaluated |

Source roots, pinned for repeatable inspection:

- [Tinium source](https://github.com/yren411/vivi-music-personal/tree/e026fecc3bb7984654e9b10e22454af46f1af6de).
- [BitChord source](https://github.com/kushagrasinghx/BitChord/tree/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f).
- [BitChord native analyzer](https://github.com/kushagrasinghx/BitChord/tree/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/native/analyzer).

References below use **T:** for Tinium and **B:** for BitChord, relative to these roots. **T-A** abbreviates `app/src/main/kotlin/com/music/vivi/playback/automix/`. **B-J** abbreviates `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/`; **B-C** abbreviates `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/`. Function names identify the examined implementation, not just a documentation claim.

### Build baseline

| Property | Tinium | BitChord |
|---|---|---|
| Gradle wrapper | 9.3.1 | 8.11.1 |
| AGP | 9.1.1 | 8.10.1, explicit R8 8.13.23 override |
| Kotlin | 2.3.10 | 2.3.20 |
| Java target | 21 | 17 |
| Media3 | 1.7.1 | 1.11.0 |
| Android | min 26 / compile 37 / target 37 | min 26 / compile 37 / target 36 |
| Native configuration | ndkVersion 27.0.12077973; no analyzer CMake integration | CMake 3.22.1, C++17; app does not explicitly pin an NDK |
| Variants | GMS/FOSS; universal, arm64, armeabi, x86, x86_64 ABI-named flavors | dev/prod; APK splits arm64-v8a, armeabi-v7a, x86_64 |
| AutoMix analysis parallelism | fixed pool 1/2/4, waiting queue 8 | single Java executor; mode sets ONNX intra-op threads 1/2/4 |

Evidence: both `app/build.gradle.kts`, root build files, wrappers; T `gradle/libs.versions.toml`; B `shared/build.gradle.kts` and `app/src/main/cpp/CMakeLists.txt`. Tinium's ABI flavor names currently set BuildConfig labels; they should not be mistaken for proof of native-library filtering.

### Existing architecture, documents and tests

Tinium already has bounded scanning/refinement, window-feature merging, active-section candidate scoring, two Media3 decks, Sonic tempo adjustment, numerical bass/treble processing, promotion without restarting the incoming song, persistent preferences, StateFlow status, timeline marker, compact stats and bounded sanitized export. Read `AutoMixController`, `AutoMixPlanner`, `DjCandidateSelector`, `TrackFeaturesAnalyzer`, `SectionAnalysis`, `AndroidWindowDecoder`, `AutoMixAudioProcessor`, `AutoMixPcm`, `DjAutomation`, `AnalysisWorkers`, `AutoMixModels`, `AutoMixStatus`, `AutoMixRecorder`, `AutoMixFailures` and `MusicService.AutoMixServiceHost/ServiceMixDeck`.

Examined Tinium documents: `docs/AUTOMIX.md`, `AUTOMIX_QUALITY_INVESTIGATION.md`, `AUTOMIX_QUALITY_AND_EXTERNAL_EQ.md`, `AUTOMIX_PIPELINE_AND_DIAGNOSTICS.md` and `AUTOMIX_DJ_MIXING_AND_LOG_EXPORT.md`. The last document supersedes some older timing/selection descriptions. Older documentation describing a corrected synthetic fixture is not evidence of success on real music.

Existing tests include planner, rhythm evidence, generated PCM analysis, controller pipelines, early execution, DJ automation, worker modes/cancellation, filters, tempo processor, status/marker, log privacy/export, preferences, and effect-session/output lifecycle. Relevant files are under `app/src/test/kotlin/com/music/vivi/playback/{automix,effects}/`. In particular, `AutoMixPipelineTest` exercises the real Kotlin analyzer with fake decks and worker scheduling; `DjMixAnalysisTest` uses generated bass/kick material. Most affirmative rhythm fixtures use exactly 120 BPM and simple repeated kicks. They do not establish robustness across ordinary complex music. There is no `app/src/androidTest` directory at this baseline.

BitChord has `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/AdvancedAutomixPlannerTest.kt`, `app/src/test/java/com/music/bitchord/AutomixAnalysisSourceTest.kt` and desktop tests including `DesktopAnalysisTest`, `DesktopBeatModelTest`, `DesktopBeatStressTest`, `DesktopAudioDecoderTest`, `DesktopCrossfadePositionTest`, `DesktopTempoBufferTest` and filter/echo tests. Some desktop tests return early if the native library is unavailable; model stress tests allow null results. Presence/pass status alone would not demonstrate beat accuracy. The full BitChord Android/desktop suites were **not** run.

All requested repository source areas were accessible. Additional upstream license files were accessible; the **Zenodo umxhq record API was blocked by the network proxy with HTTP 403**, preventing independent verification of that deposit's license. No real song audio, original decoder output, source/container identity, device logcat or device access was supplied. These limitations affect decoder causality, real-song accuracy, listening quality and exact converted-model provenance.

## 3. Supplied real-device report

Evidence file: `tinium-automix-log-2026-10-14-19-36-37-1cd93fdc.txt`.

SHA-256: `4e5552c57d8e050d56bbff142045f029f0c4bef2b5ee0248469bf23defaee3ea`.

Header: Tinium 6.0.8 / code 77; Android 17; SM-S938U; Performance / 4 workers. Generated 2026-10-15 00:36:37 UTC; service session began 00:31:06 UTC. The filename's local date differs legitimately from UTC. Track IDs are session-salted hashes; no music ground truth can be reconstructed from them.

### Independently recomputed results

| Measurement | Result |
|---|---|
| Retained attempts | 14; repeated pairs following seeking are not independent song samples |
| Actual START / COMPLETE | 6 / 6 |
| Tier A / B / C starts | 0 / 0 / 6 |
| Explicit CANCELED / skipped | 7 / 0; final pair still in progress when exported |
| WINDOW_START / WINDOW_DONE | 77 / 61 |
| GRID_CONFIDENCE min / median / max | 0 / **0.0958637691** / **0.2902120111** |
| PHASE_CONFIDENCE min / median / max | 0 / **0.0142681382** / **0.1269700883** |
| Completed-window elapsed min / median / max | 241 / 1,615 / 3,472 ms |
| BPM values across 61 windows | 150: 25; 107.142857: 13; 136.363636: 10; 142.857143: 8; 176.470588: 5 |
| Median spectral density; windows ≥0.75 | **1.0**; 56/61 |
| Median RMS; windows RMS ≥0.02 | 0.174073; 58/61 |
| Median vocal-risk proxy | 0.221844 |
| CACHE_HIT / RETRY / RECOVERED | 12 / 5 / 3 |
| Insufficient-audio FAILURE events | 11, across 4 attempts |
| Cancellation FAILURE events | 7, across 4 attempts, associated with SEEK cancellations |

77−61 is **not a decoder failure rate**: starts include retries and unfinished/canceled work. Cached windows are separate events. Eleven insufficient-audio events include repeated failures and propagated final-plan reasons, not eleven independent source failures. REJECTION counts are candidate decisions, not failed decoder calls.

The summary's pair-level categories were confirmed: LOW_BEAT_CONFIDENCE 8 pairs; INCOMPATIBLE_KEY 6; INSUFFICIENT_AUDIO_DATA 4; ANALYSIS_CANCELED 4; NO_SUITABLE_TRANSITION 3; REFINEMENT_FAILED 1; SEEK 7. Categories overlap.

### Every executed plan

All incoming cues were **0 ms** and all incoming rates logged during overlap were **1.0**.

| Pair | Technique / tier | Planned start / actual start (ms) | Planned overlap | Start lateness |
|---|---|---|---|---|
| 3 | ENERGY_FADE / C | 179642 / 179659 | 3500 ms | 17 ms |
| 5 | SAFE_FADE / C | 178121 / 178143 | 2500 ms | 22 ms |
| 7 | ENERGY_FADE / C | 186836 / 186868 | 3500 ms | 32 ms |
| 9 | ENERGY_FADE / C | 175561 / 175591 | 3500 ms | 30 ms |
| 11 | ENERGY_FADE / C | 157141 / 157181 | 3500 ms | 40 ms |
| 13 | GENTLE_FILTER / C | 155223 / 155256 | 2000 ms | 33 ms |

Thus the symptom is **not exclusively the hardcoded 2.5-second fallback**. Five of the six executions used the legacy energy/filter path, also classified C, with 2–3.5-second near-end plans. None demonstrate active beat matching, later cues, bass handoff, tempo correction or long-overlap output. Preparation observations were approximately 1.5 seconds for five pairs and 2.512 seconds for pair 5; the log contains no preparation/deadline-missed failure.

### 3.1 Mathematical rhythm diagnosis

T-A `TrackFeaturesAnalyzer.analyze`, lines 11–110:

- RMS/attack array uses `hop = sampleRate / 100`, while spectral flux is computed every `sampleRate / 50` samples. At 22,050 Hz those are **220 and 441 samples**.
- Each flux result is written into one attack slot at `(offset + 512) / hop`. Most intervening slots remain zero. This is a sparse, almost every-other-slot envelope, not a genuinely sampled 100 Hz onset function.
- Search is integer lags 33…100; `BPM = 6000 / lag`. Logged 150, 142.857143, 136.363636 and 107.142857 are exactly lags **40, 42, 44 and 56**. 176.470588 is lag 34. All are even lags, consistent with the sparse indexing favoring even-lag correlation. This pattern is evidence of estimator resolution, **not proof of those songs' true tempos**.
- Search updates only when a correlation exceeds the current best by **0.025**. This is a hysteretic ascending search, not a global argmax: a later candidate with a slightly larger correlation can be ignored. No fractional-lag fitting or explicit octave disambiguation is implemented.
- A single phase is selected and an unchanging integer-period grid spans the window. There is no local phase-locking tracker or measured interval adjustment.
- Beat timestamps use `tick * 10 ms`, although 220 samples at 22,050 Hz are **9.977324 ms**. The nominal clock overstates elapsed time by about **0.2273%**, approximately 68 ms over a 30-second window. Flux indexing partially interacts with this clock error; it cannot be treated as a constant correctable offset.
- At true 140 BPM, nearest even lag 42 means a 420 ms beat rather than 428.571 ms: approximately **600 ms of accumulated error across 70 intervals**. At 128 BPM, 470 ms vs 468.75 ms already causes about 80 ms drift across 30 seconds even if the correct metrical level is selected. Other aliases can be substantially worse.
- Phase quality measures captured onset mass in ±3 nominal frames around the rigid grid, subtracts `7 / lag`, divides by 0.65 and clamps. Grid confidence multiplies correlation by square roots of phase quality and support stability. Offbeat/hi-hat/harmonic onsets contribute to the denominator; grid drift lowers capture. Low scores can therefore mean a bad grid **or** an overly pessimistic metric on a usable one.

The planner cannot responsibly stretch or align two tracks from the device's near-zero phase scores. Detailed key/energy processing does not improve rhythm resolution: SCAN and DETAIL use the same rhythm algorithm. Refining a nearly silent 5-second tail, as pair 1 does, cannot manufacture drum evidence.

### 3.2 Controlled comparison executed during this investigation

No production/test source was edited. Temporary harnesses outside the repository called the **actual compiled Tinium analyzer** and compiled the pinned BitChord `tempo_analysis.cpp` + `resampler.cpp` with host `g++ -std=c++17 -O3`. Both received the identical normalized mono Float32 origin at 22,050 Hz. The native harness measured the tempo/resampler subset, not full AnalyzeAudio or the JNI bridge. BitChord then used its own sinc resampler to its normal 11,025 Hz DSP rate. Tinium used SCAN, start 0, actual sample duration, unlimited analysis deadline. This isolates analyzer behavior from Android decoding, models, networking and queue scheduling.

Fixtures: 30 seconds; exact tempo; onset phase 0.137 s; continuous C-major sine bed plus deterministic accented 70 Hz kicks, alternating noise snares and eighth-note hats; seed 411. RMS 0.083–0.099, peaks <1. A 120-second 128 BPM case tests drift. There is substantial audio throughout, without a quiet outro. These are generated signals, **not a representative real-song benchmark**.

F1 uses one-to-one reference beat matches within **70 ms**, excluding the first/last second; no best-phase rotation or octave allowance. Median error reports matched beats only, so low-F1 cases must not be described as accurate because their few matches are close.

| True BPM / length | Tinium BPM / score | Tinium F1 / matched median error | BitChord DSP BPM / score | BitChord F1 / matched median error |
|---|---|---|---|---|
| 90 / 30s | 60 / .204 | .400 / 29.67 ms | 90.040 / .662 | 1.000 / 4.82 ms |
| 107 / 30s | 107.143 / .237 | 1.000 / 9.35 ms | 107.088 / .698 | 1.000 / 5.63 ms |
| 120 / 30s | 120 / .367 | 1.000 / 13.00 ms | 120.031 / .806 | 1.000 / 4.44 ms |
| 128 / 30s | 85.714 / .090 | .360 / 28.12 ms | 128.087 / .676 | 1.000 / 4.65 ms |
| 140 / 30s | 142.857 / 0 | .288 / 40.14 ms | 139.972 / .732 | 1.000 / 3.80 ms |
| 150 / 30s | 150 / .363 | 1.000 / 23.00 ms | 149.920 / .592 | 1.000 / 3.66 ms |
| 174 / 30s | 176.471 / 0 | .380 / 36.31 ms | **87.049 / .741** | **.672** / 4.33 ms |
| 128 / 120s | 85.714 / .080 | .252 / 34.25 ms | 128.002 / .697 | 1.000 / 4.05 ms |

Additional negatives: silence gives Tinium 0 BPM / 0 confidence; BitChord 117.454 / .0314. An unchanging C-major chord gives Tinium 157.895 / **0 confidence**, BitChord 161.427 / **.5410 confidence**. The latter approaches BitChord's .55 beat-match gate despite no ground-truth percussion. Native scores are not interchangeable with Tinium scores.

Six short grooves have accurate native BPM, but 174's half-time result is a real limitation. Tinium correctly times all reference beats at 107, 120 and 150 here, yet never reaches its .65 active-mix threshold. This demonstrates **both incorrect grids and confidence underestimation**, depending on material. Simply lowering every gate would also admit demonstrably wrong grids.

Single host-call timings, including native resampling: Tinium 194–244 ms for the seven 30-second grooves; BitChord 157–196 ms; the 120-second case 428 / 660 ms. JVM starts/call warmup differ; these are **not phone benchmarks or a fair performance ranking**. No ONNX inference or BitChord Android decoding was run.

Reproduction recipe: generate the described Float32 little-endian PCM; call Tinium `TrackFeaturesAnalyzer.analyze(pcm,22050,0,N*1000/22050,Long.MAX_VALUE,SCAN)`; call BitChord `Resample(pcm,22050,11025)` then `AnalyzeTempo(resampled,11025,N/22050,0)`; match against `0.137 + n*60/BPM`. Kick amplitudes are .65 on every fourth beat, .45 otherwise, envelope `exp(-32*t)` for 150 ms; snare .18 uniform noise, `exp(-45*t)`; hats .08 uniform noise, `exp(-160*t)` for 25 ms; bed .05 each at 130.8128/164.8138/195.9977 Hz. Beat positions use rounded sample indices. Keep these additional regression cases if Phase 2 is authorized.

### 3.3 Decoder/window failures are a separate problem

T-A `AndroidWindowDecoder.analyze/RangeSource.readAt`:

- Uses the existing authorized Media3/cache DataSource through MediaDataSource, with a range-capability probe for uncached remote input. Blocks are 64 KiB, LRU 4; fetched-byte caps **3 MiB remote / 12 MiB cached per window**.
- Requires extractor duration, max 30 minutes, start before end; limits windows to 32 seconds and rejects less than 4 seconds.
- Seeks to previous sync, chooses a software MediaCodec, queues compressed packets until the requested endpoint/EOS, drains PCM16/float, trims by PTS and releases resources in finally.
- Converts to 22,050 Hz by timestamp-index sampling/duplication. **There is no anti-alias filter.** With downsampling, high frequencies can fold into the analysis band. With PTS gaps, one sample is repeated through the missing region; this is not verified continuous decoded audio.
- A window more than half a second short throws `truncated-audio-window`. Both that case and the <4-second guard map to **INSUFFICIENT_AUDIO_DATA**. Current export records the category, not the original coded subtype, first/last PTS, decoded frame count, EOS cause, codec name, source MIME or bytes read. It cannot identify which guard failed.
- Duration is independently discovered by MediaExtractor; queue metadata is approximate. Pair 1 incoming metadata says 181000 ms but the extractor says 180621 ms; later pairs similarly substitute measured duration. These examples are inside the merger's 1-second tolerance and do **not** prove a duration defect.

Log examples: pair 5 scan tail at +91086 ms fails/retries at 164621 ms for 16 seconds, then fails again; its 6500 ms outgoing refinement at 174121 ms fails at +95878 ms. Final SAFE_FADE is specifically attributable to insufficient audio/refinement. Pair 10's incoming scan retry shrinks a 24000 ms region to 16000 ms and recovers at +239195 ms. Across all attempts, **three recoveries from five retries** are recorded.

A smaller window succeeding is consistent with endpoint/coverage/source-reading limitations, but not enough to distinguish codec/container seeking, cache holes, packet duration, truncated files or request behavior. These failures are not logged as byte-budget, source-not-seekable or unavailable-codec categories. It would be unjustified to assert those particular failures were caused by non-range HTTP or worker starvation.

Required Phase 2 telemetry: controlled source/codec/PCM encoding identifiers, requested vs actual start/end, decoded and accepted frames, duration provenance, EOS reason, bytes fetched, cache coverage, elapsed time and original internal error subtype. Never record signed URLs/headers. Test against local and fully cached copies before changing source-fetch policy.

BitChord's `AudioDecoder` is relevant reference material, not a guaranteed replacement: it returns actual first-output PTS and contiguous native-rate PCM before sinc conversion, but uses closest-sync seeking, reads output as PCM16, has no comparable explicit byte/deadline cap in its loop, and relies on BitChord's cache/source infrastructure. Preserve Tinium's resource bounds and float-format validation.

### 3.4 Rejection, content preservation and quiet-tail bias

T-A `DjCandidateSelector.select` requires confidence ≥.65, phase ≥.5, stability ≥.8 for active mixes; Tier A additionally needs ≥.7/.65/.9 and measured bars ≥.4. Every device window fails the first rhythm gate. The legacy early-outro path also needs approximately .6 rhythm/.4 phase. Existing scanning is already wide enough (up to 90-second tail/64-second head); **lack of any earlier candidate scan is not the primary failure here**.

The active selector additionally rejects summed spectral density >1.5. Tinium density is occupied FFT bins over an absolute magnitude .02 threshold, divided by 300 and clipped to 1. Device window summaries saturate: median 1.0, 56/61 ≥.75. This is a **strong hypothesis for a second gate after rhythm is fixed**, not an observed SPECTRAL_COLLISION rejection: the rhythm gate prevents reaching that test, and exported window averages are not candidate-local density curves. Validate density invariance across gain/windowing and real overlap quality instead of blindly raising its threshold.

`AutoMixPlanner.compatibleKeys` explicitly allows missing keys or minimum confidence <.2. Therefore **unknown keys are not globally treated as incompatible**. However, confidence is an uncalibrated top-two profile-correlation margin ×3, not evidence that a short noisy window has the correct key. `mergeTrackFeatures` takes an anchor/detailed window's key rather than proving agreement across the relevant overlap. Pair 1 outgoing scans alternate between C major and E minor estimates; low-energy tail estimates are weak. Six INCOMPATIBLE_KEY rejections do not establish six truly clashing song pairs. The active selector records harmonic rejection before candidate rhythm evaluation, so it can coexist with LOW_BEAT_CONFIDENCE.

Both Tinium's legacy sustained-outro rules and BitChord's native structure logic favor declining energy/quiet endings. Tinium has an active-rhythm path, but it is unreachable with these measurements. Porting BitChord structure verbatim could preserve this bias: `BuildStructure` models 4/4 eight-bar phrases and searches for reduced section/tail energy, not semantic chorus/vocal structure.

### 3.5 Execution, gain and cancellations

**Confirmed short-plan execution:** six starts are 17–40 ms after the planned player position, and all handoffs complete. This supports the controller's fallback scheduling/promotion path; commanded timestamps are not measured DAC or Bluetooth timing.

**Long-plan support in source:** `AutoMixController.driveBlend` uses elapsed outgoing player position over the selected duration, allows completion before natural file end, releases the outgoing deck, and retains the incoming deck. `AutoMixPlan` supports up to 48 seconds. `prepareIncoming` cues once through `setMediaItems(..., incomingCueMs)`, requires actual duration covering cue+overlap, and is requested up to 30 seconds before start. `promote` swaps the player/session rather than seeking to zero. Software Sonic/pitch 1 handles modest rate adjustments; the controller applies bounded drift nudges for rhythmic techniques. Existing generated tests demonstrate these logical and numerical paths, **not sample-accurate dual-AudioTrack synchronization on this phone**. This report contains no executed device long plan or nonzero incoming cue.

**Excess incoming attenuation:** pair 3 +80920 ms records midpoint outgoing .7071 and incoming .02151; pair 7 +168746 ms incoming .03132; pair 11's final recorded overlap gain remains small. In the legacy planner, `localOut/localIn` are floored at .005; both levels are scaled to `min(localOut,localIn)`. If the outgoing tail is near silence and incoming RMS ~.164, incomingLevel is approximately **.005/.164 = .0305**. Midpoint .707×.0305 matches pair 3. Detailed outgoing pair 1/3 tail RMS is only 0.00000793, so leveling to that tail is musically inappropriate. This explains coefficients without assuming double processing.

`ServiceMixDeck.process` applies deck gain via player volume × user volume × focus gain, while the PCM processor is set to **gain 1**, with bass/treble filtering separate. No duplicate deck-gain multiplication was found there. The .94 correlation guard is additional bounded headroom, not the main ~30-fold attenuation. At completion, recovery ramps the incoming level back to 1 over 3 seconds. An incoming fade can therefore remain barely audible during overlap and rise afterward, reinforcing the impression of an end-only handoff. This policy requires an independent gain correction even with perfect rhythm analysis. The log cannot establish actual post-EQ/output loudness or clipping.

**SEEK:** seven explicit cancellations are plausible deliberate seeks: e.g. pair 4 starts near 6886 ms, and pair 5 shortly afterward at 138300 ms; pairs 6/7, 8/9, 10/11 and 12/13 show similar jumps. Late worker cancellation events after CANCELED are expected cooperative teardown, not proof of an analyzer bug. However, `MusicService.onPositionDiscontinuity` maps **every non-AUTO_TRANSITION reason** to SEEK, including internal adjustments/removal/skip discontinuities. Programmatic seeks also exist (including SponsorBlock). The report omits the originating callback reason/caller, so it cannot prove all seven were user gestures. Incoming preparation normally occurs before the primary service listener attaches; promotion itself calls an item-transition callback, not an explicit seek. No evidence proves promotion caused these cancellations. Phase 2 should record the documented discontinuity reason and old/new item/position separately.

Scheduling verdict: windows usually finish in 1–3.5 seconds, source readiness is reported for all 14 pairs, no timeout/queue-full/deadline-missed event appears, and six decks prepare successfully. Current scanning/final planners share a bounded pool without waiting for unfinished futures inside planning jobs; completed windows remain cached/merged. **Premature cancellation or starvation is not established as the dominant cause in this log.** Test it across all three modes, but do not repeat the old cancellation fix or introduce extra unbounded executors.

## 4. Diagnostic root-cause matrix

| Symptom | Log/experiment evidence | Relevant Tinium code | Likely cause / classification | Confidence | BitChord solution? |
|---|---|---|---|---|---|
| No intelligent starts | 0 A/B; all windows max .290 | T-A TrackFeaturesAnalyzer.analyze; DjCandidateSelector.select | Measurement/gate mismatch; confirmed | High | DSP grid/normalization promising; model optional; adapter/calibration still required |
| Repeated discrete BPM | Only five exact 6000/lag values, all even lags | Analyzer attack indexing and lag loop | Integer lag resolution and sparse envelope; confirmed mechanism, real BPM unknown | High | Native fractional fitting, octave search, PLL address mechanism |
| Near-zero phase | Median .0143; 140/174 fixtures drift badly | Analyzer phase/grid/captured calculation | Fixed period plus onset/clock errors; confirmed limitations | High | Native tracking avoids rigid extrapolation; validate phase against known PCM |
| Correct BPM still rejected | 120/150 fixtures F1=1, scores .367/.363 | Analyzer confidence; active .65 gate | Uncalibrated/pessimistic score; confirmed synthetic case | High | New score must be calibrated, not copied or gates blindly lowered |
| Insufficient window data | 11 categorized failures / 4 attempts; 3 recoveries | AndroidWindowDecoder.analyze, RangeSource; controller retry | Decode/coverage/endpoint failure; specific subtype not retained | Medium for cause | Analyzer cannot fix; actual-PTS decoder/resampler design helps investigation |
| Key vetoes | 6 pair rejection categories; changing keys across windows | estimateKey; mergeTrackFeatures; compatibleKeys | Unvalidated short-window key confidence; hypothesis | Medium | Native key is also heuristic; model beat tracker does not fix keys; avoid blind JNI |
| Later spectral veto risk | Median density1; 56/61≥.75; gate sum1.5 | Analyzer occupied-bin proxy; DjCandidateSelector | Saturated, gain-dependent proxy may reject most pairs after rhythm repair | Medium | Do not map native normalized energy into this density metric |
| Near-end C plans | 5 legacy 2–3.5s plans; 1 explicit2.5s SAFE | AutoMixPlanner legacy; controller refinement | Failed rhythmic gates route to legacy/end fallback; confirmed | High | Better features can unlock active path; whole planner port unnecessary initially |
| Incoming gain .02–.09 | Pair3 midpoint .02151; almost silent refined tail | Planner quiet/localIn; mixGains; driveBlend recovery | Incoming matched down to silence; confirmed policy and matching coefficients | High | Separate gain-policy correction; neither native beat tracker nor ONNX solves it |
| SEEK interruptions | Seven cancellations; major position jumps | MusicService.onPositionDiscontinuity; controller.invalidate | Mostly consistent with seeks, but category conflates origins | Medium | Preserve cancellation; improve origin diagnostics independently |
| Analysis canceled after seek | Delayed worker FAILURE after CANCELED | workers.cancelAll, requestWindow | Expected asynchronous shutdown unless later applied to new pair | High for expected mechanism | Generation-safe native/model cancellation required |
| Preparation/timing bottleneck | No missed deadline; six starts17–40ms late | prepareIncoming, tick, driveBlend | Not established for current short plans; long mixes unverified | Medium | Retain controller; benchmark long overlaps/device lead |
| Native port fixes everything | 174native half-time; chord score.541; no Android decoder test | Not a Tinium defect | Unsupported assumption | High | Conditional integration, negative controls, source adapter and device gates |

## 5. BitChord native analyzer architecture

This directory is **more than an FFT frontend**: it includes a standalone DSP analyzer with tempo/beat tracking, key, energy and heuristic structure/cues. It is **not** a complete Android AutoMix subsystem, a trained meter/vocal detector, a decoder or a playback mixer.

All nine files are AGPLv3-or-later Orchard-derived components with SFG545 and Kushagra Singh notices. They use C++ standard-library math/containers and private radix-2 FFT implementations; no KissFFT, FFTW, Essentia, Rubber Band or ONNX calls are dependencies of this directory.

| Exact B path | Functions / responsibility | Input → output | Dependencies / runtime / independent / port decision |
|---|---|---|---|
| native/analyzer/audio_analysis.h | AnalysisResult, TempoResult, EnergyPoint, Phrase, MixCuePoint; AnalyzeAudio/AnalyzeTempo contract | Mono float PCM, rate, duration → owned vectors/strings/times in seconds | Shared declarations; no Android; retain selective contract |
| native/analyzer/audio_analysis.cpp | AnalyzeEnvelope, FindMixOutTime, AnalyzeKeyAndTimbre, BuildStructure, AnalyzeAudio | Whole-track mono PCM, normally11025Hz → RMS/peak/dynamics, chroma/key, normalized energy/bass curves, voice proxy, intro/outro/content end, candidate cues | audio_analysis.h + AnalyzeTempo; own4096 FFT; independent host-callable; selectively adapt features, do not import whole-track assumptions unchanged |
| native/analyzer/tempo_analysis.cpp | OnsetEnvelope, NormalizeEnvelope, Correlation, MetricalPrior, AnalyzeTempo, tracking lambda | Mono PCM/rate/duration/audible start → fractional BPM, interval, first beat, beats/downbeats, confidence | audio_analysis.h; own512 FFT; independent; **first port candidate** |
| native/analyzer/resampler.h | Resample contract, zero-crossing constant32 | Mono PCM + rates → mono PCM | No Android; required with implementation |
| native/analyzer/resampler.cpp | Sinc, Blackman, Resample; kernel table/interpolation | Arbitrary positive rates → anti-aliased output | Standard math, call-local allocations; independent; **first port candidate** after frequency/timing tests |
| native/analyzer/mel_spectrogram.h | BeatSpectrogram and ComputeBeatSpectrogram; model constants | Mono22050Hz → flat frames×128 tensor | Declare model frontend; optional with Beat This! |
| native/analyzer/mel_spectrogram.cpp | FFT, Slaney mel filters, periodic Hann, reflection padding, normalized magnitude/log1p | 1024FFT, hop441, 30…11000Hz →50Hz log-mel | Standard C++; frontend alone does not predict beats; optional gated port |
| native/analyzer/vocal_spectrogram.h | VocalSpectrogram/ComputeVocalSpectrogram contract | Planar stereo44100Hz → channels×2049bins×frames | Optional vocal-model frontend |
| native/analyzer/vocal_spectrogram.cpp | FFT, periodic Hann/reflection padding, unnormalized STFT magnitude | 4096FFT/hop1024 stereo → fixed tensor layout consumed by vocal model | Standard C++; no source reconstruction/inference; defer |

### What is really implemented

- **Rhythm DSP:** 512-point FFT/hop128 at11025Hz; positive log-magnitude spectral flux in full and <150Hz bands; subtract local mean over roughly±350ms, normalize and sqrt-expand. Tempo search70–200BPM reads at most180s; the onset envelope can cover1200s. Comb phase first uses30s; quadratic lag/onset refinement and a **two-pass phase-locked tracker** correct phase/interval with bounded drift. Confidence mixes search strength, phase evidence and candidate separation.
- **Downbeats:** bass/full onset comparison among four offsets, then every fourth tracked beat. No separate downbeat confidence/meter classifier; it always applies a four-beat grouping. Evidence-informed but **not reliable general meter detection**.
- **Structure:** 250ms RMS windows, percentile reference/noise, sustained onset/pickup, quiet tail/content end, interior silence/energy cliff; 32-beat phrase hypothesis with energy-based intro/body/outro/breakdown labels. This is not learned song segmentation.
- **Key:** 4096FFT, sparse ~650ms spectral measurements, pitch-class energy with Krumhansl–Kessler profiles, score-margin confidence. Requires validation; no trained harmonic model.
- **Vocal DSP:** spectral bands/flatness logistic proxy aligned to compact energy samples. Not a real voice detector.
- **Loudness:** explicitly **RMS dBFS minus .691**, despite the field name loudness_lufs. It is **not gated, K-weighted integrated LUFS**.
- **Band curves:** low curve is measured FFT energy below250Hz. In AnalyzeAudio, mid/high curves are initialized from RMS energy as fixed multiples; do **not** describe them as independent measured multiband spectra.
- **Resampling:** native windowed-sinc, antialias cutoff tied to output Nyquist. No PCM container decoding/conversion or audible time stretcher.
- **ML:** mel/STFT frontends only; inference is Kotlin ONNX. No native source separation, no native Beat This! model, no native WSOLA/Rubber Band execution.

Whole-track AnalyzeAudio must be supplied actual duration consistent with PCM. Passing a 30-second excerpt with the song's 216-second duration would create invalid structure/grid context. A window adapter needs actual sample duration, absolute offset and explicit coverage, not a renamed duration field.

## 6. BitChord's complete runtime pipeline

| Stage | Actual B implementation | Port implication |
|---|---|---|
| Queue/next item | app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt; PlaybackService.createCrossfadeController | Retain Tinium queue/repeat/shuffle policy |
| Resolve analysis source | app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt request/source resolution; B-C AutomixAnalysisSource.kt; data.sources.SourceResolver/TrackMatcher | Provider/cache coupling; do not import authentication/providers |
| Cache/rendition access | TrackAnalyzer + app/src/main/java/com/music/bitchord/playback/AudioCache.kt and cache read-ahead/rendition selection | Uses fully cached/local copy when possible; provisional head analysis, cache-growth retries, duration tolerance and persisted results |
| Decode | app/src/main/java/com/music/bitchord/playback/smart/AudioDecoder.kt decodeRegion/decodeRegionStereo/decodeRaw | MediaExtractor/MediaCodec, actual first PTS, native-rate mono/stereo chunks |
| PCM prep | TrackAnalyzer.structure/region/regionInputs; B-J TrackFeatures/MelSpectrogram; native Resample | DSP11025 mono, beat22050 mono, vocals44100 stereo |
| Native features | TrackFeatures.analyze → app/src/main/cpp/jni/analysis_jni.cpp → AnalyzeAudio | Whole-track structural pass for complete copies; no ML here |
| Beat model | B-J BeatTracker.kt + MelSpectrogram.kt; jni/mel_jni.cpp; beat_this_int8.onnx | ONNX beat/downbeat logits, peak picking/refinement, snapped downbeat subset, median-gap tempo/confidence |
| Vocal model | B-J VocalTracker.kt, native/vocal_spectrogram, jni/vocal_jni.cpp; vocals_umxhq_int8.onnx | Predicts vocals magnitudes, reduces to presence ratio; does not render separated stems |
| Merge/cache | TrackAnalyzer.analyze/region/mergeMasks; B-J AnalysisStore.kt | DSP features with head/tail model evidence; tail tempo precedence; neutral unmeasured voice mask .5 |
| Candidate/tier/scoring | B-C TransitionPolicy.kt rankMixInCandidates/rankMixOutCandidates/assessTransitionTier; TransitionPlanner.kt planTransition/planWsolaTransition | Candidate anchors, skipped-audible-content budget, key/vocal conflicts, intro preroll, tiered rhythmic/filter/plain plans |
| Align/execute | CrossfadeController arming/drive/filter and rate methods | Silent buffered incoming deck; cue/rate/filter/gains, phase correction, role swap instead of restarting |
| DSP/output | app/src/main/java/com/music/bitchord/playback/TransitionFilterProcessor.kt; shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/TransitionFilter.kt; PlaybackService.silenceSkippingRenderers/buildAudioSink | Per-deck filters/echo, internal processors, Media3/Sonic or AudioTrack playback parameters; **not native/analyzer** |
| Promote/continue | PlaybackService.adoptPlayer/setSessionOwner and onTrackBecameCurrent | Focus/listeners/session/queue/bookkeeping swap; retain Tinium's version |
| Cleanup | CrossfadeController.bail/release; TrackAnalyzer.release; trackers.release | Pool/model/cache/player lifecycle; adapt bounded cancellation, not wholesale service replacement |

The native structural pass normally analyzes the whole cached song, then model passes analyze the head/tail. Partial-cache handling has provisional head results and bounds/retry bookkeeping (`MIN_HEAD_SECONDS=12`, growth factor2, max short-decode attempts3, rendition-discard budget1). A complete global structural result is not available from only a tail window.

Beat This! input is mono22050, 128mel bands at50fps. `BeatTracker` uses1500-frame chunks, 6-frame margins and a29.76-second nominal window. It predicts separate beat/downbeat logits, selects local positive peaks with sub-frame interpolation, and snaps downbeats to detected beats. At least8 beats and plausible40–220BPM are required. Confidence is `.35 + .4*regularity + .25*sigmoid(peakStrength-.5)`, capped.95; **not calibrated to Tinium's phase score**.

Vocal model input is stereo44100, 2049 bins, 960 frames (~22.3s by hop duration; comments call it ~22.8s). TrackAnalyzer trims to fit. Output is reduced into a vocal-band presence curve; there is no inverse-STFT vocal stem used in playback. Regions with no model evidence fall back to DSP/neutral mask.

BitChord's pure planner includes WSOLA-named planning, but that does not mean the native analyzer executes WSOLA. Timing plans are rendered by its Kotlin/Media3 controller. Normal automatic overlap rails are4–12s; advanced calm overlaps can reach16s/32beats; its WSOLA-style planner caps16beats/16s. Native BuildStructure assumes32-beat phrases; it does not imply64-beat rendered blends. This is **not a drop-in implementation of Tinium's desired 32/64-beat long mixes**.

## 7. Dependency map and port seams

~~~mermaid
flowchart TD
    D[BitChord Android AudioDecoder / cache source] --> P[Native-rate mono or stereo PCM + actual PTS]
    P --> R[resampler.cpp: sinc conversion]
    R --> DSP[audio_analysis.cpp + tempo_analysis.cpp]
    DSP --> J[analysis_jni.cpp / TrackFeatures.kt]
    R --> MEL[mel_spectrogram.cpp / mel_jni.cpp / MelSpectrogram.kt]
    MEL --> BT[BeatTracker.kt + ONNX Runtime + Beat This! weights]
    R --> STFT[vocal_spectrogram.cpp / vocal_jni.cpp]
    STFT --> VT[VocalTracker.kt + ONNX Runtime + umxhq weights]
    J --> TA[TrackAnalyzer: merge / source bookkeeping]
    BT --> TA
    VT --> TA
    TA --> STORE[AnalysisStore]
    TA --> PLAN[TransitionPolicy / TransitionPlanner]
    PLAN --> CF[CrossfadeController]
    CF --> OUT[Media3 decks + Kotlin filters / output]
~~~

**DSP minimum:** audio_analysis.h, tempo_analysis.cpp, resampler.h/.cpp, validated JNI/adapter and Tinium PCM provider. Add audio_analysis.cpp only for measured key/envelope/structure features with correct excerpt semantics. No ONNX needed for DSP beats.

**Learned rhythm addition:** mel_spectrogram.h/.cpp, mel_jni.cpp or new guarded bridge, MelSpectrogram.kt, BeatTracker.kt, NativeAnalysisLibrary-style loader, ONNX Runtime Android and verified beat model. AnalysisStore is optional; Tinium's bounded cache can initially suffice.

**Vocal addition:** vocal_spectrogram.h/.cpp, vocal_jni.cpp, VocalTracker.kt, licensed model and stereo decode path; high memory cost. Defer until beat accuracy and decoder coverage are established.

Neither libffmpegJNI, librempeg, DSD/DST, spatial processors, BitChord source providers, Cast routing nor the entire shared Kotlin Multiplatform application is a DSP-analyzer dependency. B CMake builds DSD/DST separately, so copy **neither those targets nor their licensing obligations unnecessarily**.

Important seam defects/risks discovered:

1. **JNI key corruption:** B `audio_analysis.cpp::AnalyzeKeyAndTimbre` emits Unicode `C♯/E♭/F♯/A♭/B♭`. B `analysis_jni.cpp::AppendString` discards every non-ASCII byte. A C-sharp key becomes `C major/minor`; E-flat becomes E. Confirmed from the two implementations, independent of model accuracy. Use numeric tonic/mode or proper encoding; do not copy this serializer unchanged.
2. **Missing exported beat list/levels:** native AnalysisResult computes beats, chroma, loudness and peak, but analysis_jni/TrackFeatures.Features expose downbeats and selected curves, **not the full tracked beat vector or absolute level fields**. Tinium needs measured beat times, coverage, local rhythm confidence and level estimates. A custom adapter is necessary.
3. **Native safety:** JNI copies arrays into vectors; top-level guards are minimal. There are no comprehensive sample/rate/size/deadline checks, no C++ exception boundary or cooperative cancellation in these analysis loops. Kotlin runCatching cannot make an escaping native exception or signal safe. Add size/finite-value/rate guards, error transport and bounded cancellation before integration.
4. **Whole-track semantics:** normalized reference energy, intro/outro labels and phrases cannot be copied into Tinium window features as absolute RMS or global structure. Offset every event and identify measured vs inferred coverage.
5. **Thread/session semantics:** BitChord's serial analysis executor makes certain model-session reuse/reconfiguration assumptions safe; Tinium's4 concurrent jobs require explicit run/lifetime coordination. Do not close an ONNX session while another job uses it.



## 8. Tinium versus BitChord

| Feature | Tinium actual implementation | BitChord actual implementation | Recommended approach |
|---|---|---|---|
| Source access | MusicService.AutoMixServiceHost; authorized/cache DataSource, range probe, bounded reads | TrackAnalyzer/AudioCache, local/full/provisional renditions and source matching | Retain Tinium sources; adopt measured coverage/source-state concepts |
| PCM decoding | AndroidWindowDecoder; software codecs, PTS trim, float/PCM16, window limits | AudioDecoder; platform codec, contiguous chunks, actual first PTS, PCM16 readers | Separate DecodedWindow seam; test local/cache/remote equivalence; no blind decoder copy |
| Resampling | Timestamp-nearest sampling/repetition to22050; no antialias | Native Blackman-windowed sinc | Adapt/test sinc or equally licensed proven converter; keep audible playback unchanged |
| Onsets | Positive magnitude flux/total, sparse20ms writes on10ms array | Log-flux full/low bands, local-mean suppression/normalization | Native DSP offers meaningful advantage; negative controls required |
| BPM | Integer6000/lag; hysteretic search;60–182 | Fractional interpolation, metrical prior/octave alternatives;70–200 search | Native DSP backend benchmarked first; expose octave uncertainty |
| Beat grid | Fixed period/phase, nominal10ms timestamps | DSP PLL; optional learned beats with sub-frame peak timing | Retain absolute measured beat vectors/coverage; do not extrapolate unsupported regions |
| Downbeats/meter | Four-beat bass accents require repeat/contrast confidence | DSP unconditional four-offset hypothesis; ONNX downbeat head | Do not invent bar confidence from native list; learned tracker is optional stronger evidence |
| Phrases | Energy boundaries + conservative repeated bar evidence | 32-beat4/4 hypothesis/energy labels; planner uses intro/outro/downbeats | Preserve inferred/measured distinction; no semantic-phrase claim |
| Musical structure | Bounded head/tail windows; no global context | Whole-track envelope/key/structure for cached audio; model head/tail | Avoid whole-track remote decode; optional coarse local-only summary later |
| Key | Window chroma/profile margin, last detailed/anchor key | DSP chroma/profile margin; JNI strips accidental symbols | Calibrate trust/local agreement; numeric tonic serialization; neither is proven here |
| Vocals | Midband/flatness/bass-adjusted proxy | DSP proxy plus optional umxhq inference mask | Keep conservative uncertainty; defer model pending licensing/memory evidence |
| Energy/loudness | Absolute window RMS/peak; bass/density/percussion proxies | Normalized global reference curves, RMS-offset field, low FFT curve | Keep absolute levels and measured curves distinct; no fake LUFS |
| Candidates | DjCandidateSelector multi-pair active path; legacy quiet outro | TransitionPolicy cue ranking/skipped-audible budget and anchors | Retain Tinium selector initially; test with improved features |
| Incoming cue | Content protection; min32s or20% track; measured beats/sections | Native cue target≤36s/28%; planner skip cap25%, preroll before dominance | Adapt preroll/dominance concept selectively in Phase3 |
| Scoring | Rhythm/phase/stability/key/energy/vocal/density/skip/readiness | Tier policy, normalized octave tempo, key/vocal conflict, anchor ranking | Validate density/key vetoes; don't equate confidence scales |
| Beat alignment | Plan cues + Media3 position drift correction | Planned downbeats/cues + incoming phase-lock corrections | Keep controller; instrument rendered output latency and beat error |
| Tempo adjustment | Pitch1 software Sonic, plan±4%, small drift nudges | Kotlin/Media3 speed, Sonic/AudioTrack route; advanced speed-up≤10% | Preserve conservative±4%; avoid route/session changes |
| Bass handoff | Per-deck one-pole180Hz extraction/cuts with8ms smoothing | High/low-pass processor/filter rides, bass swap, optional echo | Current DSP is functional; compare auditory quality before selective filter changes |
| Mix DSP | Separate PCM16/float processors + AudioTrack deck volume, correlation guard | Separate decks/filter/echo, equal-power gains, headroom/loudness callback | Fix legacy silence-level matching; no need to import echo/output engine |
| Cache |64 window results keyed to rendition/start/length/detail/version; in-flight dedup | AnalysisStore persisted JSON, schema1, max2000 entries; head/whole states | Retain bounded Tinium cache; consider persistent features after accuracy established |
| Threads |1/2/4 bounded tasks, queue8, cancellations/deadlines | Single executor + ONNX intra-op1/2/4 | Preserve Tinium limits; native/model concurrency needs explicit budget |
| Recovery | One eligible16s retry, merge partial results, bounded coarse TierB recovery | Cached/provisional/head/whole states; retries on cache growth/rendition change | Retain existing successes; improve failure granularity and evidence-preserving retries |
| Android/output | Current1.7.1 dual-deck service, independent effect-session lifetimes |1.11.0 service, custom precision/float pipeline, shared session assignments | **Retain Tinium Android/EQ architecture**; no Media3 upgrade needed for analysis |

Meaningful native advantages are demonstrated for onset normalization, fractional tempo/grid tracking and timestamp accuracy on generated material. Optional ONNX supplies learned beat/downbeat evidence absent from Tinium, but no Phase1 experiment establishes its improvement on the attached songs. Native key/vocal/structure heuristics are not fundamentally guaranteed superior.

## 9. Android compatibility and execution assessment

### Native build

A future minimal Tinium target can compile selected source directly with existing NDK27.0.12077973 and CMake3.22.1/C++17, preserving Java21/AGP9.1.1. Pure analyzer code uses no Android framework calls; only its JNI/optional Android logging link is Android-specific. Verify all relevant ABI-named flavors: arm64-v8a, armeabi-v7a, x86 and x86_64 as needed for existing distribution. **BitChord's APK splits omit x86**, and copying its split settings would remove an existing Tinium flavor's native coverage. Confirm each resolved ONNX AAR ABI before enabling a model backend.

Tinium GMS/FOSS share playback code; DSP/JNI need no GMS APIs and can live in main sources. ONNX Runtime's MIT license is not itself incompatible with FOSS. Packaging and transitive notices still require checks in both variants.

B `app/src/main/cpp/CMakeLists.txt` adds `-Wl,-z,max-page-size=16384` to native libraries, `-O3` and hidden visibility, deliberately avoiding fast-math. Preserve IEEE behavior and explicit16KB ELF LOAD alignment with NDK27; APK ZIP alignment is a separate AGP responsibility. Validate with readelf and Android `zipalign -P 16`, including every prebuilt dependency's .so, and test a16KB-page emulator/device. The host DSP experiment is **not** an Android ABI or16KB validation.

Prefer source-built, revision-pinned DSP over copied BitChord .so files. It avoids hidden ABI/toolchain/notice drift and permits sanitizer builds and cancellation changes. A future model runtime may use a pinned Maven AAR only after ABI/page-size/notice inspection; this report did not inspect its packaged native libraries.

### Memory, safety and lifecycle

Calls own mutable vectors; DSP is reentrant and spawns no threads itself. Its allocations/math are offline work, never an audio callback. Native full-track analysis has substantial allocations proportional to samples/envelope. Use bounded windows unless a separately budgeted local-file structural pass is justified.

Required guards: null/length/rate/channel/encoding validation, finite PCM or documented sanitization, checked allocation arithmetic, sample/duration consistency, output-time monotonicity and bounds; C++ exception→structured failure at JNI; independent shared cancellation state safe across buffer lifetime; no uncaught exceptions crossing JNI. Native interruption must be cooperative—Future.cancel(true) alone does not stop a long C++ loop.

ONNX sessions must be created once per configured backend, validated by model hash/schema, leased during runs, and closed only after runs have stopped. Mode changes invalidate obsolete jobs and wait for owned native/model work without blocking UI/audio threads. BitChord's lazy library load caches initial failure; a Tinium adapter should make unavailable native support an explicit recoverable fallback rather than a playback crash.

### Preserve playback and external EQ

T `playback/effects/AudioEffectSessions.kt` tracks actual valid IDs per PRIMARY/PREPARED/FADING deck, opens replacements before closing old IDs, and reference-counts IDs. `AudioOutputLifecycle.kt` handles actual AudioTrack initialization/release; independent observers survive metadata listener swaps. Keep these components and the per-deck internal EQ processors.

B `PlaybackService` assigns `sparePlayer.audioSessionId = exoPlayer.audioSessionId` and has one advertised effect-session ID in `updateAudioEffectSession`. Its output/session assumptions differ from Tinium's recent external-EQ fixes. **Do not port this session strategy.** Pure analysis integration has no reason to change the output path, focus, offload policy, internal EQ, Bluetooth route or session broadcasts.

Tinium dual decks can logically render long overlaps with bass/filter/tempo processing and early outgoing release. They are not sample-locked, and player position is not a hardware presentation timestamp. Verify renderer/output latency, rate settling, simultaneous PCM and device session attachment before claiming DJ synchronization. A shared-output mixer is a possible later architecture, but no evidence here justifies that rewrite.

## 10. Licensing and redistribution audit

This is an evidence-based technical license inventory, not a declaration that existing distribution is already compliant.

| Component / evidence | License finding | Conditions / blocker |
|---|---|---|
| T root LICENSE | GPLv3 text with proprietary Musixmatch linking exception and express copying/adaptation/redistribution prohibition for that module in another fork/repository | **Potential distribution blocker: permission status was not established by this audit.** Establish permission/applicability or remove/exclude the restricted module under an authorized separate task; exception cannot waive an imported AGPL author's requirements |
| T lyricsProvider/src/main/kotlin/com/music/musixmatch/Musixmatch.kt and models/Models.kt | Explicit VIVI-LYRICS proprietary notices: viewing only; copying/modification/redistribution/reuse prohibited, exclusive official vivimusic use | Module is included through app implementation(project(":lyricsProvider")); owner permission status unknown. This strengthens the distribution-review gate; no module changes in Phase1 |
| B root LICENSE / README | GPLv3 overall | Preserve GPL source/distribution obligations; file notices control relevant components |
| All native/analyzer .cpp/.h | AGPLv3-or-later, Orchard-derived; ©2026 SFG545, BitChord adaptation ©2026 Kushagra Singh | Preserve notices, AGPL text, modifications/revision/source and corresponding-source access; not GPL-only |
| app/src/main/cpp/jni/{analysis,mel,vocal}_jni.cpp | AGPLv3-or-later with same Orchard/BitChord notices | Same obligations; adapting namespace/JNI does not change license |
| B-J TrackFeatures.kt, MelSpectrogram.kt, BeatTracker.kt, VocalTracker.kt | Orchard AGPLv3-or-later headers | Any copied/adapted implementation remains subject to those conditions |
| B-C TransitionPlanner.kt, TransitionPolicy.kt, TrackAnalysis.kt | Orchard AGPLv3-or-later | Planner copying expands AGPL-derived footprint; not necessary for initial backend |
| B app/src/main/java/com/music/bitchord/playback/smart/{TrackAnalyzer,AudioDecoder}.kt | Explicit GPLv3-or-later BitChord notices; AudioDecoder describes Orchard platform-path reference | Preserve notices; audit any selected portions rather than presume every related file is GPL |
| AnalysisStore/NativeAnalysisLibrary/AnalysisLog, performance enum, CrossfadeController/TransitionFilterProcessor | No separate file-level license header found; repository GPLv3 applies absent another grant | Keep copyright/attribution, check provenance before copying; no wholesale port proposed |
| Beat This! upstream source **and published weights** | [Pinned upstream README license section](https://github.com/CPJKU/beat_this/blob/b95c8ab0c58c2d9fcfd40508ae8dffbc05ac4f5c/README.md#license) expressly says MIT; LICENSE verified | Include MIT notice; upstream warns some training files have limited rights. Identify source checkpoint and conversion recipe for the particular ONNX graph |
| B beat_this_int8.onnx | Actual4533252-byte graph, SHA256 **9dc29f1fcd713d18f48a2755109fce01429ba6d1639607af8ae5c7449b47070f**; wrapper claims MIT | **Converted asset provenance not established**: no checked-in conversion manifest/checkpoint hash/license bundle found. Upstream MIT grant is encouraging, but doesn't prove this exact graph's derivation |
| open-unmix source | [Pinned upstream LICENSE](https://github.com/sigsep/open-unmix-pytorch/blob/fb672c9584997c2b05e148eeaa65b4c23ed4693b/LICENSE) MIT verified | Code license does not establish every checkpoint's license |
| B vocals_umxhq_int8.onnx |9054206-byte graph, SHA256 **a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb**; VocalTracker comment claims umxhq weights MIT, confirmed at Zenodo | **Unverified independently:** Zenodo record3370489 inaccessible; exact converted graph provenance missing. Do not ship on comment alone |
| Other open-unmix weights | Pinned README explicitly says default **umxl** weights are CC BY-NC-SA4.0 | Not interchangeable with claimed-MIT umxhq; exclude noncommercial weights from unrestricted GPL/AGPL distribution |
| ONNX Runtime1.28.0 | [Tagged MIT LICENSE](https://github.com/microsoft/onnxruntime/blob/v1.28.0/LICENSE) and325054-byte ThirdPartyNotices.txt inspected for existence | Retain applicable notices and audit resolved AAR/transitive binaries; MIT is runtime license, not model license |
| Native DSP dependencies | No additional DSP library dependency in analyzed directory; private FFT/resampler implementations governed by source notices | No Rubber Band/FFTW license or size obligation introduced by this subset |
| DSD/DST and librempeg/FFmpeg | Separate unrelated BitChord targets/dependencies; DST LGPL2.1 notice exists | **Exclude**; not necessary for analysis and not audited for incorporation |

GPLv3 section13 permits combination with AGPLv3, while AGPL obligations remain applicable to the AGPL-covered portion/combination, including the remote-network-interaction provision where applicable to a modified covered program. Android APK distribution still needs required notices and **complete Corresponding Source**, including modifications, JNI, build/install scripts and required dependency information. Merely crediting BitChord does not satisfy this. Nor does “FOSS flavor” resolve proprietary-module rights.

Before implementation/distribution, record the exact adapted files/revision and original authors; retain upstream VIVI attribution; provide GPL/AGPL/MIT notices and source distribution instructions. Decide how the existing Musixmatch restriction is resolved. For ML, obtain original checkpoint identifiers/hashes, verified license evidence, reproducible ONNX export/quantization settings and graph hash. If provenance cannot be established, generate a legally verified conversion from a licensed checkpoint in a **future approved task**, or use DSP only.

**Licensing conclusion:** identifiable GPL/AGPL source grants make a selective DSP port legally possible **under their conditions**, subject to verifying permission/applicability of Tinium's existing restricted-module exception. Exact vocal-weight permission is an unresolved blocker. Beat weights have a verified upstream MIT grant, but the shipped converted graph still needs provenance. No component was incorporated in Phase1.

## 11. Four possible strategies

| Option | Required scope | Complexity / integration risk | Expected benefit and fallback reduction | Licensing / performance / size / maintenance | Required validation |
|---|---|---|---|---|---|
| **A — Native DSP only** | Selected native tempo/resampler, optional envelope/key, guarded JNI and data adapter; retain planner/controller | Medium; excerpt semantics and confidence fields require work | Strong initial BPM/grid improvement on controlled material; cannot fix missing PCM, gain, density veto, actual vocal/meter ambiguity | AGPL source obligations; small source-based library, size unmeasured; no model/runtime cost; maintain cancellation/calibration | Same-PCM accuracy and negative controls, decoder correctness, all ABIs, confidence calibration, native safety |
| **B — DSP + necessary higher-level analysis** | A plus beat-evidence validation/local mapping, optional BeatTracker/MelSpectrogram/Beat This! ORT; defer vocals unless justified | Medium–High; model lifetime, mapping and budget matter; no service replacement | Most likely sufficient musical-feature improvement; learned downbeats if proven; independent decoder/gain work still needed | AGPL wrappers; MIT runtime/verified models; beat asset4.323MiB + runtime, memory/inference overhead unknown; cache/model-version upkeep | A plus model mel parity, licensed graph reproducibility, real-song comparison, thread/RAM/CPU/device limits |
| **C — Analysis + planner** | B plus selected TransitionPolicy/TransitionPlanner/TrackAnalysis concepts or code | High; global energy, tier names, cue/dominance semantics differ | Could improve cue/preroll/scoring; does not automatically increase overlap length, fix decoding or output timing | Larger AGPL-derived surface; native/model footprint similar toB; substantial planner maintenance | Adapter+candidate equivalence, skipped-content limits, duration/cue/rate semantics, device musical evaluation |
| **D — Full/hybrid subsystem** | Analysis/planning plus controller/filter/output/service adaptations | Very High for full port; High for a precisely limited hybrid | Could supply richer filters/latency handling; no evidence it fixes current sources or Poweramp; current BitChord output session strategy differs | Broad GPL/AGPL surface, models/runtime, source/cache/output coupling, Media3 version mismatch, largest maintenance/regression risk | Full playback/source/session/queue/focus/route/EQ matrix and real-device output capture |

A is a useful first proof and may substantially reduce rhythm-caused fallback. It is not sufficient on its own for all diagnosed failures. B is the **recommended architectural scope**, implemented incrementally with a model-free A checkpoint first, and optional learned rhythm only if representative benchmarks show that DSP does not meet accuracy/meter requirements. Do not confuse the normal JNI/data adapter required byA with copying all of BitChord's TrackAnalyzer.

## 12. Recommendation and go/no-go decision

**Retain Tinium's planner/controller first; selectively replace how they receive evidence.** Build a backend interface and accurate PCM window contract before choosing the final analyzer. Run the DSP candidate on the same decoded PCM as the legacy analyzer in development-only offline/shadow experiments. Ordinary playback runs **one** selected backend, never two unnecessary analyses.

Reasons:

- The log locates the dominant barrier before execution: unusable confidence/phase output. A planner port cannot repair it.
- Generated PCM proves a native DSP advantage but also octave/negative-control weaknesses.
- BitChord's ONNX head predicts actual downbeats rather than deriving all bars from four-beat modulo; it is a justified **conditional** next candidate.
- Native structure/key/vocal proxies do not remove the need for validation. Wholesale whole-track analysis is inappropriate for Tinium's bounded streaming windows.
- Tinium already has long-duration dual decks and independent output-session tracking worth preserving.
- B's planner caps and output session policy are not aligned with the requested long mixes/external-EQ preservation.

**Not sufficient:** copying native/analyzer alone without a PCM provider, safe JNI/Kotlin adapters, calibrated local beat/phase/downbeat evidence, correct time offsets/coverage and independent gain/decoder/planner corrections.

**Not initially necessary:** the entire BitChord TrackAnalyzer, AudioCache, source matching, TransitionPlanner, CrossfadeController, PrecisionAudioSink, vocal model, echo DSP, FFmpeg/DSD or a new player engine. Add only components that clear a measured gate.

## 13. Proposed Tinium integration architecture

~~~mermaid
flowchart LR
    S[Tinium authorized source / cache / local file] --> D[PCM WindowDecoder: actual PTS and coverage]
    D --> R[Validated mono/stereo PCM and sinc resampling]
    R --> E[Replaceable AnalysisEngine]
    E --> L[Legacy Kotlin baseline - development comparison]
    E --> N[BitChord-derived bounded native DSP]
    E --> M[Optional licensed Beat This! backend]
    L --> F[Local musical evidence + provenance + uncertainty]
    N --> F
    M --> F
    F --> C[Bounded versioned feature cache]
    C --> P[Tinium planner: candidate pairs / trusted constraints]
    P --> PC[Tinium AutoMixController + existing Media3 decks]
    PC --> O[Per-deck DSP / Sonic / EQ session lifecycle / AudioTrack]
    E --> X[Existing sanitized diagnostic recorder]
    P --> X
    PC --> X
    X --> UI[StateFlow / marker / Stats / log export]
~~~

Proposed interfaces/files below are **future work, not created**:

| Proposed seam | Responsibilities |
|---|---|
| AnalysisWindowDecoder / DecodedWindow | actualStartUs, actual frame rate/encoding/channels, samples, accepted intervals, source identity/duration provenance, EOF/coverage/error; no implicit fabricated samples |
| AnalysisEngine | analyze bounded window off main; injected deadline/cancel token/engine version; explicit success/partial/failure; swappable legacy/native/model implementations |
| BeatEvidence / MusicalFeatures adapter | absolute beat/downbeat times, local tempo/stability/onset alignment, reliability provenance and uncertainty; no arbitrary phase confidence copied from unrelated score |
| NativeAnalysisBridge | owned bounded buffers, safe JNI error boundary, cancellation/lifetime, exported tracked beats and absolute levels, numeric key data |
| Existing TrackFeatures/SectionAnalysis conversion | maintain real coverage and local windows; adapt minimum fields, not incompatible normalized curves or whole-song labels |
| Existing AnalysisWorkers/cache |1/2/4 bounded tasks, queue8, in-flight dedup, same engine per window; algorithm/model version in key; no persisted uncertain result treated as final |
| Existing planner/controller/status/recorder | integrate only after feature gate; preserve generation/pair validity; record backend, actual coverage and controlled failure subtype |
| Existing effects/output | remain untouched for analysis integration; later gain fixes stay within AutoMix policy |

Cache identity should include stable recording/rendition identity, duration/cut validation, window offset/length, engine/model/schema version and PCM preprocessing version. Never log signed URLs. Preserve existing64-result memory bound initially; persistence is optional after trustworthy outputs.

On seek/next-track/source changes, cancel only owned generation work; reject stale completions at submission and application boundaries. Keep unrelated future queue growth from discarding the same current/next pair. A decoded partial window can remain evidence if its coverage is known; no inferred grid outside it may authorize a mix. Failed native/model work maps to controlled existing failure codes with subtype/stage, retains independent successes and falls back without interrupting playback.

### Worker-budget preservation

Efficient/Balanced/Performance continue meaning **1/2/4 concurrent analysis workers**, defaultBalanced. Native DSP does not spawn internal threads, so this maps naturally. MediaCodec/network/GC/playback threads remain outside that count and must be documented.

For optional ORT, simplest initial policy is intra-op **1**, inter-op **1**, sequential execution where supported, with each run admitted by the same analysis-worker budget. Disable excess spinning/retained arenas only after measuring supported runtime settings. Do not allow4 jobs each with4 ORT compute threads. Alternative shared token-budget allocation is possible, but no reason to import BitChord's mode-as-inference-threads unchanged. Session execution/reconfiguration must be leased/synchronized; mode changes cannot close in-use graphs. Measure actual runnable workers, not just executor settings.

## 14. Performance, memory and APK-size considerations

No Android benchmarks or final APK size estimates are claimed.

| Item | Evidence or calculable bound |
|---|---|
| Model-free DSP library |2096 lines across native analyzer headers/sources; selected subset smaller. Actual per-ABI ELF/compressed APK growth **unknown until NDK build** |
| Mono32s PCM at22050 |2,822,400 bytes (~2.69MiB), before copies |
| Mono32s DSP PCM at11025 |1,411,200 bytes (~1.35MiB), before copies |
| Stereo32s PCM at48000 |12,288,000 bytes (~11.72MiB);4 such jobs already ~46.9MiB before other allocations |
| Beat mel30s |about1500×128×4 =768000 bytes per flattened tensor; JNI/ORT copies and graph working set additional |
| Beat graph in B checkout |4533252 bytes =4.323MiB; this is asset size, not total APK growth |
| Vocal graph |9054206 bytes =8.635MiB; both graphs total12.958MiB before compression |
| Vocal tensor |2×2049×960×4 =15,736,320 bytes (~15.01MiB) **per input/output-sized tensor**, plus stereo/resampled PCM, JNI copies and graph working set |
| ORT AAR/libs |Not downloaded/evaluated here; ABI-specific size/page alignment unknown. Must measure compressed universal vs per-ABI packages |
| Whole-song PCM |210s mono48000 float ≈40.32MB, plus resampled/copies; avoid mandatory full-track remote analysis |
| Host measurements |Section3.2 only; not phone CPU/battery or warmed benchmark |

Use bounded coarse scanning and selected refinement; aggregate decoding regions when safe and avoid rereading overlapping ranges unnecessarily. Model input frontend parity is a prerequisite to meaningful inference. Streaming budgets and deadline behavior must survive the port; native speed does not justify unlimited remote bytes or full-song transfers.

Measure elapsed time, CPU time, peak Java/native/direct memory, queue occupancy, bytes read/cache hits, cancellation latency and sustained battery/thermal behavior under all modes. Tune concurrency by measurement rather than assuming Performance always improves readiness.

## 15. Analyzer and decoder validation plan

### Executed Phase1 checks

- Recomputed attached-log metrics, event/tier counts, every actual plan/cue/start and recoveries.
- Inspected pinned native/JNI/Kotlin/Android source and file-level licensing.
- Host-compiled and exercised actual native tempo/resampler; compared actual Tinium analyzer on8 generated rhythmic cases and2 non-rhythmic controls.
- Ran repository command:

~~~bash
source /workspace/onboarding/activate.sh
bash gradlew :app:testArm64GmsDebugUnitTest :app:testUniversalFossDebugUnitTest \
  --tests 'com.music.vivi.playback.automix.*' \
  --tests 'com.music.vivi.playback.effects.*' --no-daemon
~~~

Build successful in30s. **FOSS executed111 focused tests, zero failures/errors/skips. GMS task was up-to-date**, with matching111 focused tests in its cached passing results. Compilation prerequisites were up-to-date. This is not a newly built APK or verification of decoder/device audio. No complete BitChord build, ONNX inference, Android instrumentation, native Android packaging or listening test was run. Documentation-only scope does not justify APK packaging or unrelated full lint.

### Required accuracy gates before production replacement

1. **Common PCM corpus:** inject identical original PCM into both backends, with declared resampling and actual offset/coverage. Use multiple tempos including90/107/120/128/140/150/174, fractional tempo, phase offsets, swing/backbeat, variable tempo, sparse rhythms and complex generated instrumentation; include the newly failing cases from3.2.
2. **Reference metrics:** one-to-one beat precision/recall/F1 at70ms and stricter30ms; median/p95 timing error, accumulated drift, exact-tempo error and separately octave-tolerant tempo error. No best-phase rotation concealing wrong cues. Downbeat/meter accuracy is a separate metric.
3. **Negative controls:** silence, sustained chords, noise, speech-like material, non-rhythmic passages and ≤4-second excerpts. A non-rhythmic window must not authorize beat matching merely because a prior supplies BPM or periodic numerical FFT variation scores highly.
4. **Preprocessing parity:** mono/stereo at8/11.025/22.05/44.1/48/96kHz; channel cancellation; PCM16/float, compressed decoder output and discontinuities. Test sinc passband/alias suppression/latency/sample count against analytical tones and reference converters.
5. **Decoder correctness:** local permitted files first, then fully cached equivalent containers, then range-HTTP fixtures. Verify seek-before/after sync behavior, first/last PTS, trim, encoder delay/padding, malformed/unknown durations, real EOS vs interrupted input, output-format change, cache holes and byte budgets. Different failure subtypes must remain distinguishable.
6. **Partial/cancel/retry:** delayed decoding, one failed window, incomplete coverage,1/2/4 workers, saturating queue8, source/next changes, seek and mode change. Completed valid results survive; obsolete native/model jobs stop within a measured budget and cannot publish a stale plan.
7. **Calibration:** choose trust gates from accuracy/false-positive measurements; native .65 and legacy .65 are not the same evidence. Check confidence reliability against observed timing errors and local rhythm stability. Never synthesize phase/downbeat confidence just to satisfy an existing field.
8. **Native/runtime:** sanitizer tests, checked invalid input, low-memory behavior, stress/release concurrency, every ABI, deterministic outputs within numeric tolerances,16KB ELF/APK checks and optional model hash/frontend/output-shape verification.

**Proposed acceptance targets, not achieved real-song results:** exact steady generated tempo within1% (octave ambiguity explicitly reported); beatF1≥.95 at70ms and p95 error≤30ms on approved stable rhythmic fixtures; no cumulative drift above a materially significant fraction of a beat; no confident beat-match authorization on negative controls. Ground-truth ambiguity must be recorded, not hidden by a single aggregate.

### Permitted real-song evaluation

The owner can supply local personally permitted audio outside the repository; keep copyrighted recordings out of Git. Build a fixture manifest with content hash, format/rate/channel/duration and locally annotated representative head/tail beats, vocals and safe cues. Use diverse genres, live/drifting tempo, quiet intros, dense percussion and songs from the problematic pairs where available.

Measure separately:

- successful decoded windows / requested executable windows, excluding deliberate cancellations;
- exact/partial coverage and decoder failure subtype by source/container;
- tracks/windows with **accurate usable** grids, not simply scores above a gate;
- tempo/phase/downbeat precision vs reference, key/vocal reliability where annotated;
- pairs yielding validated TierA/B candidates and actual executions;
- unjustified vs legitimate TierC fallback rates;
- analysis wall/CPU time, peak memory, remote bytes, retries and deadline misses.

Compare against the same Tinium legacy output and unchanged source path. Representative musical data, not simple click fixtures alone, decides whether adding Beat This! is warranted. If native DSP remains octave-confused or false-positive, evaluate the licensed learned tracker on the same PCM before promoting it.

## 16. Galaxy S25 Ultra validation plan

Use a future separately approved private test build; Phase1 creates none. Preserve both installed identity and signing/update compatibility.

1. Enable AutoMix under Player and audio → Player; beginBalanced, then repeatEfficient/Performance. Enable existing transition marker and Stats for Nerds in Appearance.
2. Use permitted downloaded/local rhythmic tracks first, then the same tracks cached/streamed. Include a sustained active instrumental outro and an incoming intro whose best cue is later than zero.
3. Let tracks play without seeking for one baseline session. Separately seek/reorder/skip/shuffle to test cancellation. Export after each session by long-pressing existing Stats for Nerds; distinguish deliberate seek sessions from uninterrupted playback.
4. Verify status progression: analysis → confirmed plan → preparing → actual ready → intelligent mixing → completed. A high beat score/candidate is not a started intelligent mix. Record Tier, technique, planned/actual outgoing start, incoming cue, overlap and controlled fallback reason.
5. Require at least one supported active-to-active16/32-beat mix beginning20–40 seconds before end on appropriately annotated material. Test64beats only where true coverage, bars and sufficient music permit it. Confirm both songs audibly overlap, the incoming cue is retained after promotion, and outgoing audio stops after the planned overlap.
6. Compare measured planned vs actual timing from export; targets for prepared transitions can be ≤100ms player-position lateness, but output beat error needs loopback/recorded audio with known fixture beats. Bluetooth latency must not be conflated with analysis timestamps.
7. Repeat with Poweramp off, then Poweramp on/internal EQ off; separately test internal EQ on. Check normal, Crossfade and multiple AutoMix transitions without pause/resume workarounds. Preserve session-event ordering; effect attachment remains a device/third-party validation.
8. Test speaker/Bluetooth and wired output if available, screen off, background/session controls, interruptions, restart, pause/resume, repeat, unavailable next track, source errors and queue growth.
9. Collect at least20 uninterrupted supported pairs plus deliberate unsuitable controls. Report eligible-pair denominator, usable measured grids, actual A/B executions, correct later cues and categorized fallbacks; don't promise every song pair mixes.
10. Inspect battery/thermal behavior over30–60 minutes in all modes and long sequences for leaked decks/native sessions. Verify timeline/stats/export describe the actual plan, not an earlier candidate.

The exported report should additionally carry backend/version, coverage/PTS and error subtype in Phase2; no titles, signed URLs, credentials, headers or PCM. Existing export already gives useful starts/completions/gains. It cannot currently measure actual phase at speakers, post-EQ amplitude or the originating kind of SEEK.

Device acceptance is improved accurate usable grids on known rhythmic material, fewer **unjustified** fallbacks, fewer decoder failures specifically where decoding is changed, correct actual cues/durations/early starts, normal queue continuation and stable EQ/output. Listening confirms quality separately from numeric tests.

## 17. Phase2 roadmap — analysis integration

**Approval and licensing gates come first.** No step below is implemented in this PR.

| Focused PR/checkpoint | Exact intended modules | Completion gate / rollback |
|---|---|---|
| 2.1 “Introduce replaceable AutoMix analysis and PCM coverage contracts” | New proposed T-A AnalysisEngine/DecodedWindow seams; wrap existing TrackFeaturesAnalyzer; split AndroidWindowDecoder decode from analyze; preserve worker/controller APIs | Legacy outputs/playback unchanged; common-PCM corpus includes current failing tempos; no native dependency yet. Roll back seam if behavior changes |
| 2.2 “Validate AutoMix window decoding and analysis resampling” | AndroidWindowDecoder/AnalysisSource adapter; optional licensed sinc resampler; controlled decoder metadata/subtype diagnostics | Local/cache/range corpus passes PTS/coverage/format/EOS/alias tests within existing budgets; no silent sample filling disguised as data. Keep old source path available for investigation |
| 2.3 “Add guarded native AutoMix rhythm backend” | B native/analyzer/audio_analysis.h, tempo_analysis.cpp, resampler.h/.cpp; selective audio_analysis.cpp only if justified; new guarded JNI, Kotlin backend and minimal CMake/Gradle target | GPL/AGPL rights/notices resolved; source-built allABIs/16KB; cancellation/native safety and same-PCM accuracy gates pass. Backend can revert to legacy without touching queue/EQ |
| 2.4 “Calibrate local musical features and compare optional learned beats” | Feature adapter; optional B mel_spectrogram.h/.cpp, MelSpectrogram/BeatTracker concepts/wrappers, licensed reproducible beat graph, pinnedORT | No invented phase/bar confidence. DSP evaluated on permitted real music; only add model if it improves accuracy/fallback eligibility within measured CPU/RAM/size. Block asset distribution until provenance cleared |
| 2.5 “Select validated analysis backend and version caches” | AnalysisWorkers/cache keys and diagnostics; existing status/model conversion | One backend per ordinary window; fixed1/2/4 concurrent worker budget includingORT; bounded memory/bytes/time, old cache invalidated; private device tests pass. Revert backend selection if quality or stability worsens |

JNI/data contracts must use actual float PCM rate, explicit mono/stereo layout, actual window sample duration and absolute offset. Export measured beat vectors, interval/stability/uncertainty, downbeat evidence with provenance, absolute RMS/peak and optional trusted key/vocal/band features. Preserve missing values explicitly.

Benchmark first on host equivalence, then Android emulator/instrumentation for decode, then GalaxyS25 for runtime/output. Record Java/native/direct peak memory and multiple-run distributions, not one timing. New native/ONNX resources must release on service destruction, disabled AutoMix, source invalidation and safe mode reconfiguration.

**Exact first implementation step after approval:** PR2.1's replaceable `AnalysisEngine` plus `DecodedWindow` contract and failing common-PCM regression corpus, with the current Kotlin analyzer as the only production backend. This separates bad decoded input from bad analysis before any port and gives a reversible comparison point.

## 18. Phase3 roadmap — planning and execution

Proceed only once Phase2 demonstrates accurate local beat evidence and validated decoding.

1. **Trusted candidate inputs:** adapt Tinium's existing planner to calibrated local tempo/phase/coverage. Revisit spectral-density saturation and short-window key vetoes with labeled examples, not blanket threshold reductions. Missing/uncertain keys must stay neutral. Expose rejection subtypes and real coverage.
2. **Joint cues and structure:** retain current multi-pair selector; optionally adapt BitChord's ranked anchors, audibly skipped-content budget and preroll-to-dominance concept. Its whole-track32-beat structure is a hypothesis, not an automatically trusted phrase boundary. Do not initially port the full planner; consider a narrow planner PR only if feature improvements leave measured candidate failures.
3. **Gain correctness:** fix legacy silence-level matching independently. Match active overlap levels using trustworthy absolute PCM statistics with bounded attenuation/headroom; silence in the departing deck is not a target loudness for the arriving song. Test full overlap plus3s recovery, user/focus volume and internal/external EQ. Neither false LUFS nor normalization from a different rendition should drive gains.
4. **Long overlap execution:** retain dual decks and selected duration. Verify16/32/64beat plans, incoming cue accuracy, prep lead, output buffering/filter latency and clock drift; finish before natural EOF where planned. No silent shortening/restart/second queue advance. Add output-capture tests beyond fake play calls.
5. **EQ/continuation:** retain AudioEffectSessions/AudioOutputLifecycle and per-deck internal EQ; preserve focus/MediaSession/notification/queue after promotion. Do not share session IDs merely because BitChord does.
6. **Fallback honesty:** distinguish insufficient/source/decode/low-rhythm/incompatible/music/coverage/prep/deadline reasons. Partial valid evidence may permit boundedTierB; otherwise an intentionalTierC/no mix. A displayed intelligent plan must disappear if no longer executable. Preserve timeline, stats and log export semantics.

Suggested separate PR titles: “Calibrate AutoMix candidate trust and overlap compatibility”; “Improve AutoMix incoming preroll and cue preservation”; “Correct AutoMix incoming level matching at silent tails”; “Validate extended AutoMix output timing and handoff.” Every PR gets its own permitted fixtures and regression/device gate; no unrelated playback-engine rewrite.

Rollback on crashes, persistent silence/clipping, wrong cue/queue/promotion, native leak, unbounded CPU/transfers, stale marker/state, or EQ attachment regressions. Retain known-safe fallback/gapless playback and the ability to select the legacy analysis backend during development. Do not enable AutoMix by default or alter preference keys.

## 19. Risks, unresolved questions and blockers

- **Distribution licensing:** Musixmatch restriction and importedAGPL obligations must be resolved before shipping a port. The vocal asset's deposit license and both converted models' provenance are unresolved. Do not assume rootGPL or comments override these.
- **Real-song truth:** no source audio/reference annotations; logged BPM/key cannot be confirmed for those songs. Generated tests show important defects but not corpus-wide superiority.
- **Decoder subtype:** export omits PTS/frames/EOS/container/codec/read details. Exact insufficient-data source cause remains unknown; copying an analyzer will leave it.
- **Estimator residuals:** native half-time at174 and near-gate steady-chord score; 4/4/downbeat/32-beat phrase assumptions. Model confidence also needs calibration and corpus testing.
- **Adapter semantics:** native whole-track normalized energy, missing JNI beats/levels, accidental stripping, lack of phase/downbeat confidence and native cancellation guards require real integration work.
- **Planner second gates:** density saturation, key trust, content preservation and detailed coverage may still prevent A/B plans after grid repair. Fix based on independent evidence.
- **Output:** dualAudioTracks are not sample-locked. Long-plan musical phase, modelcue readiness and post-EQ sound remain unverified. BitChord's shared-session/output design is specifically unsuitable for an indiscriminate replacement.
- **Resource/binary risk:** native memory/exception/cancellation, ORT lifetime/internal threads, ABI/prebuilt16KB compatibility and final APK/RAM/battery costs unmeasured.
- **Source/cache differences:** BitChord complete-cache/head-growth/source matching assumes its own providers. Tinium must preserve authorized streaming/cache limits and never fetch whole songs merely for analysis.
- **Historical log provenance:** app6.0.8/code77 does not prove this installed binary exactly matches inspectedmain. Behaviors/metrics strongly match current recorder/planner fields, but there is no recorded sourceSHA. Add a non-sensitive engine/build revision identifier in a later approved diagnostic change.

## 20. Final feasibility verdict

**Conditionally feasible.**

Tinium's current rhythm analyzer is not beyond repair, but its implemented onset clock, integer lag/search, fixed grid and confidence model are inadequate across the tested rhythmic cases. The device log confirms those scores block intelligent plans; controlled same-PCM tests independently reproduce the weakness. This supports replacing or substantially correcting the analysis backend, **not merely reducing planner confidence thresholds**.

BitChord's native tempo/resampler have demonstrated advantages on this generated corpus and are small, independently compilable candidates. The native directory also contains heuristic key/energy/structure analysis and ML frontends; it is neither just an FFT foundation nor the complete learned AutoMix pipeline. **Porting only native/analyzer is insufficient for the complete goal:** accurate PCM/PTS, guarded JNI/Kotlin adapters, calibrated local evidence and independent gain/planning fixes are necessary. Learned beat/downbeat tracking additionally needs MelSpectrogram, BeatTracker, ONNX and a verified licensed graph; trained vocals require a separate model/backend and are deferred.

Recommend **selective OptionB**, with a model-free DSP checkpoint and only measured, justified supporting components. Retain Tinium's controller/decks/EQ/session architecture. Do not import BitChord's whole planner or output engine now.

Phase2 begins, **only after approval**, with a replaceable engine/decoded-window contract and the failing common-PCM tests. Clear licensing, decoded-audio correctness, analyzer accuracy, native safety/resource and real-device gates before changing the production default. Phase3 then addresses candidate policy, incoming preroll, silence-level matching and long-overlap output verification. Successful compilation or an ordinary fade is not an acceptance criterion for intelligent AutoMix.
