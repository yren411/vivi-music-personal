# Phase 3.1 — experimental native-aware transition planning

Later interface note: [Phase 3.3.2](AUTOMIX_NATIVE_DJ_RELIABILITY_AND_PLACEMENT.md)
adds planner v2 duration-preservation policy and end-aware bounded search. This
document retains the original Phase 3.1 ranking, measurements and validation history.

This document preserves the Phase 3.1 implementation and benchmark checkpoint.
Phase 3.3 now connects it to MusicService behind explicit experimental opt-in, with
legacy remaining default. The owner reports the later combined **49-test** Android
selection passed, including all **six planner tests**, on S25 Ultra / Android 17.
See [current Phase 3.3 validation](AUTOMIX_NATIVE_DJ_ACTIVATION.md#current-validation-status--phase-33)
for reviewed-head CI/JVM provenance and the limits of that owner-reported evidence.

## Scope and source baseline

Starting main: **`e76bfcdd0c6fa47580fd2b3b4fab427930546198`**, merged PR #20.
That includes corrected PCM v3 and the owner's successful 18-test S25 Ultra /
Android 17 regression. Those are historical decoder/native tests, not device
validation of this planner. The existing BitChord/Orchard DSP at
`fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` is reused without changes or a second copy.

**Production remains legacy Kotlin analysis, `nearest-time-mono-22050-v1`, and the
existing `AutoMixPlanner`/`AutoMixController`.** This change does not fix or enable
audible DJ transitions. It adds a real, explicitly invoked experimental planner,
its analysis orchestration, tests and offline comparison tools. Playback gains,
filters, rate automation, queue promotion, effects sessions, settings and UI are
untouched. No planner toggle or production service hook was added.

## Architecture and implementation inventory

```mermaid
flowchart TD
  S[Explicit test/developer pair requests] --> W[Existing AnalysisWorkers: 1 / 2 / 4]
  W --> D[Existing corrected Android decoder: PCM v3]
  D --> O[Compact PCM transient / quiet-prefix observations]
  D --> E[Existing WindowAnalysisPipeline and BitChordNativeAnalysisEngine]
  E --> J[Existing JNI / internal 11025 Hz native DSP]
  J --> R[AnalysisResult with absolute track-relative beats]
  R --> C[Bounded completed feature cache: no PCM]
  O --> C
  C --> P[Caller polls two-track session; queues planning after both complete]
  P --> V[Native evidence validation]
  V --> T[Tempo hypotheses and fixed-index beat matching]
  T --> A[Bounded cue / overlap candidates and feasibility gates]
  A --> F[Ranked proposal OR explicit fallback recommendation]
  F -. Phase 3.2 only .-> X[Future executor: not connected in this phase]
```

Files under `app/src/main/kotlin/com/music/vivi/playback/automix/`:

| File | Implemented responsibility |
| --- | --- |
| `NativePlanningContracts.kt` | Versioned request, candidate/rank, plan, outcome and bounded diagnostic models |
| `NativePlanningEvidence.kt` | Native-specific validation; tempo hypotheses; fixed-index/stride matching |
| `PlanningSignalObservation.kt` | Optional supplied-PCM corroboration and synchronous experimental analysis wrapper |
| `NativeAwareAutoMixPlanner.kt` | Candidate generation, feasibility, ranking, cancellation and fallback |
| `ExperimentalNativePlanningSession.kt` | Bounded two-source jobs, reference-counted cancellation, cache reuse and nonblocking aggregation |
| `AndroidNativeAnalysis.kt` | New explicit `createPlanningAnalysis()` factory; no workers or analysis start merely by constructing it |

The planner operates on compact `PlanningWindowEvidence`. It never retains a
`DecodedWindow`, PCM buffer, player, network provider or Android context. Only
the analysis job borrows PCM, which is released normally after analysis.
`LegacyPlanningFeatures.require` continues rejecting native results. The new
planner never routes native confidence through that bridge.

## Native evidence admission

The native adapter requires the exact current BitChord backend/version and
`sinc64-blackman-flacframes-mono-22050-v5`. Backend IDs, confidence methods,
PCM provenance and source/rendition identity must agree. Future backend or input
versions require deliberate admission rather than an automatic confidence cast.

It validates requested/sample intervals, duration against the supplied track,
accepted frames, sorted source spans, complete source coverage of materialized
PCM, absence of repeated/synthetic source material, and native diagnostic frame/
rate consistency. A legitimate EOS clamp can have PARTIAL *requested* coverage
while still providing FULL effective/materialized coverage; this is not silently
promoted to a fully decoded request. Unknown sample coverage is rejected.

BPM must be finite and in 60–220; beat vectors contain at most 512 strictly
increasing in-coverage timestamps, with at least nine supporting beats. Median
beat period must agree with measured BPM within 2%; individual periods outside
200–1,200 ms are rejected. These are structural admission rules, not confidence
calibration or proof that percussion was detected. A false regular grid can pass
them. Raw native scores remain unchanged and identifiable; **the legacy 0.65
threshold is not used**. Missing rhythm, invalid evidence and technical analysis
failure are distinct outcomes. Cancellation and timeouts retain their categories.

Heuristic downbeats must be native bass-accent hypotheses and a subset of the
real beat vector. No key, vocal score, phrase, meter, legacy phase confidence or
tempo-stability probability is invented. Every proposal flags missing musical
structure/vocal/harmonic knowledge and uncalibrated confidence.

## Optional PCM corroboration and intro preservation

`NativePlanningAnalysis` wraps the existing decoder/pipeline only when explicitly
called. It computes `pcm-rms20ms-rise25pct-quiet60db-v1` from canonical mono
Float32 / 22,050 Hz PCM on the same analysis worker:

- 441-frame (20 ms) blocks; no additional resampler or BPM estimator.
- An RMS rise of at least `max(0.001, 0.25 * window RMS)` is an observed event.
- Source-timeline event positions are the block's actual start (20 ms resolution).
- At most 512 events; truncation is explicit and not trusted as a complete audit.
- A measured leading prefix with peak below 0.001 (-60 dBFS) can justify skipping
  that prefix **only when actual PCM begins at track time zero**.
- Nonfinite samples, wrong input version/format, invalid coverage and cancellation
  are handled before retaining observations. RMS/peak must agree with native
  supplied-PCM statistics; audit identity/interval/version must also agree.

At least 60% of each proposed matched pulse run must have a unique rise event
within 70 ms if that track has an audit. Events cannot be reused to support
several beats. This is an engineering corroboration rule tested on generated
material, **not a calibrated rhythm probability**, a vocal classifier or a
guarantee of human-perceived activity. Soft percussion, legato music and unusual
arrangements may be rejected. A later real-music corpus must assess these costs.
Static/noise false grids are not accepted merely for having a large native score.

Without an audit the planner can return a **timing-only, signal-unverified**
proposal with explicit warnings. It cannot claim verified activity, silence or
safe later intro skipping. Normal internal orchestration collects the audit.

Incoming cue candidates preserve the first second of unverified material at
most. A measured quiet prefix can extend that bound; beats within 70 ms before
the prefix end remain eligible to account for the observation resolution. A
later cue is never authorized from BPM or the first native beat alone. Very
quiet distinctive intros may contain content below this floor; that uncertainty
is explicit. Longer non-silent intro skipping needs future musical evidence.
At present an unseekable incoming track needs a supported beat exactly at zero;
pre-roll/offset scheduling for other nonseekable intros is not implemented.

## Tempo relationships and ambiguity

For measured tempos `Bo`, `Bi`, enumerate only strides `(1,1)`, `(1,2)`, `(2,1)`.
Their effective pulse tempos are `Bo/so`, `Bi/si`. A stride of two selects every
second **existing** native beat; no midpoints or corrected beat events are made.
Only hypotheses with `abs(Bo/so - Bi/si)/(Bo/so) <= 0.03` enter candidate search.

Each hypothesis retains measured and effective BPMs, absolute/relative tempo
difference, signed period difference in microseconds and the *hypothetical*
incoming rate `(Bo/so)/(Bi/si)`. Actual proposed playback rate is always **1.0**.
An 87/174 relationship is an unverified metrical hypothesis, not independent
confirmation that either detector found the true musical meter. No other ratios
are introduced. A 174 BPM recording may still be analyzed near 87 and remain
metrically uncertain even when its common pulses align.

## Beat correspondence, phase and drift

Cue anchors are actual absolute native beat timestamps. Selecting outgoing
beat `O[i]` and incoming beat `I[j]` means proposed overlap start `O[i]` and seek
cue `I[j]`. Their initial predicted phase residual is exactly zero **because
those are the proposed seek/start positions**, not because decoder timing was
measured or a grid was rotated. No sample origin is added again.

For each pulse interval `k`, compare fixed indices:

`residual[k] = (I[j + k*si] - I[j]) - (O[i + k*so] - O[i])`.

There is no nearest-neighbor re-pairing, octave normalization, phase rotation,
missing-beat insertion or tempo correction. An explicitly strided hypothesis
reports how many native subordinate pulses were intentionally unpaired. Tests
show that a missing pulse or initially aligned but drifting grid cannot be hidden.

The overlap is the measured outgoing interval `O[i + N*so] - O[i]`, not rounded
integer BPM. Predicted tempo drift is
`overlapUs * ((Bo/so)/(Bi/si) - 1)`. Median absolute residual must be <=25 ms,
95th percentile <=40 ms, maximum <=70 ms, and absolute measured final and
predicted drift <=70 ms. These are explicit timing tolerances, not musical
confidence values. All calculations use checked microsecond arithmetic; large
valid origins are tested without conversion to absolute floating-point seconds.

## Bounded candidate generation and hard feasibility

The default considers up to 16 evenly distributed outgoing anchors, 16 incoming
anchors, four pulse lengths (64/32/16/8), three metrical hypotheses and 3,072
candidate attempts. Configuration hard-caps these at 32 anchors per side,
12,288 attempts and 32 reported candidates; defaults retain 12 candidates.
Intro preservation and tempo gates usually reduce actual search substantially.
Cancellation/deadline checkpoints occur throughout validation, enumeration,
matching support and publication. No hidden wall-clock score affects ranking;
`AnalysisControl` carries the original caller deadline. Runtime is diagnostic.

Hard constraints include:

- `outgoingStart >= earliestOutgoingUs + preparationLeadUs` (checked addition).
- Both cue anchors lie within the supplied allowed cue regions. Those regions
  constrain **entry**, not the whole overlap; source sample coverage constrains
  the entire overlap separately.
- Incoming seeking is permitted; both cue/overlap intervals fit actual tracks
  and completely covered materialized audio. Duration metadata mismatch rejects.
- Current `AutoMixPlan` supports 0.5–48 seconds. Experimental defaults require
  at least three seconds and never exceed 48 seconds or available evidence.
  One 32-second window commonly limits duration more than the playback model.
- Every evaluated alignment includes both anchors and the final pulse, not just
  an initially attractive beat pair. Poor phase/drift or unsupported signal fails.

No quiet-outro requirement or low-RMS reward is applied. Stable, audible generated
rhythms can produce an overlap ending before the source file's natural end.
Unknown vocals, important remaining content and phrase boundaries remain flagged;
this is not permission to cut a real song prematurely.

## Ranking and plan contract

After hard gates, ranking is lexicographic, not an opaque weighted score:

1. More tracks with actual PCM corroboration.
2. Direct pulse correspondence before an octave hypothesis.
3. More supported pulse intervals, within all drift/coverage/signal constraints.
4. Lower 10 ms band of P95 phase residual.
5. Less incoming content skipped.
6. Outgoing cue closer to the requested earliest usable region.
7. Smaller exact P95 residual.
8. More heuristic downbeat anchors (last musical tie-break only).
9. Stable timestamp/relation tie-breaks.

Within the same 10 ms band, finer numerical improvements cannot justify throwing
away another intro beat. Crossing a band boundary can change that preference;
these are explicit engineering tie-breaks, not calibrated perceptual categories.
Length alone cannot pass timing or signal gates. Heuristic four-beat accents
never become verified phrases or a claim of 4/4 meter. Search truncation is visible.

`NativeTransitionPlan` includes planner version, pair generation, actual source/
rendition identities, duration/cue constraints, native/preprocessing profiles,
analyzed intervals, original earliest-position/preparation assumptions, both cues,
duration, stride hypothesis, alignment/drift, rank components, raw scores and
uncertainty flags. `TIMING_AND_TRANSIENT_SUPPORTED` and
`TIMING_ONLY_SIGNAL_UNVERIFIED` distinguish evidence sufficiency.

**Every plan still requires `PHASE32_NATIVE_PLAN_EXECUTOR`.** Timing feasibility
under today's duration/track limits does not mean today's legacy-only executor
can safely consume the representation. There is no `AutoMixPlan` conversion or
controller injection. A fallback outcome recommends ordinary safe playback or
independently sourced legacy planning; it never executes a fallback fade.

## Two-track workers, cache and invalidation

`ExperimentalNativePlanningSession` is an explicit developer batch using one
caller-owned existing `AnalysisWorkers<PlanningWindowEvidence>` exclusively.
The batch has one original `AnalysisControl`/deadline; it cannot silently renew
it. New batches require closing the previous coordinator before reusing its pool.
No second scheduler, native worker pool, coroutine scope or nested future waits
are introduced. The existing 1/2/4 worker choices, waiting queue 8 and LRU 64 are
unchanged. The coordinator itself retains at most eight pair tickets.

Completed immutable native window evidence uses keys comprising native backend/
version, corrected preprocessing version, exact requested microseconds, detail,
recording/rendition identity, signal-audit version and immutable-source policy.
Mutable sources are not retained. Technical failures throw a controlled worker
failure rather than becoming cached successes; valid lack of rhythm may be cached.
Plans themselves are never cached: position, generation and deadline are contextual.

Pairs share identical in-flight source requests by reference count. Invalidate a
ticket on seek, skip, shuffle, queue/rendition change or disabled experimental
evaluation. Only the last observer cancels that source job. Starting a replacement
before invalidating the old ticket preserves a still-needed outgoing analysis.
Completed valid evidence remains reusable under its original identity. The pool's
existing completed-result/invalidation race fix remains unchanged.

The caller's `poll()` is nonblocking. It queues aggregation only once both source
futures are already done; **workers never block awaiting child futures**. A
canceled/changed pair reports the relevant fallback even if its obsolete planning
future finishes. Opaque tickets use object identity, so a ticket from a closed
batch cannot read or invalidate the first pair in a new batch. Publication
rechecks cancellation under the session lock. Pool
ownership, explicit invalidation and current-position revalidation remain caller
responsibilities; there is intentionally no automatic queue listener in production.

## Diagnostics, comparison and private local evaluation

Explicit tests/developer tools expose planner/backend/preprocessing versions,
session-salted source digests, candidate counts, bounded top candidates, rejection
categories, cues, duration, raw scores, residuals/drift, cancellation and timing.
No source URL, signed token, title, artist, raw PCM or source filename is included
in the JSON report. Salts are caller-supplied and never emitted. Invalid/missing
features are not replaced with probabilities. Normal Stats for Nerds/export is
unchanged and continues reporting the actual legacy production engine.

`NativePlannerComparisonTest` runs both paths on the **same source Float PCM**:
the actual legacy capture/analyzer/planner, and actual corrected capture/native
JNI/new planner. This compares end-to-end alternatives, not planner superiority
on identical musical features. Preprocessing, analysis and planning durations are
reported separately. Nonunity legacy rates are honored in its diagnostic residuals;
Tier C fades have no claimed rhythmic alignment.

The versioned generated corpus has 31 pairs: seven offset equal-tempo pairs,
incompatible/slightly differing tempos, both octave directions, backbeats/hats,
quiet audio, actual leading silence, drift, 64-pulse overlap, 48/44.1 kHz source
material, short windows, and negative/identical-negative silence/noise/chord/sweep
pairs. Mathematical/contract tests add missing/malformed grids, partial/unknown
coverage, source mismatches, late starts, invalid durations, overflow, queue changes
and cancellation. Exact fixture recipes and parameters accompany raw results.

Beat evaluation uses one-to-one 30/70 ms matching with no best-phase or octave
normalization. Full-window and selected-overlap metrics are both retained. An
extrapolated grid through leading silence can have poor whole-window precision
even when its later selected interval aligns accurately. Half-time grids still
lose quarter-note recall and remain marked suspicious in benchmark classification.

See [measured comparison and reproduction guide](benchmarks/phase31-native-planner/README.md).
That directory contains generated-data reports only. A bounded optional local PCM
manifest can evaluate permitted recordings outside the repository, streamed one
pair at a time. Ground truth for those recordings is **unknown**, not zero and
not inferred from whichever backend sounds more confident. No commercial music
was downloaded, committed or listened to for this work.

## Validation and resource evidence

Exact commands, counts, measured timing/allocation summaries and numerical
fingerprint are in the benchmark guide and machine-readable reports. Timed planner
benchmarks preallocate compact evidence; decoding and native DSP are excluded.
Cold/JIT/shared-runner contention is not Android CPU, battery or thermal behavior.
Java thread allocation counts are cumulative allocations, **not peak retained heap**.
The retained set is bounded to 12 default candidates, <=512 beats/events per
track and compact metadata. Each alignment scratch vector has at most 65 longs.
There are no retained PCM arrays. Decoder/JNI working memory remains the existing
Phase 2.3B budget and is multiplied by active workers as documented there.

Native CTest/provenance and sanitizer suites are rerun without changing DSP source.
New JVM tests execute real host JNI separately from synthetic evidence tests used
for precise mathematical properties. Test fixtures labeled native-shaped are not
used as measured DSP outputs in the benchmark. Host-JNI comparison tests explicitly
skip when the optional host library is unavailable; they are not stubbed successes.
New instrumentation tests use real Android JNI and include local AAC/FLAC decoding.

## S25 Ultra device commands

New class: **six tests**. Use Android Studio Terminal with PowerShell at the repo
root and your existing working debug signing setup:

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Then the six new tests plus the unchanged 14 native / four decoder tests, **24 total**:

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Require the expected nonzero test count and zero failures/skips. The existing
property uses `com.yren411.tinium.decodercheck`; the normal installation/data are
preserved. Do not uninstall your primary app or overwrite keys. Restore the same
working debug key if the isolated install has a signature conflict. No new
signing or application-ID changes are part of this phase.

At the initial Phase 3.1 cloud checkpoint, no emulator/system image, KVM or connected
device was available. Planner Android tests were compiled, **not executed by Codex**;
the earlier 18 owner passes did not count as six planner passes. Subsequent owner-run
planner validation and the final Phase 3.3 combined run supply separate device evidence.
These later results do not rewrite the original benchmark measurements.

## Limitations and Phase 3.2 handoff

1. Native scores can be high on incorrect or half-time grids. Strided timing and
   simple PCM corroboration do not determine true meter or song structure.
2. No dependable vocals, key, phrase, buildup, important instrumental content,
   perceived loudness or energy trajectory is available from this native backend.
   Early active proposals therefore carry content-preservation uncertainty.
3. Transient corroboration uses a simple, versioned engineering rule; it needs
   authorized real-song evaluation for false accepts/rejects before activation.
4. Analysis covers bounded windows, not whole songs. No full-track structure
   inference, wider automatic network scans or production schedule change occurs.
   Generated benchmark windows are placed on declared 180-second fixture timelines;
   only their supplied 30/32 seconds are known. Real callers must reconcile the
   exact source duration with any rounded catalog/Media3 duration before admission;
   this phase deliberately does not silently repair duration mismatches.
5. Equal source timestamps do not prove equal decoder/output latency or accurate
   seeks. No audio is mixed by this phase and no listening-quality claim follows.

Phase 3.2 must explicitly consume this **separate** plan type, revalidate pair,
rendition, generation, current position/deadline and readiness, then prepare/seek
and synchronize decks against measured output behavior. It must preserve source
coverage and track-end bounds, handle early completion without restarting the
incoming deck or double-advancing the queue, and preserve effect sessions. Gains,
loudness continuity, fade/filter automation, actual cue accuracy, buffering and
listening validation remain separate implementation work. A source or metrical
uncertainty must never be hidden by relabeling this proposal as a full Tier A mix.

## Licensing

No upstream DSP/JNI code is copied or changed in this phase. Original Orchard /
BitChord notices, pinned provenance and AGPLv3-or-later license assets remain
intact. New native-planning/test glue is marked AGPL-3.0-or-later. Root licensing,
lyrics providers and Musixmatch are untouched. The owner's existing permission
assumption remains a development assumption; public combined-APK redistribution
clearance and source/license compliance remain separate release prerequisites.
No new licensing investigation or public APK release is performed here.


## Phase 3.3 caller

The opt-in service caller is documented in [Phase 3.3 activation](AUTOMIX_NATIVE_DJ_ACTIVATION.md).
Its planning session can use decoder-reported duration geometry and update the earliest
feasible outgoing time before planning starts. Compact source-matched active-RMS observations
accompany selected plans; session cache keys include that method version. These additions
retain the musical gates, timestamp coordinates and confidence semantics described above.
There is no native-to-legacy plan conversion. Original benchmark/device evidence remains
historical. Separate later owner-reported service-connected runtime success is documented
in the Phase 3.3 current validation summary; real-song musical quality remains unverified.

The service caller can observe compact completed analysis evidence before candidate rejection
through `onEvidence`; this records honest BPM/backend evidence without retaining PCM or
changing planner scoring.

### Phase 3.3 preprocessing identity update

Reviewed Phase 3.3 admission used corrected v5 FLAC-frame PCM. The subsequent
[Phase 3.3.1 AAC repair](AUTOMIX_REAL_WORLD_DECODER_RELIABILITY.md) advances corrected
admission to v6, retaining that FLAC route. The subsequent bounded Opus
preroll/endpoint-support repair advances it to v7. Historical v2/v3/v4/v5/v6
PCM/plans are not interchangeable with v7 and are rejected. Planner/executor algorithms, alignment
thresholds, confidence and gain policy remain unchanged. Earlier benchmark/device
results retain their original provenance; they are not v6/v7 Android validation.
