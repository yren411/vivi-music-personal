# AutoMix analysis engine architecture — Phase 2.1

Starting main commit: **c72712ceb25292ae0dea5e642cd3c779d8d67a7d** (merged Phase 1 report).
Design reference: [BitChord feasibility investigation](BITCHORD_AUTOMIX_PORT_FEASIBILITY.md).

## Scope and production behavior

This phase introduces a replaceable PCM analysis boundary and regression corpus.
**The production backend remains Tinium's existing Kotlin analyzer.** There is
no native code, JNI, CMake, ONNX, model, new dependency, backend setting or UI change.
The existing BPM, onset, grid, key, confidence, candidate, fade, gain, tempo,
preparation and handoff algorithms are preserved.

The Phase 1 investigation found wrong/low-confidence grids, separate decoding
failures and a separate incoming-gain problem. This PR does **not** fix those DSP,
decoder or gain policies. It makes their inputs and semantics inspectable and
creates a common benchmark for later approved changes.

## Implemented pipeline

~~~mermaid
flowchart TD
    C[Existing AutoMixController] --> W[Existing bounded AnalysisWorkers]
    W --> H[MusicService AutoMixServiceHost]
    S[Existing authorized source / cache] --> D[AndroidWindowDecoder]
    H --> P[WindowAnalysisPipeline]
    P --> D
    D --> CAP[LegacyPcmWindowCapture: unchanged nearest-time samples + source PTS metadata]
    CAP --> PCM[DecodedWindow]
    PCM --> E[AnalysisEngine: LegacyKotlinAnalysisEngine]
    E --> V[AnalysisResultValidation]
    V --> R[Qualified AnalysisResult]
    R --> A[LegacyPlanningFeatures: exact original TrackFeatures]
    A --> CF[Controller coarse/refined aggregation and existing planner]
    CF --> OUT[Existing Media3 decks / DSP / EQ lifecycle]
~~~

The controller still schedules scan/refinement/planning and awaits work in the
same places. The service host calls a synchronous pipeline **inside an existing
analysis worker**. Neither decoder, engine, validation nor bridge owns an executor,
nested future, coroutine scope, player or network resolver.

## Interfaces and files

All new production files are under
`app/src/main/kotlin/com/music/vivi/playback/automix/`:

| File | Responsibility |
|---|---|
| AnalysisContracts.kt | PCM formats, source identity, intervals, coverage/duration metadata, decode requests/results, failure transport and cancellation |
| AnalysisEngine.kt | AnalysisEngine, backend/profile, typed musical evidence, results, pipeline orchestration and explicit legacy planning bridge |
| LegacyKotlinAnalysisEngine.kt | Calls the existing analyzer and qualifies its evidence without changing numerical values |
| LegacyPcmWindowCapture.kt | Extracted old nearest-time sample writer plus bounded source-coverage observation |
| AnalysisResultValidation.kt | Checks backend/context consistency and marks invalid evidence explicitly, without inventing/recalculating values |

Modified boundaries:

- `AndroidWindowDecoder` now implements `AnalysisWindowDecoder<AnalysisSource>`
  and returns `DecodeResult`. It does not call a musical analyzer.
- `MusicService.AutoMixServiceHost` owns one pipeline using the legacy engine and
  existing preprocessing. Its existing error classification and sources remain.
- `AutoMixHost.analysisProfile` defaults to that legacy profile; the service
  exposes the profile of its actual pipeline.
- `AutoMixController.requestWindow` uses that profile in cache identity. Its
  scheduling, regions, retries, scoring, gains, decks and status are unchanged.
- `TrackFeaturesAnalyzer` only gains an optional cancellation signal at the four
  existing checkpoint locations. The numerical body and processing order are
  unchanged. `checkAnalysisCancellation` retains interruption/deadline checks.

## DecodedWindow contract

The float buffer is interleaved; format supplies its actual materialized sample
rate/channel count/encoding. The current decoder still materializes **mono
Float32 at 22,050 Hz**, even when source PCM is stereo, 48 kHz or PCM16. Source
formats are retained separately; they do not describe the float buffer.

PCM ownership transfers to one analysis job; backends borrow samples read-only.
They must not mutate or retain PCM. Feature caches never hold decoded samples.
Metadata uses immutable values/read-only lists captured by the producer.
Safe descriptions omit sample contents, identities and original exception messages.

| Metadata | Meaning |
|---|---|
| requested | Original requested absolute source interval |
| decodeInterval | Effective interval after the unchanged duration/32-second clamp |
| sampleTimelineStartUs / sampleInterval | Nominal timeline of the materialized buffer; start remains the old requested origin |
| actualStartUs / actualEndUs | Bounds of observed accepted source-frame coverage; nullable when unknown |
| format / frameCount | Float buffer representation and `samples.size / channels`; not source-frame count |
| sourceFormats | Observed decoder-output formats, or explicitly tagged container/assumed-PCM16 information |
| coverage.intervals | Union of accepted source PCM frame intervals, clipped to decodeInterval; null = unmeasured, empty = measured none |
| coverage.acceptedDecodedFrames | Source frames passing interval trim, before downsampling/dropped duplicate PTS; not unique timestamps or output count |
| repeatedOutputFrames | Additional materialized slots written with the same input sample by the old nearest-time writer |
| duration | Raw extractor duration in microseconds plus provenance; unknown stays null |
| endOfStreamReached | Whether a codec EOS flag was observed; does not prove the file's natural end, since requested endpoints also queue EOS |
| identity | Stable recording ID plus rendition identity; never printed in diagnostics |
| preprocessingVersion | Precisely identifies the PCM transformation used |

### Timing and coverage conventions

All contract intervals are **absolute source-timeline microseconds, start inclusive,
end exclusive**. Source frame times use decoder BufferInfo PTS and the reported
output rate, as in the existing loop. A frame endpoint is rounded up by at most
one microsecond; adjacent fractional-rate frames are coalesced with at most a
one-microsecond rounding tolerance. These are timestamp-derived coverage bounds,
not measured speaker/Bluetooth presentation times.

Requested origin is **not relabeled as actual first decoded PTS**. A late first
frame/gap may make the old writer repeat a sample into nominal slots. Those slots
remain in the buffer for compatibility, but **do not extend actual coverage**.
Normal nearest-time upsampling also repeats samples; repetition count alone does
not prove a source gap.

Coverage is FULL only when observed intervals cover the original request,
PARTIAL for known gaps/truncation, UNKNOWN when not established. A clamped
interval can be fully covered while the original longer request remains partial.
Container-only format assumptions make coverage/frame timing explicitly unknown.
Fragmented interval history is capped at256 retained segments plus the active
segment; if exceeded, coverage becomes unknown without changing audio output or
acceptance. No complete interval is invented from requested length/buffer size.

The old writer's arithmetic, source trim, sample order and half-second short-window
failure threshold are retained. Codec selection, seeking, EOS queueing, source
authorization, range fetching, 3/12 MiB budgets, four-block cache and32-second
maximum remain unchanged. No new resampling or gap repair is performed.

The legacy engine requires mono float PCM and millisecond-aligned nominal origin;
unsupported layouts/origins fail explicitly. Full-track duration is passed to the
old analyzer with the same millisecond truncation. Unknown duration becomes the
legacy unknown value0, never the end of an excerpt. New backends can use the
contract's microseconds, channels and actual sample duration directly.

## Musical evidence and validation

`AnalysisResult.Completed` contains backend/context, coverage status and typed
`MusicalEvidence`. Completion means analysis returned; it does **not** mean a
valid transition exists or an incoming deck is ready. Partial/unknown coverage
qualifies completion without introducing a new legacy rejection threshold.

`MusicalValue` distinguishes:

- **MEASURED:** statistic computed from supplied PCM, such as RMS/peak/energy.
  This does not imply original unconverted source or post-EQ/output loudness.
- **INFERRED:** legacy fixed beat grid, tempo, onset-capture score, bass-accent
  downbeats, key-profile estimate or spectral vocal-risk proxy.
- **UNAVAILABLE:** null value, rather than an invented tempo/key/bar/voice result.
- **INVALID:** original value retained with an explicit invalid qualification.

The independent result validator checks returned backend, source, interval,
preprocessor, detail and coverage/context identity. Mismatch becomes a structured
RESULT_VALIDATION failure. Nonfinite/out-of-range scores, invalid numeric keys,
unordered/out-of-window beat arrays and malformed energy/vocal curves are marked
INVALID, not normalized or silently made confident.

Legacy confidence semantics are explicitly documented in the result:
correlation × sqrt(onset capture) × sqrt(grid support); phase is onset capture;
key confidence is profile margin ×3. These are **uncalibrated heuristic scores,
not probabilities**. Grid support is exposed separately. Independent tempo
stability is unavailable because the algorithm does not measure it.
Numeric tonic0…11/minor mode preserve key information without text serialization.

The explicit `LegacyPlanningFeatures` bridge returns the **same TrackFeatures
object produced by the analyzer**, not reconstructed values from the new evidence.
Thus sidecar qualifications do not change legacy planning behavior. It rejects a
different backend or missing legacy payload; a future native engine needs its
own deliberately reviewed planning adapter. It cannot silently reuse legacy
confidence semantics.

Failures carry stage/category/controlled subtype. Original exceptions stay
internal so the bridge can rethrow the same exception for existing service
classification, retry and fallback paths. No raw exception message is exported.
An exposed `truncated-audio-window` subtype means that exact existing guard
fired; it does not establish why a real stream ran short.

## Backend, preprocessing and cache versioning

Current backend: **tinium-legacy-kotlin / 5** (the existing v5 DSP semantics).
Current production preprocessing: **nearest-time-mono-22050-v1**.
Direct synthetic fixtures use **identity-float-v1**.

Cache keys include backend ID/version, preprocessing version, stable recording
ID, existing rendition identity, requested start/length and detail. Length-prefixed
fields are SHA-256 digested; signed resolver details are not exposed in key strings.
Recording identity is separate from the URL/rendition component.

The current cache still holds exact legacy TrackFeatures in the existing64-entry
memory LRU and deduplicates in-flight work. No disk cache or second cache exists.
New key namespace `analysis-v1` needs no migration: the old cache was service-local
and already disappears on app/service restart. A steady backend/rendition/window
continues hitting cache; canceled work cannot overwrite fresh results, and valid
completed results remain reusable across invalidation.

Future engines must use distinct profiles and preserve qualified coverage through
their own planning/cache adapter. They must not populate the optional internal
legacy payload or masquerade as backend version5.

## Cancellation, invalidation and worker limits

`AnalysisControl` carries the existing deadline plus a cooperative cancellation
signal. Existing thread interruption remains checked and is not cleared.
`AnalysisCancellationToken` is an atomic JVM signal; no native handle or scheduler
is implemented. The legacy analyzer polls it at existing RMS/onset/correlation/
spectral checkpoints. Decoder loops/source reads use the same control.

Existing controller pair/source checks and future cancellation remain authoritative
for seek/skip/queue/disable/performance changes. Pipeline checks additionally
reject a decoded window belonging to a different request/rendition/preprocessor.
The existing future lifecycle prevents canceled old-generation results from
entering the cache or replacing current work, including late decoder returns.

Efficient/Balanced/Performance remain **1/2/4 actual analysis workers**, queue8,
with no new scheduler or nested future wait. Metadata/evidence adds bounded
per-window work, not per-sample logging or a UI analysis task.

## Diagnostics

The new API exposes backend/version, preprocessing, requested/materialized/source
coverage, decoder formats, duration provenance, repetitions, EOS, result qualification
and structured failure stage/subtype. Existing schema1 recorder, Stats for Nerds,
timeline marker and export interaction remain unchanged. The compatibility bridge
returns legacy features to existing WINDOW_DONE logging.

**These additional fields are not yet included in exported schema1 reports.**
A later diagnostic schema addition can record controlled backend IDs, coverage/frame
counts and exact internal error subtype without dumping URLs, exception messages
or PCM. This phase does not claim to diagnose the attached stream failures on-device.

## Shared regression corpus and accuracy targets

New tests under `app/src/test/kotlin/com/music/vivi/playback/automix/`:

- `AnalysisPcmFixtures`: generated90/107/120/128/140/150/174BPM grooves,
  continuous harmonic bed, accented kicks, backbeat noise snares, eighth-note
  hats, phase0.137s,120-second128BPM case, other phases, leading silence,
  offbeat accents,44.1/48kHz, silence/chord/noise controls.
- Uses Phase1 amplitudes/envelopes and seed411, with a **specified32-bit LCG**
  rather than NumPy PCG64 noise; no external library, copyrighted recordings,
  wall-clock-dependent samples or production fixtures.
- `BeatBenchmark`: one-to-one70/30ms precision/recall/F1, median/p95 matched
  error, exact tempo error, separate half/double-time classification and
  index-paired accumulated drift. No phase rotation/octave rescaling.
- Index-paired drift is deliberately large for wrong tempo/missing beats;
  inspect beat counts/F1 before describing it as pure local phase drift.
- `FutureAnalysisAccuracyTargets` evaluates exact-tempo≤1%, F1≥.95 at70ms
  **and30ms**, p95≤30ms and absolute drift≤70ms. These targets are not weakened
  to fit legacy; the characterization prints explicit unmet targets.
- `LegacyAnalysisCharacterizationTest` asserts known defects remain reproducible
  while parity tests compare all raw features. No difficult tests are disabled.
- Contract/engine/validation/pipeline tests cover formats, counts, timestamps,
  gaps, unknown/partial coverage, EOS, malformed input, missing/invalid evidence,
  backend/context mismatches, cancellation/deadlines, cache separation/reuse,
  late canceled generations and actual1/2/4 worker limits.
- Existing `AutoMixPipelineTest` now exercises its asynchronous scan/refinement/
  controller fixtures through the legacy engine interface, retaining assertions.
  Existing controller, DSP, preferences, diagnostics and EQ tests remain enabled.

### Intentionally unfixed baseline results

30-second grooves, SCAN, current legacy adapter; scores rounded:

| True BPM | Estimated BPM | Grid score | F1 at70ms | F1 at30ms |
|---|---|---|---|---|
|90|60.000|.205|.400|.400|
|107|107.143|.237|1.000|1.000|
|120|120.000|.367|1.000|1.000|
|128|85.714|.091|.360|.200|
|140|142.857|0|.288|.106|
|150|150.000|.363|1.000|1.000|
|174|176.471|0|.405|.147|

The120-second128BPM case remains85.714BPM, F1≈.252/.119 and index-paired
drift≈39.544s. Sustained chords/noise can produce nonzero tempo guesses but
zero grid confidence. Even correctly timed120/150BPM grooves remain below the
active planner's.65 confidence threshold. These are recorded failures of the
legacy measurement/score model, **not improved audio quality in this phase**.

## Validation and limitations

- Focused GMS arm64Debug and FOSS universalDebug suites: **151 tests executed
  per variant, zero failures/errors/skips**, including40 new tests beyond the
  existing111 focused cases. Prerequisite tasks reuse Gradle outputs where valid.
- Separate temporary host comparison of pre-refactor compiled DSP vs refactored
  DSP: **32 exact full-feature string matches**,8 Phase1 PCM cases ×SCAN/DETAIL
  ×origins0/12345ms. Adapter unit tests additionally assert TrackFeatures equality
  and read-only input. No baseline analyzer duplicate is committed.
- Debug compilation occurs as test prerequisites. Universal GMS/FOSS release-source
  compilation is checked separately, without APK packaging/signing.
- `git diff --check` and source/diff review confirm no DSP arithmetic/threshold,
  transition, gain, EQ/session, UI, preference, signing/version/workflow or dependency
  change. Only optional cancellation polling was added inside analyzer checkpoints.
- No Android device/emulator decoding, output capture/listening or Poweramp
  compatibility test was performed. Pure timestamp/sample goldens cannot establish
  real MediaCodec coverage. No APK was created or installed.

## Next phases — not implemented

**Phase2.2:** use DecodedWindow/capture tests to improve decoded PTS/coverage,
endpoint handling and antialiased resampling with bounded source reads. Add
permitted local/cache/range-container instrumentation and controlled decoder/export
metadata. Changes to conversion or acceptance require a new preprocessing version
and explicit compatibility/accuracy review.

**Phase2.3:** implement a BitChord-derived backend behind AnalysisEngine after the
Phase1 licensing/source-provenance gates. Add guarded JNI, bounded native
cancellation, proper numeric key transport, real beat arrays and calibrated evidence,
using this same corpus. Retain one backend in ordinary production, not duplicate
analysis. A native planning adapter must interpret its own confidence/coverage;
the legacy bridge intentionally refuses it.

No C++/JNI/model/build integration or Phase2.2 decoder/DSP correction begins in this
PR. The separate silence-level gain policy remains Phase3 work.
