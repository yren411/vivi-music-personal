# Phase 2.2 pre-merge validation review

PR: [#17](https://github.com/yren411/vivi-music-personal/pull/17).
Reviewed starting head: `18d294428bf26282550d11ec63154319343412a5`.
See [the PCM contract and measurements](AUTOMIX_PCM_DECODING_AND_RESAMPLING.md).

## Runtime availability

A bounded environment check found no Android emulator executable, system image,
KVM device, USB device access, or running emulator/ADB process. No remote ADB server/device binding is configured. The earlier ADB
attempt also failed because its default user directory is read-only. There is no
usable connected-device/emulator runtime here. No emulator installation or lengthy
provisioning was attempted. **None of the four instrumentation methods ran here.**
Compilation and JVM checks are separate evidence, reported below.

## Confirmed findings and changes

1. **Reused ByteBuffer limit:** a new JVM regression failed before the fix with
   capacity 8, old limit 2, valid offset 4/length 4. Setting position before
   expanding the limit throws despite valid capacity bounds. The reader now sets
   the validated region's limit before its position. Valid PCM bytes, downmix and
   resampling are unchanged. MediaCodec normally supplies a valid current region;
   this is a reproduced buffer-contract defect, not proof it caused the phone log.
2. **Deterministic active-decode cancellation:** the instrumentation test formerly
   canceled after 120 arbitrary checkpoints. That count can be reached in different
   stages across Android/container implementations. A nullable synchronous decoder
   validation observer now cancels after actual nonempty PCM output is accepted.
   The test requires positive accepted-frame count and the PCM-conversion stage,
   then runs another decode to check continuation. Production uses no observer.
3. **Window completion versus EOS:** the seek test no longer requires input EOF
   merely because the requested/clamped interval is complete. If codec EOS was
   observed, an input-end cause must be known; otherwise full effective coverage
   is required. Natural EOF and requested-boundary EOS remain distinct. EOS flags
   are recorded as soon as the output buffer is observed, even if conversion or
   cancellation subsequently fails.
4. **Safe phone test installation:** ordinary debug and release builds share the
   app ID but use different signing identities. Running connected debug tests
   over an installed release-signed beta would fail to install; uninstalling the
   beta would risk its data. The opt-in `-PautomixDecoderTests=true` flag adds
   `.decodercheck` to the **debug test target only**, with a separate app data
   directory and provider authorities. Normal debug and all release builds retain
   `com.yren411.tinium`. Signing configuration, keys, versions and release workflows
   are unchanged. This flag does not select corrected production preprocessing.

## Timestamp review

- Frame PTS remains `bufferPTS + frameIndex/sampleRate`, with a rational clock
  across contiguous corrected buffers. Integer-microsecond jitter is counted;
  a real one-source-frame hole is rejected rather than filled.
- Additional tests cover negative priming PTS, preceding-sync trimming, nonzero
  origins and irregular 257-frame buffers at 8/11.025/16/22.05/32/44.1/48/96 kHz.
  Source coverage and sample origin remain distinct and no padding is counted.
- Existing tests retain duplicate/overlap trimming, regression/gap failure,
  unknown coverage, partial EOS, format changes and cancellation assertions.
- AAC delay/padding, actual Samsung codec buffer timestamps, mid-stream format
  behavior and remote/cache delivery still need Android execution. No musical
  thresholds, PTS-gap tolerance, resampler/filter contract or gain policy changed.

## Four-worker memory and cache review

The corrected path allocates a bounded mono source window, an output array and
one FIR table per job. For a maximum 32-second window, calculated core array
storage per job is approximately 8.58 MiB at 44.1 kHz, 9.10 MiB at 48 kHz and
28.31 MiB at 192 kHz. Four concurrent jobs therefore imply approximately 34.3,
36.4 or **113.3 MiB**, respectively, before codec buffers, DataSource blocks,
analysis arrays, unreclaimed allocations and the rest of Tinium. These are
calculated bounds, not measured phone heap peaks.

A new desktop JVM stress test holds four full 192-kHz/32-second captures and then
resamples them through the actual four-worker pool. It verifies distinct bounded
worker threads, 705,600 output frames/window, complete source coverage, DC accuracy
and no test PCM cache entry. It is a JVM load test, not Android memory/battery proof.
A streaming/pooled implementation could reduce future memory, but no large rewrite
or new scheduler is justified in this review. Keep corrected activation blocked
until device memory/GC/thermal tests pass.

Production caches only musical features, not DecodedWindow PCM. Both cache and
in-flight dedup keys include backend/version, preprocessing version, rendition,
window and detail. The selected decoder and pipeline identity are immutable for a
job. Existing tests verify cross-version isolation, cancellation of obsolete work,
failed/partial rejection and result/diagnostic provenance. Legacy remains
`nearest-time-mono-22050-v1`; corrected remains
`sinc64-blackman-mono-22050-v2`. The buffer/EOS robustness fixes do not change the
corrected filter, downmix, time mapping or valid numerical PCM contract.

## Running the four Android tests on a Galaxy S25 Ultra

No source-code editing, production keystore export, release build, or switch of
normal AutoMix preprocessing is required. Use the isolated debug target below.
Android Studio/SDK and a computer connected to the phone are required; a cloud
Codex session cannot access the USB phone automatically.

1. In Android Studio, get `yren411/vivi-music-personal` and select branch
   `codex/automix-pcm-resampling-diagnostics` (or updated `main` after merging).
   Select **JDK 21** for Gradle and install **Android SDK 37** using SDK Manager.
   Let the project sync. Use the ordinary local debug signing setup; never copy
   release secrets into the project or change any permanent keystore.
2. On the phone, open Settings → About phone → Software information, tap **Build
   number** seven times, then enable **USB debugging** in Developer options.
   Connect a data-capable USB cable, unlock the phone, and accept the computer's
   debugging authorization. Run `adb devices` from the SDK platform-tools folder;
   the phone must show `device`, rather than `unauthorized` or `offline`.
3. Keep only the intended test phone connected. In the project's Android Studio
   Terminal, use the exact command for your shell. Quote each **whole** `-P`
   argument, especially the dotted instrumentation property; quoting only its
   value is insufficient in Windows PowerShell.

Windows PowerShell:

```powershell
.\gradlew.bat :app:connectedArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

Bash:

```sh
bash gradlew :app:connectedArm64GmsDebugAndroidTest "-PautomixDecoderTests=true" "-Pandroid.testInstrumentationRunnerArguments.class=com.music.vivi.playback.automix.AndroidWindowDecoderInstrumentedTest" --no-configuration-cache --no-daemon --stacktrace
```

The first retry disables configuration-cache reuse for a fresh configuration.
It does not delete caches or change repository settings.

The isolated target is `com.yren411.tinium.decodercheck`; the test package is
`com.yren411.tinium.decodercheck.test`. Your installed `com.yren411.tinium` beta,
its signing identity and data remain separate. The tests decode generated local
AAC/FLAC assets; they do not play music or fetch streams. The two preprocessing
implementations are selected explicitly inside the test harness only. A normal
Tinium service still defaults to legacy preprocessing, even in the test target.

For FOSS coverage, run the same command with
`:app:connectedUniversalFossDebugAndroidTest` instead. Run variants sequentially.
Do not omit `-PautomixDecoderTests=true` on a phone holding a release-signed beta.
If installation reports a signature/version conflict, stop and check the printed
target package; **do not uninstall your real Tinium app to make a test pass**.

4. Expect **four test methods**, zero failures/errors. Follow the Gradle output's
   HTML report link under `app/build/reports/androidTests/connected/`; raw results
   are under `app/build/outputs/androidTest-results/connected/`. Save the report,
   device/Android version and exact commit. A successful APK installation alone
   is not a test pass. Share assertion failures and controlled decoder details;
   do not send unrestricted logcat with private account/stream information.
5. Optional cleanup removes only the separate test packages:

```sh
adb uninstall com.yren411.tinium.decodercheck.test
adb uninstall com.yren411.tinium.decodercheck
```

Afterwards you may disable USB debugging. These local commands create/install
**debug test APKs only** when you choose to run them. No APK was packaged or
installed during this cloud review, and no release workflow was dispatched.

Passing these four methods validates the generated local-container cases on that
device. It does not prove streaming/range compatibility, musical quality, external
EQ behavior or sustained four-worker memory/thermal performance. Those remain
separate activation gates in the main Phase 2.2 document.

## Merge recommendation

Merge is reasonable **as a staged foundation with legacy production preprocessing
retained**, once the final JVM and compile results below are green. This is not
approval to activate corrected preprocessing or begin Phase 2.3. Device decoder,
source-format and four-worker memory testing still gate activation. No release,
version reservation, musical analyzer, planner or EQ-session change is included.

## Final cloud results

- **GMS: 194 focused JVM tests executed and passed; FOSS: 194 executed and passed.**
  Zero failures, errors or skips in both; fresh AutoMix/effects XML results were
  counted. This includes existing analyzer parity/characterization, controller,
  workers/cancellation, cache isolation, diagnostics privacy and effects tests.
- The new reused-buffer regression was run before its fix: **one test executed,
  one failed** with IllegalArgumentException. It passes in both final full suites.
- The four-worker maximum-rate stress and new timestamp tests passed on JVM.
  No Android peak-memory, battery or audible-output measurement was performed.
- Both default GMS/FOSS debug and universal release Kotlin configurations compiled.
  Default Android-test source tasks compiled/reused valid outputs. Both isolated
  GMS/FOSS Android-test Kotlin compilations then executed successfully.
- Parsed generated manifests confirmed normal debug targets and release targets
  use `com.yren411.tinium`; with the opt-in flag both test targets use
  `com.yren411.tinium.decodercheck`, with matching `.decodercheck.test`
  instrumentors. Release IDs stay unchanged even when the test flag is present.
- **Four Android methods compiled; zero executed.** There was no device/emulator
  run, APK packaging/install, release build dispatch, publication or merge here.
- `git diff --check` passed. The new test-only package flag, buffer fix and
  diagnostic ordering do not activate corrected preprocessing or change the
  preprocessing ID, algorithms, signing configuration or external EQ lifecycle.

Recommendation: **merge PR #17 with its legacy production default retained**, after
normal PR checks are green. The corrected path remains experimental and gated by
the device/source/memory validation above. This review does not start Phase 2.3.

Reproduce the cloud checks (Java 21 / configured Android SDK):

```sh
bash gradlew :app:testArm64GmsDebugUnitTest --tests 'com.music.vivi.playback.automix.*' --tests 'com.music.vivi.playback.effects.*' :app:testUniversalFossDebugUnitTest --tests 'com.music.vivi.playback.automix.*' --tests 'com.music.vivi.playback.effects.*' :app:compileArm64GmsDebugAndroidTestSources :app:compileUniversalFossDebugAndroidTestSources :app:compileUniversalGmsReleaseSources :app:compileUniversalFossReleaseSources --no-daemon
bash gradlew :app:compileArm64GmsDebugAndroidTestSources :app:compileUniversalFossDebugAndroidTestSources :app:processUniversalGmsReleaseMainManifest :app:processUniversalFossReleaseMainManifest -PautomixDecoderTests=true --no-daemon
```

Gradle reused unchanged resources/Java and some aggregate Sources tasks; the final
focused JVM test tasks executed rather than returning up-to-date/cache outcomes.
The isolated instrumentor manifests and Kotlin sources were separately compiled;
none of these commands invoke connected tests or APK signing/packaging.

## Windows PowerShell / protobuf 0.9.6 configuration failure

The reported exception was `MissingPropertyException: No such property:
sourceSets for class: java.lang.String` at `ProtobufPlugin.groovy:135`, before
compilation. This is an **Android project-property collision**, not evidence of a
Java version mismatch or a missing Android sourceSets API.

The [actual protobuf 0.9.6 source](https://github.com/google/protobuf-gradle-plugin/blob/v0.9.6/src/main/groovy/com/google/protobuf/gradle/ProtobufPlugin.groovy#L135)
calls `project.android.sourceSets.configureEach`. A bare `-Pandroid` creates a
String project property named `android` that shadows the real Android extension
for this Groovy lookup. The registered extension still exists, but the plugin
receives the String instead. The earlier Windows command left the dotted
`-Pandroid.testInstrumentationRunnerArguments.class=...` token unquoted; PowerShell
argument parsing can turn such a token into the bare property plus a remaining
fragment. The corrected commands above quote the entire property argument.

### Investigation and compatibility evidence

The cloud JVM is **21.0.12.1**, matching the reported Windows launcher version;
the daemon criteria require Java 21. No JDK change is needed for this exception.
The repository uses AGP **9.1.1**, wrapper **9.3.1**, project Kotlin **2.3.10**,
Gradle-embedded Kotlin **2.2.21** / Groovy **4.0.29**, protobuf plugin **0.9.6** and
protoc/runtime **4.33.5**. `android.newDsl=false` retains the legacy Android DSL
used by this plugin; leave it intact. The application plugin precedes protobuf
in `app/build.gradle.kts`, and protobuf waits for Android via `withPlugin` callbacks
before `doApply`. Reordering plugins or changing Kotlin DSL is unnecessary.

Two fresh Linux configurations, with configuration-cache reuse disabled, isolate
the failure independently of Windows and Java:

| Input | Groovy project.android | Registered Android extension | Result |
|---|---|---|---|
| Bare `-Pandroid` | `java.lang.String` | `BaseAppModuleExtension_Decorated` | Same exception at protobuf line 135 |
| Intact dotted `-Pandroid.testInstrumentationRunnerArguments.class=...` | `BaseAppModuleExtension_Decorated` | Same Android extension | Configuration succeeds; dotted property present, bare property absent |

Four GMS/FOSS debug/release protobuf generation tasks were then **forced to run**;
all **144 generated Java/Kotlin source hashes remained identical**. All 20 normal
ABI × GMS/FOSS × debug/release protobuf-generation tasks remain registered. The
GMS/FOSS isolated Android-test compilation checks with intact arguments succeed;
unchanged compilation outputs may be reused. No dependency, plugin version/order,
Gradle setting, proto schema, generated source, flavor, signing or AutoMix code is
changed by this command-documentation correction.

The Windows shell itself and Android instrumentation were not executed in cloud.
The supplied Windows command and the exact reproduced failure establish the
property-collision mechanism; the corrected PowerShell invocation is the local
retry. This is not a claim about every AGP 9/new-DSL configuration or future plugin
compatibility.

If the same error persists with the fully quoted command, check for an accidental
**bare** `android=` entry in project/user `gradle.properties`, an
`ORG_GRADLE_PROJECT_android` environment variable, or another explicit `-Pandroid` /
`-Dorg.gradle.project.android` input. Remove only that accidental bare property;
do not remove legitimate dotted properties such as `android.newDsl=false`.
There is no reason to delete signing files, regenerate keys or clear all Gradle
caches to resolve this collision. Do not dump environment/property files containing
credentials when collecting diagnostics.
