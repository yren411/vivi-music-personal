# Tinium current-status snapshot

[Complete handoff](TINIUM_COMPLETE_PROJECT_HANDOFF.md) · [Compact AI context](TINIUM_AI_CONTEXT.md) ·
[New AI starting prompt](TINIUM_NEW_AI_STARTING_PROMPT.md)

## Observation and repository state

**Observed:** 2026-11-09 UTC (2026-11-08 America/Chicago). This is a dated inspection,
not a live dashboard. Recheck GitHub before making changes.

| Item | Verified state |
| --- | --- |
| Repository / default branch | [yren411/vivi-music-personal](https://github.com/yren411/vivi-music-personal) / `main` |
| Main commit | [`76eda458c3ac2dc0efd307d24116bf4c68cd31df`](https://github.com/yren411/vivi-music-personal/commit/76eda458c3ac2dc0efd307d24116bf4c68cd31df), PR #26 merge |
| Merged implementation phase | Phase 3.3.3, AAC boundary coverage and genuine nonzero-seek preroll |
| Active implementation branch | `codex/automix-adaptive-analysis`, open/draft PR #27; **not main** |
| PR #27 inspected head | `f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2` |
| This handoff branch | `codex/tinium-project-handoff`, based on main; documentation only |
| Production identity | Tinium / `com.yren411.tinium`; internal namespace `com.music.vivi` |
| Development version defaults | 6.0.8 / 77; not a fresh release reservation |
| Default AutoMix state | AutoMix off; Legacy engine when enabled; Native explicitly experimental opt-in |

All PRs #1–26 are merged. At inspection **#27 is the only existing open application PR**,
with no review/issue discussion returned by the inspected APIs. This new documentation PR
is separate from that implementation inventory. Do not infer active work from retained
merged-PR branches; `codex/validate-tinium-arm64` also exists without an open PR found.

## Relevant merged history

| PR | Phase / result |
| --- | --- |
| [#11](https://github.com/yren411/vivi-music-personal/pull/11)–[#14](https://github.com/yren411/vivi-music-personal/pull/14) | Kotlin intelligent AutoMix, compatibility/diagnostics and legacy DJ work |
| [#15](https://github.com/yren411/vivi-music-personal/pull/15) | BitChord feasibility investigation |
| [#16](https://github.com/yren411/vivi-music-personal/pull/16) | 2.1 replaceable analysis architecture and evidence contracts |
| [#17](https://github.com/yren411/vivi-music-personal/pull/17) | 2.2 corrected decoder/preprocessing/source coverage |
| [#18](https://github.com/yren411/vivi-music-personal/pull/18) | Licensing/provenance reassessment; not public-distribution clearance |
| [#19](https://github.com/yren411/vivi-music-personal/pull/19) | 2.3A real native DSP prototype and deterministic corpus |
| [#20](https://github.com/yren411/vivi-music-personal/pull/20) | 2.3B Android JNI/backend |
| [#21](https://github.com/yren411/vivi-music-personal/pull/21) | 3.1 native-aware planner |
| [#22](https://github.com/yren411/vivi-music-personal/pull/22) | 3.2 real dual-deck executor and timing repairs |
| [#23](https://github.com/yren411/vivi-music-personal/pull/23) | 3.3 MusicService integration, opt-in, fallback, diagnostics; merged October 18 |
| [#24](https://github.com/yren411/vivi-music-personal/pull/24) | 3.3.1 AAC tail ownership and Opus support; merged October 18 |
| [#25](https://github.com/yren411/vivi-music-personal/pull/25) | 3.3.2 route/clock diagnostics and outgoing preservation; merged October 19 |
| [#26](https://github.com/yren411/vivi-music-personal/pull/26) | 3.3.3 AAC boundary investigation/preroll; merged October 23 |
| [#27](https://github.com/yren411/vivi-music-personal/pull/27) | 3.3.4 adaptive windows/evidence reuse; **open, draft, not merged** |

Earlier branding, updater, signing and release-management PRs #1–10 are mapped in the
[complete history](TINIUM_COMPLETE_PROJECT_HANDOFF.md). Private recordings and unverified
original MP4 edit metadata are not engineering fixtures.

## Latest checked build results, attributed to exact revisions

| Source revision | Workflow and result |
| --- | --- |
| Main `76eda458c3ac2dc0efd307d24116bf4c68cd31df` | [CI & Debug Build 39886408542](https://github.com/yren411/vivi-music-personal/actions/runs/39886408542), success |
| Same main revision | [Build Tinium Development APK 39886408613](https://github.com/yren411/vivi-music-personal/actions/runs/39886408613), success |
| PR #27 head `f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2` | [Build PR 40109859869](https://github.com/yren411/vivi-music-personal/actions/runs/40109859869), success |
| Same PR #27 head | [CI & Debug Build 40109859915](https://github.com/yren411/vivi-music-personal/actions/runs/40109859915), success |
| Same PR #27 head | [Duplicate push run 40109800004](https://github.com/yren411/vivi-music-personal/actions/runs/40109800004), conditionally skipped; not a failed test |

Later scheduled Stale Issue Handler runs are issue maintenance, not newer APK validation.
The current workflows do not execute phone instrumentation. Nonblocking lint does not mean
clean lint. These previous runs must not be attributed as fresh checks on a later handoff
commit. The new documentation PR's own checks can be inspected on its Checks tab.

## Test and measurement evidence

| Scope | Latest recorded evidence and limit |
| --- | --- |
| Main JVM | Phase 3.3.3 records **563 Arm64 GMS + 563 Universal FOSS = 1126**, zero failures/errors/skips, including 17 actual host-JNI methods per variant (34 included, not additional) |
| Main Android | Owner reports **10 new AAC tests, then 79 combined selected tests passed**, zero failures/skips, S25 Ultra / Android 17. No raw runner XML or independent installed-SHA verification supplied; instructions referenced reviewed `fcde6cbab169fa72541de9860cc4afc302641218` |
| Older Android milestones | Owner-reported combined 49 → 59 → 69 before 79; distinct historical checkpoints, not additive new passes |
| Draft PR #27 JVM | Recorded **599 + 599 = 1198**, zero failures/errors/skips; 19 host-JNI methods per variant (38 included) |
| Draft PR #27 Android | **12 new methods compiled**; expanded selection is **91**. New physical-device execution/passing report not found; pending acceptance |
| Native DSP | Historical standalone CTest/sanitizer/NDK and host-JNI evidence; not newly rerun during this handoff |
| Acoustic / EQ / Bluetooth | No independently synchronized loopback or systematic audible Poweramp/Bluetooth validation established |
| This documentation task | Successful Gradle `:app:tasks --all` enumeration; documentation validation only. No new JVM/native/Android suite or app build execution |

[Main boundary evidence](benchmarks/phase333-aac-boundary/validation.json),
[phase history/provenance](TINIUM_COMPLETE_PROJECT_HANDOFF.md), and
[pinned PR #27 evidence directory](https://github.com/yren411/vivi-music-personal/tree/f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2/docs/benchmarks/phase334-adaptive-analysis/)
retain different historical counts and unfavorable measurements. Do not rewrite initial
JSON to match later prose checkpoints. Main acceptance includes eight classes; standalone
startup/FLAC/listening probes are outside the 79. See the complete handoff for exact commands.

## Current implementation identities

| Layer | Main value |
| --- | --- |
| BitChord pinned revision | `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` |
| Backend | `bitchord-native-dsp` / `fe95bb60-controlled-v1-jni1-evidence1-internal11025` |
| Corrected PCM | `sinc64-blackman-aac-preroll-mono-22050-v8` |
| Planner | `tinium-native-dj-planner-v2-duration-preservation` |
| Executor | `tinium-native-dj-executor-v5-bracketed-media-clocks` |
| Legacy PCM | `nearest-time-mono-22050-v1` |
| Draft PR #27 strategy | `native-regions-v1-verified-inset`; not a replacement v9 PCM |

Native is real source-pinned corrected decoding → BitChord JNI → native planning → service
Media3 decks → guarded execution → one promotion. Legacy independently owns fallback after
native release. Native gain remains restrained, without advanced per-deck frequency effects.

## Unresolved work and recommended next actions

| Priority | Status / next investigation |
| --- | --- |
| Review/validate existing PR #27 | Run its targeted 12 and expanded 91 on owner device; review actual cache/lease/cancellation semantics before considering merge. Do not duplicate implementation |
| Real AAC boundaries | v8 recovered genuine nonzero-seek preroll; a 137.006 ms leading presentation interval and 144 µs trailing deficit remain partial on main. Original private metadata is uninspected. PR #27's new verified requests need real-source confirmation |
| Intermittent native drift | `EXCESSIVE_DRIFT` still occurs; preserve 120 ms/40 ms guards. Bracketed clocks and some successful mixes do not establish an acoustic fix |
| Seek/route/recorder lifetime | Main completed evidence and pending batches have different lifetimes; PR #27 separates reuse and replanning further. Validate Samsung recorder, actual output replacement, same-source seeks and safe stale-plan removal |
| Musical/compatibility quality | Test authorized sequences, vocals/phrases/cue balance, long overlaps, speaker/headphones/Bluetooth and external EQ audibility; no broad production claim |
| Resources | Extended phone battery/thermal behavior unmeasured; optional enrichment must yield to playback/preparation |
| Broader app integrations | Current remote-provider/account, recognition, canvas, backup, Cast and Android Auto breadth is not proved by AutoMix tests |
| Security/licensing/release | Review embedded third-party credential exposure/configuration without reproducing values; preserve GPL/AGPL/restrictive notices; human redistribution review and fresh version assignment remain required |

No advanced Native DJ EQ/time-stretching phase has been implemented. Recommended ordering:
existing PR acceptance → repeated real-source/lifecycle/clock evidence → narrow reliability
fixes if demonstrated → broader musical/device evaluation → separately authorized Phase 3.4.
The next AI should orient and wait for the owner's actual task, not start this roadmap automatically.

## Release and installation boundaries

No GitHub Releases were returned by the API at observation. That does not mean no APK was
installed elsewhere. Reserved immutable codes **78–83** exist; latest inspected reservation
is **6.0.8-beta.6 / 83**, source `801915d816941c6176016abbc808a62972867abc`.
Never retarget an old reservation or apply it to this newer code. Manual candidate/publish
workflows and reviewed notes/certificate/provenance gates remain authoritative.

For device work retain `-PautomixDecoderTests=true`: debug package becomes
`com.yren411.tinium.decodercheck`. Keep the owner's working debug key. Do not uninstall the
normal `com.yren411.tinium`, clear data/music caches, change signing or publish an APK.
Switch the UI engine back to Legacy for reversible listening rollback; temporary native
session suspension does not remove the saved experimental preference.

This handoff changes documentation only. It allocates no version, merges nothing, releases
nothing and does not enable experimental behavior by default.
