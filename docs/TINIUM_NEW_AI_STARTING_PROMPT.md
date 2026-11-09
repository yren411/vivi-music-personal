# Ready-to-copy prompt for a new AI coding assistant

Copy the message below. Upload `TINIUM_AI_CONTEXT.md` first if the platform accepts files;
provide repository access and the other handoff documents as references. Do not provide
private keys, cookies, credentials, signed source URLs or commercial audio.

---

You are continuing an existing Android music application project called **Tinium**, a
customized fork of Vivi Music. This is an orientation request, **not authorization to begin
implementation**. First inspect the current repository, acknowledge the context, identify
missing information and summarize your understanding. Wait for my specific next task before
changing implementation, opening an implementation branch or starting a new development phase.

Repository: https://github.com/yren411/vivi-music-personal

Production application ID: `com.yren411.tinium`

Internal Kotlin namespace: `com.music.vivi`

Read, in this order:

1. `AGENTS.md` and any applicable directory instructions.
2. `docs/TINIUM_AI_CONTEXT.md` for compact orientation.
3. `docs/TINIUM_CURRENT_STATUS.md` for the dated branch/PR/evidence snapshot.
4. `docs/TINIUM_COMPLETE_PROJECT_HANDOFF.md` for the complete architecture and history.
5. The relevant versioned engineering documents, source files, tests and benchmark artifacts
   linked from the handoff before making a technical recommendation.

**Verify the latest GitHub state instead of treating the handoff as permanently current.**
Check default branch, main SHA, open/draft/merged PRs, actual source versions and check-run
SHAs. At handoff, main was `76eda458c3ac2dc0efd307d24116bf4c68cd31df`, merged through
PR #26 / Phase 3.3.3. PR #27 was open/draft, adaptive native analysis and stable evidence
reuse, branch `codex/automix-adaptive-analysis`, head
`f2e8a1b9ac245a69c38998e0afb69f8a52cb7da2`. Its implementation and validation must be
inspected rather than duplicated. Main did not contain it. A merged PR's retained branch
is not necessarily unfinished work. Do not merge, close or replace any PR automatically.

Tinium uses Kotlin/Compose, Hilt, Room, DataStore and a Media3 MediaLibraryService.
`MusicService` owns normal music playback, queue, audio focus, sessions and AutoMix. Supporting
modules provide YouTube Music source/browse logic, optional JioSaavn matching, lyrics/canvas,
Spotify import, scrobbling, rich presence and recognition. These wired integrations are not
all comprehensively validated. There is existing background playback, library/playlists,
download/cache management, EQ and optional features. Preserve the entire app, not just AutoMix.

Native DJ AutoMix is the primary recent engineering focus. It already has source/rendition
pinning, strict corrected PCM decoding/resampling, pinned BitChord C++/JNI analysis, native
beat evidence, a duration-preserving planner, real dual-deck execution, MusicService/queue
integration, experimental settings, bounded diagnostics and independent Legacy fallback.
Do not recreate earlier phases. At handoff the main identities were:

- PCM: `sinc64-blackman-aac-preroll-mono-22050-v8`.
- Planner: `tinium-native-dj-planner-v2-duration-preservation`.
- Executor: `tinium-native-dj-executor-v5-bracketed-media-clocks`.
- BitChord upstream: `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`.

Main still rejects some original AAC requests whose genuine presentation coverage does not
reach time zero or a rounded endpoint. PR #27 independently retries an inward verified
interval and adds bounded region enrichment/evidence reuse. It does not invent missing PCM,
rebase timestamps, scan every song unconditionally, stitch a global beat grid, or guarantee
planner acceptance. Read its own evidence before proposing further adaptive analysis.

Known issues include intermittent `EXCESSIVE_DRIFT`, uncertain startup/route behavior,
Samsung screen-recording interactions, musical phrase/vocal/harmony limitations, external EQ
and Bluetooth listening validation, battery/thermal measurements and broader provider/security/
licensing review. Bracketed player-clock observations do not prove acoustic alignment or
resolve all drift. Genuine source gaps must still fall back. Whole-track analysis/caching
cannot be described as a synchronization fix. Advanced native per-deck EQ, bass swaps,
filters and tempo stretching are future work, not already implemented Phase 3.4 features.

Preserve these constraints in any later authorized work:

- Legacy remains the default engine; Native DJ stays explicitly experimental and opt-in.
- Never invent/interpolate/duplicate PCM to satisfy coverage, weaken gap validation, or treat
  raw native confidence as probability or legacy confidence.
- Preserve 120 ms measured-drift and 40 ms late-command guards, exact cue provenance,
  route-specific startup compatibility and honest failure results.
- Source evidence, pair planning, execution scheduling and route calibration have different
  lifetimes. A seek may cancel/replan without invalidating compatible musical evidence.
- MusicService remains normal playback authority. Native/Legacy deck ownership is exclusive;
  promotion happens exactly once; release cannot destroy the promoted player.
- Preserve source authorization, queue, user volume, focus, MediaSession, notifications,
  audio sessions, global EQ, Poweramp integration, downloads, lyrics and streaming behavior.
- No unrelated DSP/dependency rewrites, app-ID/branding/signing changes, cache/data wipe,
  normal-app uninstall, version allocation, release publication or automatic merge.
- Preserve attribution, GPL/AGPL and restrictive third-party notices; development assumptions
  do not establish public redistribution permission. Do not expose embedded credentials.

For later validation use actual Gradle pins/tasks. The handoff records JDK 21, SDK 37,
Gradle 9.3.1, NDK 27.0.12077973 and CMake 3.22.1. Test both GMS and FOSS, actual host JNI,
relevant native regressions, app/test builds and packaging when affected. Keep four ABIs,
16 KB alignment and license assets. Use the isolated Android configuration
`-PautomixDecoderTests=true`, package `com.yren411.tinium.decodercheck`, preserving the
owner's existing debug key, normal app data and music cache.

Historical evidence must retain attribution: the owner reported 79 combined selected Android
tests passing on an S25 Ultra / Android 17; main records 563+563 JVM cases including host JNI.
PR #27 records 599+599 JVM cases and compiled 12 new Android methods, with expanded 91 device
execution pending at handoff. Do not present compiled tests, CI or owner reports as your own
physical execution. Verify updates and record exact counts/skips/SHAs honestly.

The recommended next engineering activity, subject to my explicit task, is validation and
review of existing PR #27: targeted 12 and expanded 91 device tests, real-source coverage,
seek reuse and screen-recorder behavior. Do not begin advanced DJ effects automatically.

For your first response, summarize the current app architecture, actual merged versus open
work, evidence limits, highest-priority unresolved issues and any missing access/information.
Then wait for my next task. Do not implement changes merely because a roadmap exists.
