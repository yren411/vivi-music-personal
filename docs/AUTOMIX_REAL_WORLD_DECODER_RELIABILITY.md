# Phase 3.3.1 — real-world AAC/Opus decoder reliability

Baseline: **`d17b094f8b6ba74bc33579c9821bc0b32b3245bc`**, current main containing
merged Phase 3.3 / PR #23. Repair branch: `codex/automix-real-world-decoder`.
BitChord remains pinned to `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`.

Current follow-up: **v7 Opus support-window repair**. The owner reported one failure
among ten v6 decoder tests; its supplied diagnostic establishes missing head/tail
coverage at a nonzero Opus request. After the v7 repair, the owner reported the
single Opus method passed, followed by **all 59 combined Android tests passing,
zero failures/skips**, on the Galaxy S25 Ultra / Android 17. These are owner-reported
results, not Codex executions; raw XML and an independently verified installed SHA
were not supplied. The branch-update instructions identified source `288511a3`.
Earlier v6 failure and host-validation records remain historical.

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

The initial AAC repair introduced corrected preprocessing
**`sinc64-blackman-aac-tailclock-mono-22050-v6`**. The later Opus follow-up below
advances the current profile to v7 without relabeling v6 results.
It includes v5's FLAC frame/codec route and the same arithmetic mono Float32 / 22050Hz
sinc64 Blackman conversion, plus corrected AAC tail timestamp ownership and natural-vs-
request EOS semantics. This can change samples, source coverage and resulting musical
features, so v5 is preserved as `BANDLIMITED_V5` and is never relabeled.

`AnalysisPreprocessing`, native profile, planning/execution admission, worker cache keys,
in-flight identities and signal/active-level observations already reference the common
preprocessing identity. The initial repair selected v6 together; the Opus follow-up
selects v7. Tests distinguish historical/current futures and cached object identities
and reject historical v5/v6 PCM/plans at native boundaries. Signal
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

## Owner Opus regression and bounded window repair (v7)

After the v6 handoff, the owner reported **one failure among the ten new Android
methods** on the Galaxy S25 Ultra / Android 17. A single-method rerun supplied this
actual codec signature (not Codex-executed Android testing):

```text
requestedUs=2000000..7000000 coverage=PARTIAL sourceUs=2001000..6994500
codecFrames=239688 acceptedFrames=239688 outputFrames=110106
queuedInputBuffers=250 firstInputPtsUs=2001000 lastInputPtsUs=6981000
extractorEndSampleTimeUs=7001000 outputDataBuffers=250 outputBytes=479376
firstOutputPtsUs=2001000 lastOutputPtsUs=6974500 lastCodecEndUs=6994500
codecEos=true inputEnd=REQUEST_BOUNDARY gaps=0 synthesizedTimelineFrames=0
```

There are **two missing edges**, not an interior gap: generic seeking delivered the
first packet 1ms after the request, and request-boundary input EOS excluded the real
7001000µs packet. The decoder produced 250×960 − 312 = 239688 source frames at 48kHz.
The committed WebM's OpusHead independently declares 312 pre-skip frames (6.5ms).
The output clock is continuous after the 2001000µs first output, and ends at
2001000 + 239688/48000 seconds = **6994500µs**. Applying the compressed packet cutoff
at 7000000µs therefore did not guarantee decoded coverage through 7000000µs.
This is distinct from the original real-song Opus reports of **zero** PCM at natural EOS;
those reports remain unresolved without their source/seek evidence.

`OpusAnalysisWindow` is applied **only to corrected audio/opus analysis**:

- Request an earlier seek using the declared `csd-2` seek preroll, with the Opus
  80ms decoder-convergence minimum (RFC 7845 §4.6). Near zero, read the fresh track
  without an explicit zero seek, preserving real initial packets.
- Permit compressed input through requested end + the declared `csd-1` codec delay,
  rounded up to microseconds. For the fixture's 6.5ms delay this admits the previously
  discarded 7001000µs packet, but excludes 7021000µs. Actual capture completion can
  stop decoding sooner. Missing delay remains explicitly unknown and adds no lookahead.
- Keep original codec output PTS and sample order. Corrected capture trims actual PCM
  to the **unchanged** requested/effective interval; no timestamp shifting, silence,
  repeated samples or extended coverage is introduced.
- Validate native-endian eight-byte timing declarations; reject malformed, negative,
  overflowing or excessive support with `UNSUPPORTED_OPUS_TIMING`. Support is bounded
  by the largest 16-bit OpusHead pre-skip at 48kHz (1365312500ns, about 1.365s).
  This is a conservative analysis support limit, not a claim that every legal stream
  must have preroll below it. Existing 3/12MiB byte, 32s accepted-window, deadline,
  cancellation and 1/2/4-worker limits are unchanged.

This changes which real source packets can contribute PCM, so current preprocessing is
**`sinc64-blackman-opus-support-mono-22050-v7`**. Historical v6 remains
`BANDLIMITED_V6`; v5/v6 results, in-flight jobs and plans cannot be relabeled as v7.
The common profile propagates to native analysis, signal evidence, planning/execution
admission and cache keys. AAC tail ownership and FLAC extraction/codec policy are retained.
Legacy analysis, positioning and packet cutoff are unchanged.

Host regression replays the **measured packet grid and aggregate output-frame clock**,
not libopus or MediaCodec. Its first-buffer 312-frame reduction is inferred from the
measured total, not a supplied per-buffer XML trace. It checks the exact original
239688-frame partial result and the new bounded support policy, compares trimmed PCM
sample-exactly with contiguous reference PCM, and retains rejection of a genuine missing
source frame. Android endpoint assertions are retained and strengthened to require both
edges, FULL coverage, 240000 accepted source frames and 110250 output frames in each
5s window. The owner subsequently reported this repaired method passed, then
confirmed the complete combined 59-test selection passed. See the separate
[owner-device record](benchmarks/phase331-native-decoder/owner-device-regression.json);
the original Opus diagnostic and host replay have not been rewritten.

The subsequent v7 complete local JVM tasks passed **514 Arm64 GMS + 514 Universal
FOSS = 1028 tests**, zero failures/errors/skips, including the same 17 actual
host-JNI/comparison executions per variant. Five Opus policy/replay tests and one
Opus diagnostic test were added per variant. These counts are one complete run per
variant, not the sum of focused/full reruns; they do not claim Android execution.
Both GMS/FOSS app and test APKs built in **4m31s** (60 executed tasks, 337 up-to-date).
Actual app APK inspections again passed four JNI ABIs, ELF/ZIP16KB alignment, symbols
and license assets; both contain `OpusAnalysisWindow` and the v7 identity in DEX.
Unchanged native-library hashes and prior raw benchmark artifacts are preserved.

Additive `opusWindowSchema=1` diagnostics report declared codec delay, declared/applied
preroll and compressed input cutoff. They do not invent unknown metadata, fetch extra
packets solely for logging, or include paths/URLs/audio. The complete owner diagnostic
and separate post-repair host/build results are recorded in
[the evidence directory](benchmarks/phase331-native-decoder/README.md).

## Cache assessment, Opus, coverage and deadline findings

| Issue | Finding | What remains unverified |
| --- | --- | --- |
| Owner says both tracks cached but incoming flag false | `fullyCached` is a source-publication snapshot. MusicService tests a **whole known-length rendition** in either downloadCache or playerCache; normal playback can use only a cached chunk. A fresh resolved source can also carry the conservative default false, and the two caches' union can contain bytes even if neither alone passes the full-span predicate. | The incoming rendition's actual spans/publication timing were not supplied. No claim that the owner was mistaken or that cache caused this PTS gap. |
| Source access | The same authorized, read-only nested Media3 cache/range factory remains in use. `bytesRead` counts bridge bytes, which can come from cache; it is not a measured cellular/network byte count. | New instrumentation compares actual full/partial/empty Media3 caches over a local ranged encoded fixture. It models range semantics, not real HTTP/CDN/auth/expiry behavior. The subsequent owner report identifies one failing Opus method in the ten-method run; no individual raw cache-test report was supplied. |
| Opus late empty output | The reported legacy attempts reached natural extractor EOS with zero PCM. Existing seeking is generic previous-sync; no verified Opus-specific preroll is established from the excerpts. | Incorrect duration, beyond-end seek, packet/range loss and format-specific preroll remain alternatives. The separate v7 owner-fixture regression above confirms a nonzero seek/head and artificial packet-cutoff defect. Its bounded preroll/delay-support repair does not establish the cause of these original zero-output natural-EOS reports; no byte budget increase. |
| PARTIAL_SOURCE_COVERAGE | Can indicate missing head, actual early EOS, mismatched duration, packet loss or preparation failure. AAC tail retention can also truncate an artificial analysis endpoint. Effective, requested and materialized intervals remain distinct. | Other original attempts lack sufficient per-buffer/source evidence. They are not automatically attributed to the AAC mechanism or relabeled successful. |
| DEADLINE_EXCEEDED | Service starts with finite `min(20s, max(4s, measuredCost×3), remainingPlayback−4s)`; initial estimate gives 6s. Decode and native work share that deadline and the existing worker budget. Outgoing native time ~2.604s alone does not establish where either timed-out attempt spent its time. | No original stage breakdown or worker-wait measurement. New per-stage elapsed values separate extractor/cache setup, codec setup/output, PCM and resampling; native diagnostics already report JNI time. No deadline increase or claim of timeout repair. |

## Privacy-safe diagnostics

Existing `codecProgressSchema=1` fields remain; additive **`codecTimelineSchema=2`** records
original encoder-delay/padding, configured codec padding, selected seek target, first
actually queued packet PTS after positioning, held AAC frames and natural-EOS discarded
frames. Pre-seek PTS stays unknown: diagnostics must not fetch an otherwise unnecessary
initial packet before a tail seek. Empty initial seeks retain the actual EOF sentinel
and zero queued-input counts, rather than inventing a packet timestamp.
`pcmBoundarySchema=1` records only the last buffer PTS/frame count, trim offset, expected
and actual next source-frame index and frame delta. Those frame indices are relative
to the first accepted source frame; buffer PTS remains track-relative microseconds.
No per-packet history is retained.
Decoder-stage elapsed microseconds are bounded to the fixed stage enum; they are elapsed
stage time, not CPU profiling or an isolated network/worker-wait measurement.

These values use the existing bounded recorder/export. Missing values stay null. No PCM,
credentials, URLs, titles or private paths are exported. Debug fixture reports use only
controlled numeric fields and overwrite one bounded app-private diagnostic file.

## Validation and reproduction

[Benchmark evidence](benchmarks/phase331-native-decoder/README.md) separates pre-repair
failure, host PCM parity, full JVM/host-JNI runs, packaging and the subsequent
owner-reported Android run. The 1028-test JVM result and both successful workflows
([GMS](https://github.com/yren411/vivi-music-personal/actions/runs/39146164268),
[FOSS](https://github.com/yren411/vivi-music-personal/actions/runs/39146164279))
belong to implementation `288511a33107276e1b7c67747e1c2c2b3c626a19`; documentation-only
follow-ups are not new local JVM executions. Check the PR for checks on later heads.
The native DSP/JNI/C++ sources are unchanged, so native CTest/sanitizer suites are not
rerun for this Kotlin-only repair. Their previous results are not new passes here.
No emulator/system image/KVM or connected Android device is available to execute tests
in this cloud environment. Android compilation is not a Samsung runtime pass.

The complete v6 fixture-contract validation was **508 Arm64 GMS + 508 Universal FOSS = 1016 passed**,
zero failures/errors/skips. This includes **17 actual host-JNI/comparison tests per
variant** (34 included executions, not additional to 1016). Eight rate/channel/encoding
PCM parity cases measured **maximum absolute Float32 error 0.0**, accepting exactly
44100 or 48000 source frames into 22050 output frames over 0..1000000µs, FULL, with
zero dropped/repeated/synthesized frames. These are generated already-decoded PCM
comparisons, not commercial-song or Android decoder parity measurements.

Both app and instrumentation APKs initially built in **4m06s** (50 executed tasks,
347 up-to-date). After making seek diagnostics passive and adding a real packet-loss
probe, both full suites passed **507 + 507 again**, with app/test builds successful in
**3m29s** (42 executed tasks, 355 up-to-date). Counts are per complete run, not summed
across reruns. Final packaging checks were repeated and recorded separately.
The new test class is present in built test DEX. Actual app APK checks pass all four JNI
ABIs, ELF/ZIP 16KB alignment, symbols and license assets. At that cloud validation
checkpoint, no new Android instrumentation was executed by Codex: **0 device executions**,
not ten passes. The later owner-reported device run is recorded separately below. Exact counts, commands, original
failure and unchanged-native justification are in the evidence directory. The first
broad validation exposed a version-list test expecting seven distinct profiles after an
eighth (historical v5) was added; the exact uniqueness assertion was updated to eight,
then both complete suites were rerun successfully. No assertion was removed.

The generated AAC initially used FFmpeg's shortened final stts duration, which could
imply zero declared codec padding. Before device handoff the generator was corrected:
only stts/mdhd are adjusted to full coded-frame timing, while elst presentation duration
and all encoded mdat bytes remain unchanged. Independent atom inspection finds 1573888
media frames − 1024 priming − 1572850 presentation = **14 declared tail frames**.
FFprobe still reports the correct 1572850-frame presentation and clips the last packet
to 1010 presentation samples. A JVM fixture-contract test checks these committed bytes
and payload hash, preventing an unrelated valid-AAC case from satisfying this regression.
The initial fixture hash/defect remain in the fixture manifest as historical evidence.
After that fixture-contract addition, both full suites passed **508 + 508**, and both
app/test variants built successfully in **2m47s** (22 executed tasks, 375 up-to-date).
This was the complete v6 fixture-contract run; the separate v7 follow-up evidence records its own executions. Earlier 507-per-variant runs above retain their own counts.

New class: **10 tests**, using genuine generated AAC and Opus. One direct MediaCodec
probe explicitly configures 14-frame padding to reproduce the original 1010/1024 buffer
signature. A zero-padding real-codec reference checks sample-exact parity against the
new tail-managed route. Other tests cover 32s AAC/native planning, negative initial input
PTS, full/partial/empty caches, seek/coverage, Opus near-end/EOS, cancellation, deadlines
and strict missing-source rejection before JNI, including a real omitted compressed AAC
access unit with unchanged remaining extractor timestamps. No internet or audio playback is needed
for this new decoder class. Existing 49 tests remain intact.

The owner first reran
`com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest#opusEarlierAndNearEndWindowsContainActualDecodedAudio`
and reported a pass, then reported all **59** methods passed in one combined selection:

| Suite | Tests passed (owner-reported) |
| --- | --- |
| Real-world AAC/Opus decoder | 10 |
| Corrected PCM decoder | 4 |
| BitChord native analyzer | 14 |
| Native-aware planner | 6 |
| Native DJ executor | 14 |
| Native AutoMix service | 11 |
| **Total** | **59**, zero failures/skips |

These generated-media integration results do not establish original-song repair,
acoustic alignment or audible mix/EQ quality. The following commands remain optional
regression/troubleshooting tools; no additional device rerun is required solely for
this documentation update. The diagnostic file records Opus delay/preroll and cutoff.

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

The owner used this exact combined **59-test** selection (expected zero failures/skips):

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
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

The retained-test-APK command installs the isolated current-profile app, not an update to the normal
Tinium installation. To update only that isolated app for ordinary listening:

```powershell
.\gradlew.bat :app:installArm64GmsDebug "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --stacktrace
```

Keep the same working debug key. The isolated app's sources/preferences/cache are
separate; normal cached songs remain untouched and are not automatically copied. Configure
only authorized sources normally. No uninstall or cache wipe is needed. Use the latest
attempt/session in the export; do not count cumulative retained history as new failures.

1. In the appropriate local experimental build, enable AutoMix and explicitly select
   Native DJ AutoMix before starting the pair (Settings → Player and audio → Player →
   AutoMix → AutoMix engine; selector appears only when AutoMix is enabled). Preserve the normal installation/data and
   existing signing; the isolated debug app has separate preferences/accounts/cache.
2. Queue **Oye Como Va — Banda M1 → Eva Maria — Banda Maguey**, using sources you are
   authorized to play. Keep the same volume/route/EQ conditions. Do not erase cached songs.
3. Export Stats for Nerds after the attempt. Check **v7** preprocessing and both actual
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
