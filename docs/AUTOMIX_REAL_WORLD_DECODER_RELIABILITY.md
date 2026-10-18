# Phase 3.3.1 — real-world AAC/Opus decoder reliability

Baseline: **`d17b094f8b6ba74bc33579c9821bc0b32b3245bc`**, current main containing
merged Phase 3.3 / PR #23. Repair branch: `codex/automix-real-world-decoder`.
BitChord remains pinned to `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`.

## Scope and evidence limits

This is a corrected **analysis decoder** repair, not a musical-gate, playback, provider,
EQ or gain change. Legacy AutoMix and `nearest-time-mono-22050-v1` remain the defaults.
Experimental opt-in, strict native admission and genuine independent legacy fallback
remain unchanged. No commercial audio, signed URL, private path or account data is included.

The owner supplied two listening-session summaries and diagnostic field excerpts in the
task. The original full exports and the encoded commercial sources were **not attached**
to this task. Consequently we can reconstruct the frame arithmetic and reproduce a matching
platform mechanism, but cannot claim independent inspection of all log events or the
original incoming track's gapless metadata. Android runtime verification is separate from
host testing and APK compilation; the new device tests have not been run by Codex.

## Listening reports and exact Pair 35 signature

The first report (Bad Bunny and similar material) recorded 12 native pairs, no plans or
completed native mixes, 11 native fallbacks and five completed legacy handoffs. Recorded
fallback reasons: EMPTY_OUTPUT 4, PARTIAL_SOURCE_COVERAGE 4, DEADLINE_EXCEEDED 2,
SOURCE_GAP 1. These categories can share causes; they are not eleven diagnosed defects.

The second export retained the **same service session**. Its earlier history is cumulative,
not a new occurrence of each earlier failure. The newly relevant attempt is Pair 35:

| Stage | Owner-reported evidence | Interpretation |
| --- | --- | --- |
| Outgoing cached AAC | Requested/covered 131865000..163865000µs; 705600 canonical frames; FULL; `c2.android.aac.decoder`; no gaps/drops/synthesis | Corrected v5 can decode/analyze a real AAC tail. Preserve this behavior. |
| Outgoing native DSP | BPM ~147.9543; raw confidence ~0.53783; RMS ~0.14723; ~2604ms | Genuine completed analysis; neither musical BPM ground truth nor calibrated confidence. |
| Incoming AAC | Requested 0..32000000µs; first input PTS −23219µs; output PTS 0 then 23219µs; 2034 codec frames; only 1010 accepted; SOURCE_GAP | Rejected before incoming native analysis or a native plan. |
| Incoming source | 44100Hz stereo, `audio/mp4a-latm`, `c2.android.aac.decoder`; 196608 logical bytes read; `fullyCached=false`, range VERIFIED | Source flag alone does not measure network traffic or establish a cache-related cause. |
| Genuine legacy fallback | Coverage 23219..46121µs then 46439..16000000µs; low beat confidence; ~3500ms Tier C ENERGY_FADE | Playback protection worked; legacy's separate gap policy is not borrowed for native evidence. |

First-buffer frames are **inferred** from the reported totals: 2034 − 1024 = 1010.
At 44100Hz they cover `1010 / 44100 = 0.0229024943s`. The next reported PTS maps to
`23219 × 44100 / 1000000 = 1023.9579` frames. The discrepancy is **13.9579 frames**,
about 316.5µs. This exceeds the unchanged half-source-frame timestamp cell (~11.34µs).
The old capture correctly refuses these **unexplained timestamp coordinates**. Merely
snapping the next buffer backward would hide missing data and is not the repair.

The initial host reproducer failed with `PcmPreparationException: SOURCE_GAP` after
1010 accepted frames and source coverage 0..22902µs. Its original outcome and XML hash
are preserved in [before-pair35-replay.json](benchmarks/phase331-native-decoder/before-pair35-replay.json).
The final characterization still requires that unexplained signature to be rejected.

## Root cause: timestamp ownership of retained AAC tail PCM

A concrete platform mechanism explains precisely this first-short-buffer signature:
Android's `SkipCutBuffer` retains declared **encoder-padding** PCM in a FIFO. With
14 frames of padding and 1024-frame AAC output, it first returns 1010 frames. The next
1024 returned frames begin with the **previous** packet's 14 retained frames. The
single-output-buffer path does not move the new buffer's PTS back to those older bytes.
Applying that new PTS to every returned sample makes continuous PCM appear to have a gap.

Reference source inspected: `LineageOS/android_frameworks_av` at
`f13e44db891bf5caf478062365372a8fbc23e45f`:

- [`SkipCutBuffer.cpp`, `submitInternal`](https://github.com/LineageOS/android_frameworks_av/blob/f13e44db891bf5caf478062365372a8fbc23e45f/media/libstagefright/SkipCutBuffer.cpp): retain tail bytes, then refill the client buffer; buffer timestamp is not adjusted here.
- [`CCodecBuffers.cpp`, `OutputBuffers::submit` / `OutputBuffersArray::registerBuffer`](https://github.com/LineageOS/android_frameworks_av/blob/f13e44db891bf5caf478062365372a8fbc23e45f/media/codec2/sfplugin/CCodecBuffers.cpp): the single-buffer path invokes that FIFO.
- [`MPEG4Extractor.cpp`, edit-list processing](https://github.com/LineageOS/android_frameworks_av/blob/f13e44db891bf5caf478062365372a8fbc23e45f/media/module/extractors/mp4/MPEG4Extractor.cpp): container/edit-list metadata can supply encoder delay and padding.

This is inspected platform reference code, **not verification of Samsung's exact Android
17 binary**, and no platform source was copied. Negative compressed PTS alone is not the
cause: those packets supply AAC priming and remain preserved. Front-delay and tail-padding
are different operations.

**Confirmed:** the frame mismatch, the old failure, and the matching FIFO mechanism can
be reproduced deterministically. **Strong hypothesis for Pair 35:** its codec was retaining
14 encoder-padding frames. The original export did not record encoder-padding, so that
specific link remains to be confirmed by the new real-codec/device and listening diagnostics.
If a source has a different genuine gap, it will still fail; no automatic timestamp repair
is inferred solely from the shape of this failure.

## Narrow repair and PCM timeline

For corrected AAC with positive declared encoder-padding only:

1. Read/save the original encoder-delay/padding values. Configure **codec tail padding=0**.
   Front/encoder-delay handling stays codec-owned; negative initial packets and all existing
   seek behavior are preserved. Legacy and every non-AAC codec keep their original configuration.
2. `AacAnalysisTailTrim` retains the declared tail itself, keeping each segment's original
   buffer PTS plus its exact frame offset. It emits older retained samples with their own
   source time **before** emitting current-buffer samples.
3. At genuine codec EOS reached from **natural extractor EOS**, discard only the declared
   final padding. At artificial **request-boundary EOS**, release that real retained PCM:
   the end of a 32-second analysis window is not the end of the song.
4. The existing `CorrectedPcmWindowCapture` still maps the emitted original timestamps,
   detects genuine discontinuities, trims the requested interval and resamples. Its
   nearest-frame cell, sample conversion and sinc filter are unchanged. No PCM is repeated,
   interpolated, synthesized or silently relabeled as full source coverage.

For the 14-frame case, emission is:

| Data | Source-frame indices | Emitted PTS (µs) |
| --- | --- | ---: |
| First 1010 frames | 0..1009 | 0 |
| Retained 14 from the first buffer | 1010..1023 | 22902 (rounded from 22902.4943) |
| First 1010 from the next buffer | 1024..2033 | 23219 (container PTS) |
| Its retained tail, when released | 2034..2047 | 46121 (rounded from raw PTS 23219 + 1010-frame offset) |

The unchanged frame clock absorbs only sub-half-frame quantization. Actual missing
frames still fail SOURCE_GAP; regressions and overlaps keep their existing treatment.
The queue never merges segments across unknown timestamps. Real EOS retains honest
partial coverage if valid audio is short, rather than turning a materialized buffer into
full evidence.

## Versioning and resource bounds

New corrected preprocessing: **`sinc64-blackman-aac-tailclock-mono-22050-v6`**.
It includes v5's FLAC frame/codec route and the same arithmetic mono Float32 / 22050Hz
sinc64 Blackman conversion, plus corrected AAC tail timestamp ownership and natural-vs-
request EOS semantics. This can change samples, source coverage and resulting musical
features, so v5 is preserved as `BANDLIMITED_V5` and is never relabeled.

`AnalysisPreprocessing`, native profile, planning/execution admission, worker cache keys,
in-flight identities and signal/active-level observations already reference the common
preprocessing identity. They now select v6 together. Tests distinguish v5/v6 futures and
cached object identities and reject historical v5 PCM/plans at native boundaries. Signal
observation **method** versions stay unchanged: the measurement algorithm did not change,
and its profile/cache identity already includes preprocessing. No persistent cache migration,
unbounded cache, second scheduler or native algorithm change is introduced.

The tail holds at most 8192 source frames, with explicit frame/rate validation. At the
largest supported 8-channel Float32 format that is **262144 payload bytes per worker**
(calculation, excluding bounded segment/object overhead). Typical 14-frame stereo PCM16
retention is 56 bytes. A single-frame segmentation adversary can create at most 8192
pending segments; actual AAC normally retains one or two. Output-rate scaling follows
configured padding's source sample rate, with the same post-scaling bound. Oversized
metadata fails safely. Control checkpoints guard conversion/emission/finish; no detached
threads or playback-thread work. Existing worker 1/2/4, queue 8, cache 64 and source byte,
window, retry and network limits remain unchanged.

## Cache assessment, Opus, coverage and deadline findings

| Issue | Finding | What remains unverified |
| --- | --- | --- |
| Owner says both tracks cached but incoming flag false | `fullyCached` is a source-publication snapshot. MusicService tests a **whole known-length rendition** in either downloadCache or playerCache; normal playback can use only a cached chunk. A fresh resolved source can also carry the conservative default false, and the two caches' union can contain bytes even if neither alone passes the full-span predicate. | The incoming rendition's actual spans/publication timing were not supplied. No claim that the owner was mistaken or that cache caused this PTS gap. |
| Source access | The same authorized, read-only nested Media3 cache/range factory remains in use. `bytesRead` counts bridge bytes, which can come from cache; it is not a measured cellular/network byte count. | New instrumentation compares actual full/partial/empty Media3 caches over a local ranged encoded fixture. It models range semantics, not real HTTP/CDN/auth/expiry behavior; device execution pending. |
| Opus late empty output | The reported legacy attempts reached natural extractor EOS with zero PCM. Existing seeking is generic previous-sync; no verified Opus-specific preroll is established from the excerpts. | Incorrect duration, beyond-end seek, packet/range loss and format-specific preroll remain alternatives. No additional byte budget or speculative Opus seek change. New local WebM near-end/EOS tests and before/after seek PTS diagnostics distinguish the next failure. |
| PARTIAL_SOURCE_COVERAGE | Can indicate missing head, actual early EOS, mismatched duration, packet loss or preparation failure. AAC tail retention can also truncate an artificial analysis endpoint. Effective, requested and materialized intervals remain distinct. | Other original attempts lack sufficient per-buffer/source evidence. They are not automatically attributed to the AAC mechanism or relabeled successful. |
| DEADLINE_EXCEEDED | Service starts with finite `min(20s, max(4s, measuredCost×3), remainingPlayback−4s)`; initial estimate gives 6s. Decode and native work share that deadline and the existing worker budget. Outgoing native time ~2.604s alone does not establish where either timed-out attempt spent its time. | No original stage breakdown or worker-wait measurement. New per-stage elapsed values separate extractor/cache setup, codec setup/output, PCM and resampling; native diagnostics already report JNI time. No deadline increase or claim of timeout repair. |

## Privacy-safe diagnostics

Existing `codecProgressSchema=1` fields remain; additive **`codecTimelineSchema=2`** records
original encoder-delay/padding, configured codec padding, selected seek target, observed
extractor PTS before/after seeking, held AAC frames and natural-EOS discarded frames.
`pcmBoundarySchema=1` records only the last buffer PTS/frame count, trim offset, expected
and actual next source-frame index and frame delta. No per-packet history is retained.
Decoder-stage elapsed microseconds are bounded to the fixed stage enum; they are elapsed
stage time, not CPU profiling or an isolated network/worker-wait measurement.

These values use the existing bounded recorder/export. Missing values stay null. No PCM,
credentials, URLs, titles or private paths are exported. Debug fixture reports use only
controlled numeric fields and overwrite one bounded app-private diagnostic file.

## Validation and reproduction

[Benchmark evidence](benchmarks/phase331-native-decoder/README.md) separates pre-repair
failure, host PCM parity, full JVM/host-JNI runs, packaging and pending Android runtime.
The native DSP/JNI/C++ sources are unchanged, so native CTest/sanitizer suites are not
rerun for this Kotlin-only repair. Their previous results are not new passes here.
No emulator/system image/KVM or connected Android device is available to execute tests
in this cloud environment. Android compilation is not a Samsung runtime pass.

Actual local validation: **507 Arm64 GMS + 507 Universal FOSS = 1014 passed**,
zero failures/errors/skips. This includes **17 actual host-JNI/comparison tests per
variant** (34 included executions, not additional to 1014). Eight rate/channel/encoding
PCM parity cases measured **maximum absolute Float32 error 0.0**, accepting exactly
44100 or 48000 source frames into 22050 output frames over 0..1000000µs, FULL, with
zero dropped/repeated/synthesized frames. These are generated already-decoded PCM
comparisons, not commercial-song or Android decoder parity measurements.

Both app and instrumentation APKs built in **4m06s** (50 executed tasks, 347 up-to-date).
The new test class is present in built test DEX. Actual app APK checks pass all four JNI
ABIs, ELF/ZIP 16KB alignment, symbols and license assets. No new Android instrumentation
was executed: **0 device executions**, not ten passes. Exact counts, commands, original
failure and unchanged-native justification are in the evidence directory. The first
broad validation exposed a version-list test expecting seven distinct profiles after an
eighth (historical v5) was added; the exact uniqueness assertion was updated to eight,
then both complete suites were rerun successfully. No assertion was removed.

New class: **10 tests**, using genuine generated AAC and Opus. One direct MediaCodec
probe explicitly configures 14-frame padding to reproduce the original 1010/1024 buffer
signature. A zero-padding real-codec reference checks sample-exact parity against the
new tail-managed route. Other tests cover 32s AAC/native planning, negative initial input
PTS, full/partial/empty caches, seek/coverage, Opus near-end/EOS, cancellation, deadlines
and strict missing-source rejection before JNI. No internet or audio playback is needed
for this new decoder class. Existing 49 tests remain intact.

PowerShell from the repository root in Android Studio; keep the working debug key and
isolated `com.yren411.tinium.decodercheck` package. No normal-app uninstall, data deletion,
cache wipe or signing change:

```powershell
git fetch origin
git switch codex/automix-real-world-decoder
git pull --ff-only
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Expected **10 executed, zero failures/skips**. For the exact codec-boundary probe only,
append `#actualCodecPadding14ReproducesTheReportedBoundaryAndStrictRejection` to the class.
The test passes only when actual MediaCodec reproduces the old timestamp problem and
strict capture rejects it; the other tests independently require corrected full output.
Do not count a zero-test invocation or compilation as successful device execution.

To rerun the prior **49** (plays generated audio; use comfortable volume and do not run
another Gradle/device invocation concurrently):

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Retain failures/counts from `app/build/reports/androidTests/connected/` and
`app/build/outputs/androidTest-results/connected/`. The new decoder helper emits
`REAL_WORLD_DECODER_ANDROID` and saves the last decoded fixture's sanitized report:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase331-decoder-last.txt
```

A test failing before the helper runs might not create that file; retain its actual
assertion too. Existing Stats for Nerds long-press Downloads export is the supported
real-listening diagnostic path, not unrestricted logcat or automatic telemetry.

## Authorized real-song rerun after device regressions

1. In the appropriate local experimental build, enable AutoMix and explicitly select
   Native DJ AutoMix before starting the pair. Preserve the normal installation/data and
   existing signing; the isolated debug app has separate preferences/accounts/cache.
2. Queue **Oye Como Va — Banda M1 → Eva Maria — Banda Maguey**, using sources you are
   authorized to play. Keep the same volume/route/EQ conditions. Do not erase cached songs.
3. Export Stats for Nerds after the attempt. Check **v6** preprocessing and both actual
   source identities, outgoing full corrected coverage and incoming full coverage without
   false SOURCE_GAP. Inspect encoder-padding/configured-padding and frame-boundary evidence.
4. Confirm both native results reach planning, or retain accurate rejection reasons. A
   decoder success does not require a native plan, prove the BPM, or authorize relaxed gates.
   Execute only a genuinely admitted plan; otherwise independent legacy fallback must keep
   playback/queue continuity without competing fades or permanently muted decks.
5. Repeat with permitted Bad Bunny/similar AAC and Opus material, including earlier/late
   windows and full/partial cache conditions. Separate technical failures from musical
   rejection and subjective beat/volume/vocal/cue quality. Preserve unfavorable cases.
6. Return to Legacy AutoMix at any time. Share only the redacted report and voluntary
   listening observations, never audio, signed URLs or unrestricted playback history.

No real-song result, acoustic alignment, Bluetooth/EQ listening quality, release or default
activation is claimed. Opus, streaming/CDN, other partial-coverage causes and timeout
budgeting remain targeted follow-ups if the new evidence identifies a separate defect.
