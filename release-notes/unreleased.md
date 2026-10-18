# Unreleased

This is an unversioned draft for review. No release version or tag is assigned.

## Branding

- Use the updated Tinium logo with its purple gradient background during startup,
  in the onboarding introduction and completion transition, and beside the
  Tinium title in the main app bar.
- Use the same full-color artwork in the About tile and Wrapped introduction
  and conclusion, with proportional scaling and consistent colors across themes.
- Use the background logo for regular launcher icons and full-color app-update
  notifications, with the same playback notification fallback when album artwork
  is unavailable. Actual album artwork and playback controls remain unchanged.
- Keep the transparent Tinium mark for compact UI, player placeholders, sharing
  and Android's required monochrome status-bar/themed icons.
- Reduce only the system startup logo's display size, adding transparent margins
  around the unchanged gradient artwork. Launcher icons and in-app branding retain
  their existing appearance; Samsung's launcher animation remains OS-controlled.

## Fixes

- Correct the About screen's build label for beta releases, while retaining nightly
  labels and identifying ordinary debug builds as development. Display the installed
  application's version name and build number.

## AutoMix

- Add opt-in, analysis-driven AutoMix above Crossfade in Player and audio settings.
  It analyzes bounded audio windows for rhythm, key, energy and transition level
  balancing, then executes actual dual-decoder overlaps with beat-aligned timing,
  conservative tempo matching and selective bass/filter processing.
- Add persistent Efficient, Balanced and Performance analysis modes with 1, 2 and
  4 bounded workers. Preserve the previous Crossfade choice while the modes are
  mutually exclusive, and explain software-audio conflicts and safe fallbacks.
- Credit BitChord's reference architecture. Tinium's Android implementation is
  independent; no BitChord/Orchard code, native libraries or ONNX model assets
  are incorporated into the application.
  Vocal and phrase estimates are approximate DSP heuristics; perceived audio
  quality and Android decoder/output behavior still require phone testing.

## Release preparation

- Add project instructions for Codex to prepare accurate release-note drafts
  alongside meaningful changes, using a chosen release version when assigned or
  this unversioned draft otherwise.
- Let unpublished beta and production test builds use the accumulating draft
  automatically when version-specific notes are missing, or clearly labeled
  internal-testing notes when no meaningful draft exists.
- Preserve exact candidate notes, source hashes and review status in immutable
  artifacts. Block unreviewed drafts/placeholders from public publication, and
  require reviewed version-specific source notes plus explicit review confirmation.
  Coordinated version codes, permanent signing and manual publishing remain intact.

## Checks before assigning a release

- Review wording and copy only changes intended for the chosen fresh version.
- Verify cold startup, top-bar spacing, onboarding and About/Wrapped branding on
  a phone in light/dark mode. Device appearance/navigation checks remain manual.
- These notes do not claim a newly published release or a tested release-signed APK.

## AutoMix quality and external equalizers

- AutoMix can identify sustained instrumental outros beyond the old near-end
  window, pair them with suitable intro cues, and finish a planned overlap before
  the outgoing file ends when evidence supports it.
- Added wider coarse scanning, targeted refinement, spectral rhythm confidence,
  measured accent/section boundaries and candidate scoring. Vocal/structure
  estimates remain approximate; unsafe early cuts and intro skipping are refused.
- Smoothed bass handoffs, transition-region level matching and tempo corrections;
  missed intelligent boundaries use a later safe fallback when possible.
- Corrected external effect-session ownership across prepared, promoted and
  fading players for both Crossfade and AutoMix. Real AudioTrack callbacks prevent
  stale/incorrect IDs and delayed old releases from closing a replacement session.
- Improved Crossfade metadata, seek/error cleanup and promotion recovery while
  preserving its duration/curve. Poweramp Equalizer compatibility and listening
  quality still require device validation; no third-party settings are reset.

## AutoMix analysis and player diagnostics

- Corrected incoming rhythm confidence around leading silence and energy-window
  timing so supported track pairs can retain measured earlier outro transitions.
- Gave refinement its own bounded deadline, preserved analysis across future-only
  queue changes, and identified missed starts, decoder problems and fallback fades.
- Added optional **Show AutoMix transition point** below Appearance’s player slider
  style and **Stats for Nerds** in its new Advanced section. Both default off.
  Stats show verified source/app-output audio details and actual AutoMix stages;
  the marker represents a confirmed rhythmic plan, never a fallback estimate.
- Preserved existing Crossfade settings and external audio-effect session ownership.
  Audible quality and external equalizer behavior still require device testing.

## Active AutoMix and exportable diagnostics

- Added analysis-driven active groove mixing without waiting for an RMS drop, with
  joint rhythm/bass/spectrum scoring and adaptive later intro cues that protect
  important content. Supported mixes can use 16/32/64 beats, with a 48-second cap.
- Added full DJ, moderate and safe tiers, extended refinement coverage, nonlinear
  automation, bass transfer and controlled high-frequency handoffs. Existing tempo
  matching and external audio-effect session ownership are preserved.
- Added one bounded smaller-window retry for eligible failures and moderate recovery
  from failed refinement; corrected a worker cancellation race and labeled queue
  saturation distinctly.
- Stats for Nerds now shows tier/actual-start information. Hold the existing overlay
  to export sanitized recent AutoMix history to Downloads on Android 10+, or a
  selected document on Android 8/9. Normal taps do not export.
- Real-device musical quality, storage-provider/gesture behavior and external EQ
  still need verification; generated-PCM and storage-contract tests do not certify
  listening quality. No new dependency, model, signing or release-version change.

## AutoMix analysis foundation

- Separate decoded PCM windows from musical analysis behind a replaceable engine,
  keeping the existing Kotlin analyzer and playback behavior as the production default.
- Add explicit source timing/coverage, backend versions and evidence qualifications,
  plus generated-audio parity and characterization tests for future analyzer work.
  Existing tempo/confidence, decoding/resampling and gain weaknesses remain unfixed
  in this preparation phase; no native analyzer, model, setting or UI change is added.

## AutoMix PCM preparation foundation

- Add a separately versioned, internally selectable anti-aliased PCM preparation
  path with explicit source timing, gap and overlap handling; keep the existing
  preprocessing as the production default; corrected preprocessing remains experimental.
- Include structured decoder coverage, EOS, format, timing and failure details in
  the existing sanitized AutoMix diagnostic export, without changing its UI.
- Add generated signal tests and Android decoder test fixtures for future analyzer
  integration. Musical-analysis algorithms and playback/EQ behavior are unchanged.
- Harden decoder output-buffer region handling and EOS diagnostics, and add an
  isolated device-test target plus stronger timing and four-worker regression
  checks. Legacy preprocessing remains the production default.
- Correct the Windows PowerShell instrumentation-test command to quote complete
  dotted Gradle properties, avoiding an Android-extension/protobuf configuration
  collision without changing toolchain or plugin versions.
- Document restoring the existing local debug keystore before Android decoder
  tests, without replacing permanent keys or changing signing configuration.
- Add sanitized decoder-test diagnostics and correct a seek-test assumption
  exposed by Samsung's reported 8 ms missing head. Test honest partial coverage
  and rejection before musical analysis. The owner later reported all four decoder tests
  passing in the final combined 49-test S25 Ultra / Android 17 regression.

## Standalone native analysis experiment

- Add an independently buildable AGPLv3-or-later prototype using pinned
  BitChord/Orchard tempo DSP, with generated rhythm benchmarks, original-code
  parity, bounded cancellation and Android ABI compilation checks.
- Keep the prototype separate from Tinium's Android build and lyrics components.
  Production AutoMix, legacy preprocessing, playback and EQ behavior are unchanged.
  Confidence false positives and metrical ambiguity remain documented limitations;
  this phase does not claim improved audible transitions or Android runtime testing.

## Experimental Android native analysis foundation

- Add an internally selectable BitChord/Orchard native analysis backend through
  bounded JNI, using corrected PCM, explicit beat timestamps, qualified evidence
  and a separately identified legacy fallback. Preserve source and license notices.
- Add real-JNI host tests, Android integration tests and native APK packaging checks.
  Normal AutoMix still uses the existing Kotlin analyzer and legacy preprocessing;
  no setting, playback transition, lyrics or EQ behavior changes. Android runtime
  validation and musical-confidence calibration remain required before activation.
- Preserve completed analysis cache entries across cancellation/invalidation even
  when their completion callbacks are delayed, avoiding unnecessary decoding.
  Keep canceled/failed work out of the cache and prevent late callbacks from
  repopulating it after shutdown. Playback and backend defaults are unchanged.
- Include controlled decoder coverage and timestamp details in native Android
  codec-test failures, and attempt both generated AAC and FLAC fixtures without
  weakening their assertions. Phone diagnostics identified a one-frame AAC head
  loss before JNI. Preserve initial priming packets in corrected decoding with a
  separately versioned v3 input/cache identity; keep legacy decoding unchanged.
  The owner confirmed the targeted AAC/FLAC integration test passed on the S25
  Ultra with Android 17; full regression and musical-quality validation remain
  separate requirements before experimental production activation.

## Experimental native-aware transition planning

- Add an internally invoked two-track planner using actual BitChord beat evidence,
  covered PCM, explicit cue candidates and predicted drift at normal playback rate.
  Keep half/double-time interpretations and missing phrase/vocal/key evidence visible.
- Corroborate proposed pulse runs with bounded PCM observations and preserve intros
  when no measured quiet prefix supports a later cue. Rank feasible overlaps without
  treating the native score as a calibrated confidence probability.
- Add generated legacy/native comparisons, private local-audio evaluation tooling,
  cancellation/cache regressions and six new Android planner instrumentation tests.
  Normal AutoMix, playback gains, effects sessions, settings and UI stay unchanged;
  the experimental planner is not connected to production playback.

## Experimental AutoMix execution — developer validation

- Add an internally invoked native-plan executor with prepared incoming cues,
  two-player overlap, bounded gain envelopes and explicit cancellation/handoff.
- Prevent quiet or unrelated outgoing level measurements from severely attenuating
  incoming music in this experimental path; normal AutoMix gains remain unchanged.
- Add deterministic signal/state tests and isolated Android playback tests with
  timing/session diagnostics. Normal playback still uses legacy AutoMix; device
  execution and real-song listening remain separate validation requirements.
- In the experimental executor, predict incoming command timing from a fresh outgoing
  startup observation only for compatible prepared audio formats, while retaining the
  selected cues, full overlap and drift limits.
  Continue outgoing playback safely if an unverified early start is interrupted.
- Track experimental overlap progress and stalls independently for each player clock,
  avoiding undercounts or false stalls when position snapshots update on different ticks.

## Opt-in Native DJ AutoMix — experimental service integration

- Add an explicit Native DJ AutoMix engine selection alongside legacy AutoMix. Existing
  users retain legacy behavior; switching back does not erase preferences or playback data.
- Connect corrected two-track native analysis, conservative planning and the validated
  dual-deck executor to the normal service queue, with exclusive ownership and safe fallback.
- Preserve bounded active-level gains, queue continuity and the service's existing effects
  sessions; show native plans/fallbacks through the existing timeline and Stats for Nerds.
- Extend deliberate diagnostic exports with privacy-safe native transition evidence and
  add actual-service Android tests. The owner reports all 49 combined Android regressions
  passed on the S25 Ultra / Android 17, including all eleven service tests, with zero
  failures/skips. Real-song and route/EQ listening remain required; this experimental
  mode is not a production-readiness claim.

- Improve experimental service-test progress and timeout diagnostics, including bounded
  main-thread waits and private failure reports when logcat is unavailable.
- Isolate generated service-test media identities between runs so cached WAV fixtures cannot
  replace AAC/FLAC or silence fixtures; remove only the current test's cache resources.

- Experimental Native DJ AutoMix waits for the current audio output to initialize before
  admitting a pair, and reports specific configuration-rejection reasons. Legacy mode
  remains the default; native coverage, gain and timing safeguards are unchanged.

- Fix corrected experimental PCM capture discarding a source frame for tiny FLAC timestamp
  offsets. The new v4 preprocessing profile keeps cache results isolated; missing audio
  still fails safely, and normal legacy AutoMix remains unchanged.

- Add privacy-safe decoder input/output accounting and a silent generated-FLAC Android
  diagnostic used to investigate experimental full-window EOS failures. Incomplete audio
  continues to fall back; this diagnostic does not enable native mode or relax coverage.

- Correct experimental compressed-FLAC analysis packet framing and select an available
  platform software decoder, while preserving strict source coverage. A separate v5
  preprocessing profile isolates old analysis/cache results; legacy playback remains
  unchanged. Host packet-preservation tests passed; the owner subsequently reported the
  corrected FLAC probe, service codec case and final combined Android regression passing.

- Prevent experimental Native DJ pause/resume callbacks from repeatedly toggling the
  playback decks. Keep strict queue/timing checks and isolate generated service-test
  histories and queue cleanup. The owner reports the final combined 49-test device suite
  passed after the completion-boundary correction. Legacy AutoMix remains the default.
