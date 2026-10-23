# AAC source-boundary coverage — Phase 3.3.3

Base: merged PR #25, `c102d080ab91ddf8d80cf1707c84c6c2bbc2cf68`.
Legacy remains the production default; Native DJ remains an explicit experiment.
This phase repairs a demonstrated **nonzero AAC seek/preroll defect**. It does not
rebase positive presentation origins, fill gaps, extend EOS, or claim that the owner's
private AAC files have been inspected. The 137 ms initial offset and short EOS remain
strict partial coverage unless genuine source evidence supports the requested interval.

## Current validation status

The owner reports that the ten new AAC tests passed, followed by **all 79 tests in
one expanded Android regression selection** on their Samsung Galaxy S25 Ultra / Android 17.
That selection includes the previous 69 decoder/native/planner/executor/service/reliability
cases plus all ten new AAC boundary cases. These are owner-reported physical-device results;
Codex did not execute them. No raw Android XML, exact execution date or independently
verified installed commit SHA was supplied. The instructions selected PR #26 implementation
head `fcde6cbab169fa72541de9860cc4afc302641218`; that association is not an installed-SHA audit.

Both required GitHub workflows passed on that reviewed implementation head:
[CI & Debug Build](https://github.com/yren411/vivi-music-personal/actions/runs/39252392567)
and [Build PR](https://github.com/yren411/vivi-music-personal/actions/runs/39252392554).
The 1,126 JVM passes below remain earlier local executions, not new runs triggered by
this documentation follow-up. Existing nonblocking lint policy is not a clean-lint claim.

Generated-media Android validation is therefore owner-reported passed. Private-source
edit metadata, exact real-song endpoints, acoustic alignment and listening quality remain
unverified. The next step is the authorized real-song retest below. Legacy stays default;
no merge, release or broader activation follows automatically from these results.

## Evidence boundaries and owner baseline

The owner reports S25 Ultra / Android 17, Tinium 6.0.8 (77), Performance / four workers,
PCM v7, planner v2 and executor v5. Their latest session retained three native pairs,
one plan/observed/completed native mix, two native fallback pairs, one legacy handoff
and two completed handoffs overall. These are attempts, not distinct identified songs.
Track names below come from the owner's account; no commercial recordings, private URLs,
cache contents or independently verified song identities were supplied to Codex.

The owner reports **Eva Maria → El Viejo Joven** completed natively: duration 163,865,000 µs,
outgoing cue 159,274,221 µs, incoming cue 290,249 µs, overlap 3,234,207 µs, endpoint
162,508,428 µs, remaining 1,356,572 µs (99.17214% preserved), one promotion. This observation
is retained, not claimed as a new run. Planner `tinium-native-dj-planner-v2-duration-preservation`
and executor `tinium-native-dj-executor-v5-bracketed-media-clocks` are unchanged.

The failed **Oye Como Va → Eva Maria** attempt had these separate signatures:

| Requested interval, µs | Observed source coverage, µs | Other evidence |
| --- | --- | --- |
| 0..32,000,000 | 137,006..32,000,000 | First compressed and output PTS 137,006; delay/padding 0; 44.1 kHz stereo; accepted 1,405,159; output 702,557; no interior gaps |
| 123,063,000..155,063,000 | 123,065,759..155,062,856 | Seek returned 123,042,539; codec delay 1,024; natural EOS; no interior gaps |

Both were reported fully cached, `range=NOT_REQUIRED`, using `c2.android.aac.decoder`.
No full original report or encoded bytes are assumed beyond the supplied task summary.

## Confirmed seek defect and repair

The v7 corrected decoder seeks to the requested nonzero time with PREVIOUS_SYNC, then
configures a **fresh** codec with the track's encoder-delay metadata. The codec still owns
that front trim. A previous sync packet alone does not guarantee enough preceding audio:

```text
returned packet PTS                   123,042,539 µs
1,024 frames / 44,100 Hz                  23,219.954649 µs
next complete output packet          123,065,759 µs
requested start                      123,063,000 µs
missing leading coverage                  2,759 µs
```

The reported values follow the existing code path exactly. A deterministic source-frame
replay reproduces the same leading loss before repair. This is not an interior gap,
fractional timestamp tolerance problem, or reason to disable delay trimming.

`AacAnalysisWindow` requests earlier **real compressed packets** for corrected AAC only:

```text
supportFrames = declaredEncoderDelayFramesOrZero + 2,048
supportUs     = ceil(supportFrames * 1,000,000 / configuredSampleRate)
seekTarget    = max(0, requestedStart - supportUs)
```

Two AAC-LC 1,024-frame units bound synthesis warmup and common doubled-rate output;
this is a conservative analysis support budget, not universal AAC format certification.
The actual output still has to pass all existing source-continuity/coverage checks.
At 44.1 kHz / delay 1,024 the budget is 69,660 µs, target 122,993,340 µs. PREVIOUS_SYNC
retains the real packet before that target. If support reaches zero, **no seek** is issued,
preserving negative priming packets exactly as v7 does. Unknown delay is not fabricated:
it stays null and only the bounded warmup budget is used. Metadata outside the explicit
8,192-frame delay / 8..192 kHz resource limits fails `UNSUPPORTED_AAC_TIMING`.

The codec's declared front delay remains configured. No application front trim or PTS
adjustment is added. Capture drops actual decoded preroll before the request; only the
retained real samples enter the existing bandlimited converter. Source-byte limits remain
3 MiB uncached / 12 MiB fully cached, with the same four 64 KiB bridge blocks, deadlines,
workers and queue. At 8 kHz the support budget is at most 1.28 s; no whole-song PCM or
new scheduler is allocated. Tail ownership remains `AacAnalysisTailTrim` without changes.

## Positive start: reproducible valid edit, private source cause unconfirmed

Both first compressed and first output timestamps are already +137,006 µs in the owner's
summary. There is no evidence that capture/resampling discarded those first 137 ms.
The initial corrected read does not seek to zero, so this is not fixed by changing
the nonzero seek policy. No interior gaps means continuity **after** the first sample;
it does not establish that the requested beginning exists.

The new genuine MP4 fixture has a valid two-entry edit list: an empty edit of **6,042
movie ticks at 44,100 Hz**, then a normal media-time-zero edit. Its first compressed PTS
is 6,042 source ticks, floored to **137,006 µs**. All `mdat` bytes have the same SHA-256
as the earlier AAC fixture: no access unit was removed. FFmpeg independently reports the
positive first PTS. The host capture reproduces exactly 1,405,159 accepted frames,
702,557 canonical frames and `137006..32000000 / PARTIAL`, without source fabrication.
That is **Outcome B**, a legitimate leading presentation gap, not damaged-media proof.

This independently demonstrates a possible container mechanism for the exact signature.
It does **not** establish that the owner's private rendition has that edit list rather
than another container start offset or missing initial access units. Actual MediaCodec
and edit behavior are covered by the new phone tests; private-source edit metadata has
not been supplied. The implementation therefore preserves this partial result and fallback.
There is no arbitrary 137 ms rebase or second application of container edit offsets.

Reference implementation inspected (not copied): the existing pinned
[MPEG4Extractor edit-list handling](https://github.com/LineageOS/android_frameworks_av/blob/f13e44db891bf5caf478062365372a8fbc23e45f/media/module/extractors/mp4/MPEG4Extractor.cpp)
adds initial empty edits to packet composition times and exposes gapless delay/padding.
[CCodecBuffers](https://github.com/LineageOS/android_frameworks_av/blob/f13e44db891bf5caf478062365372a8fbc23e45f/media/codec2/sfplugin/CCodecBuffers.cpp)
installs the front skip/tail cut. These sources explain a platform mechanism; they are not
an audit of Samsung's exact Android 17 binary. Normal playback uses the existing Media3
source/presentation timeline. This repair never substitutes a requested-zero timeline for
the actual platform timestamps or assumes cross-extractor coordinates are interchangeable.

## Natural EOS: decoded samples versus rounded container duration

The generated long fixture contains **6,838,272 actual presentation PCM frames** at
44.1 kHz. Its movie/edit timebase is 1,000 Hz, advertising 155,063 ms. Its media duration
is 6,839,296 frames including 1,024 priming frames. Independent FFmpeg decoding produces
exactly 6,838,272 frames, with an endpoint of **155,062,857.142857 µs**. The rounded
movie endpoint exceeds that by 142.857 µs; integer codec PTS anchoring in the replay
produces coverage ending 155,062,856 µs, leaving the observed **144 µs** deficit.
The fixture manifest preserves raw `stts`, `mdhd`, edit-list and ffprobe observations;
an inferred packet duration alone is not decoded-frame evidence.

Preroll recovers the outgoing head but does not create final frames. The exact reported
32-second geometry therefore remains PARTIAL after repair. A separate request ending
at 155,000,000 µs, inside genuine PCM, changes from PARTIAL to FULL with 122 additional
real leading frames. No universal endpoint clamp, duration rewrite or six-frame tolerance
is introduced. A presentation endpoint could be used only with authoritative rendition
provenance; the supplied private-source summary does not establish that provenance.
The rounded-duration mechanism is independently reproduced, not asserted as the confirmed
metadata structure of the owner's original file.

## Source, gapless and cache integrity

The permitted source factory and the pinned rendition identity still feed `RangeSource`
→ Android MediaExtractor → software MediaCodec → codec-owned front trim → unchanged
application-owned AAC tail → corrected capture → bandlimited mono Float32/22,050 Hz
→ validated native analysis → existing planner admission. Raw extractor PTS are queued
unchanged. Negative initial packets, the 14-frame retained-tail regression, natural versus
request EOS, and strict half-frame/whole-frame discontinuity rules are preserved.
No FLAC extraction or Opus support-window policy changes are made.

`fullyCached` is a publication-time byte-span predicate over the known rendition length in
either playback or download cache; it is not a certificate of valid MP4 presentation times,
all original access units, accurate catalog duration, or immutable upstream metadata.
Both owner failures can occur with complete local bytes. No cache cause is inferred.
Source/rendition pinning, authorized URLs, cache keys and source resolution are unchanged;
no cache reset/removal is required. New Android tests compare local, complete cache,
prefix cache and range-backed uncached reads of identical generated bytes. The range
adapter is local fixture transport, not a measured public streaming/CDN test.

## Identity and diagnostics

Corrected identity is **`sinc64-blackman-aac-preroll-mono-22050-v8`**. V7 remains explicitly
`BANDLIMITED_V7`; v2..v6 and legacy identities remain distinct. Earlier support packets can
change real PCM, source coverage and musical evidence, so retaining the v7 identity would
be incorrect. The format is not known when analysis jobs/cache keys are created: the
single corrected-decoder profile conservatively advances to v8 for all its codecs, even
though FLAC/Opus PCM behavior is unchanged. Profile/cache/in-flight identities and native,
planner and executor profile validation already use that shared constant. Tests separate
v7/v8 in-flight jobs, completed entries after `cancelAll`, and reject v7 PCM before JNI.
Native DSP/backend, evidence formulas, planner v2, executor v5 and signal-observation
algorithms are unchanged; no legacy confidence or timing threshold is altered.

The additive numeric `aacBoundarySchema=1` export reports configured/observed rate,
declared delay, requested support-frame/time budget, observed preroll input-packet count,
decoded frames actually discarded before the interval, missing leading/trailing µs and
ceiling frame-duration estimates. The latter are estimates from µs coverage, not decoded
missing samples. Existing first input/output/accepted intervals, EOS, duration basis,
byte/cache/range and last source-frame-clock diagnostics remain. Codec-owned front trim
is not falsely reported as a directly counted application discard. No title, URI, path,
token, PCM or private container metadata is added to production diagnostics.

## Validation and measured before/after evidence

See [evidence inventory](benchmarks/phase333-aac-boundary/README.md). New deterministic
tests replay the exact leading loss, positive start and trailing deficit; test all preserved
tail rules and genuine missing/duplicate frames; and compare nonconstant mono/stereo
source-frame order at 44.1/48 kHz. The measured host model parity is **max absolute error
0.0** in all four cases. This is a source-frame replay, not Android codec parity.
Three encoded fixtures regenerate to identical SHA-256 values. Old fixtures are unchanged.

There are **10 new** `AacBoundaryCoverageInstrumentedTest` methods. They execute actual
MediaExtractor/MediaCodec, forward-versus-seek PNS-disabled AAC PCM parity, negative
priming, valid edit offsets, natural EOS, omitted compressed packets, cached/range sources,
both source rates/channels, real BitChord JNI and actual MusicService planning/fallback.
The service harness gets only optional generated assets/durations; its previous defaults
and all 69 earlier acceptance methods/assertions are retained. Expanded acceptance is
**79**; the earlier observation-only startup probe is separate and excluded.

At the initial cloud-validation checkpoint, physical execution remained pending: there
was no emulator image/KVM or exposed USB device, and an adb enumeration attempt also hit
a read-only home preference path. The earlier owner-reported 69-test pass belonged to v7.
That checkpoint is now superseded for generated-media device validation by the owner's
new ten-test and combined 79-test passes described above. Codex still claims no Android
execution. Native DSP/JNI/C++/NDK settings are unchanged; earlier CTest/sanitizer results
remain historical, not rerun results for this PR. Local JVM/build/packaging outcomes and
the separately attributed owner follow-up are recorded in `validation.json` and the PR.

Executed locally on this implementation: **563 Arm64 GMS + 563 Universal FOSS = 1,126
JVM tests passed**, zero failures/errors/skips, including **17 actual host-JNI cases per
variant / 34 executions**. The focused selection passed 39 tests. Both debug app and
instrumentation APK variants assembled successfully (full matrix: 3m 58s). Actual APK
inspection passed all four JNI ABIs, ELF/ZIP 16 KB alignment, JNI exports and license assets;
DEX inspection confirmed current v8 policy code plus unchanged planner v2/executor v5.
Those local results did not execute Android methods; the later ten-test and expanded
79-test passes are separately owner-reported physical-device evidence.

## S25 Ultra / Android Studio PowerShell

Use the repo root and the existing working debug key. No normal Tinium uninstall, data
deletion, cache wipe, key change or application-ID change. The property below selects
`com.yren411.tinium.decodercheck`. Only the two service methods play generated audio;
pause normal music and use comfortable volume. Run only one test command at a time.

```powershell
git fetch origin
git switch codex/automix-aac-boundary-coverage
git pull --ff-only
.\gradlew.bat :app:assembleArm64GmsDebug :app:assembleArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" --no-configuration-cache --no-daemon --stacktrace
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AacBoundaryCoverageInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

The connected task installs/updates the isolated test packages. Expect **10 executed,
zero failures/skips**. Some cases deliberately assert PARTIAL coverage/native rejection
for genuine unsupported intervals; a final diagnostic showing PARTIAL is not alone a
test failure. A zero-test invocation is not validation. To rerun one method append
`#methodName` to the actual class selector, for example
`#seekPcmMatchesIndependentForwardCodecAtTheExactReportedStart`.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase333-aac-boundary-last.txt
& $adb exec-out run-as com.yren411.tinium.decodercheck cat files/phase33-service-last.txt
```

These contain the latest decoder/service case, not every case. Preserve failures from
`app/build/reports/androidTests/connected/` and `app/build/outputs/androidTest-results/connected/`.
Do not paste signed URLs/private paths. If signing conflicts, restore the existing debug-key
configuration; do not uninstall the normal app or silently replace its signing identity.

Previous **69-test** regression:

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest,com.music.vivi.playback.automix.NativeDjReliabilityInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Expanded **79-test** selection (owner reports all 79 passed; retained for future regression):

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest --rerun "-PautomixDecoderTests=true" "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AacBoundaryCoverageInstrumentedTest,com.music.vivi.playback.automix.RealWorldDecoderInstrumentedTest,com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest,com.music.vivi.playback.automix.BitChordNativeAnalysisInstrumentedTest,com.music.vivi.playback.automix.NativeAwarePlannerInstrumentedTest,com.music.vivi.playback.automix.NativeDjExecutionInstrumentedTest,com.music.vivi.playback.automix.NativeAutoMixServiceInstrumentedTest,com.music.vivi.playback.automix.NativeDjReliabilityInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

## Authorized real-song retest and remaining work

With generated-media Android validation now owner-reported passed, use the already
installed isolated app for controlled listening
or update it explicitly with `:app:installArm64GmsDebug -PautomixDecoderTests=true` using
the same debug key. Instrumentation does not update the normal Tinium app. Enable AutoMix
and select Native DJ Experimental through its existing settings; legacy remains available.
Confirm reports identify **v8**, planner **v2**, executor **v5**, and the actual pinned rendition.

Repeat **Oye Como Va → Eva Maria**, then **Eva Maria → El Viejo Joven** with authorized
sources. Compare requested/effective/actual intervals, first input/output PTS, delay,
seek target/returned PTS, support packets, accepted/canonical counts, missing edge estimates
and EOS. A source that actually begins at +137 ms or ends before the request must still
fall back. Native success is not the acceptance criterion for unsupported audio.
If both analyses are genuinely full, verify they reach unchanged planner admission and
only supported plans execute; check one promotion, preservation, drift and continuity.
Different v8 evidence can legitimately yield different cues; do not force previous cues.

Then test several natural transitions under consistent output conditions. Record full
coverage, honest partial rejection, musical planner rejection, execution fallback/completion,
remaining outgoing material and subjective impressions separately. Preserve unfavorable
results. No listening history/audio upload is automatic. Do not erase cached songs.

Actual private-file edit/start metadata and exact playable EOS remain unverified. Public
streaming/expired URLs, Bluetooth/acoustic timing, vocals/phrases/harmony and extended
resource behavior are not solved by this decoder patch. Phase 3.4 effects are not started.
No filters, gain changes, synchronization changes, distribution signing, release/version
allocation, licensing relabeling, automatic merge or production-default activation occurs.
