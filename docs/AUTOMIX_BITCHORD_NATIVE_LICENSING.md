# Phase 2.3 native AutoMix source and licensing review

## Status: import blocked; no native implementation shipped

Phase 2.3 implementation **has not been completed**. The requested pre-import
license review found a material unresolved permission/compatibility issue already
identified in Phase 1 and still present on merged main. The task explicitly says:

> If there is a material unresolved license conflict, stop the code-import portion
> of the integration, document the blocker, and propose a compliant alternative or
> review path.

Accordingly this PR changes documentation only. No BitChord/Orchard implementation,
native binary, JNI, build target, dependency, model or experimental engine was added.
No existing licenses, restricted files, attribution, preferences or defaults were
changed. This is an engineering evidence review, not a ruling about the legality
of the owner's existing installations or a legal clearance for redistribution.

## Exact source baseline

| Repository | Inspected branch/revision | Observation |
| --- | --- | --- |
| Tinium | `origin/main`, `e3f1fb2abb8d79f959ee55c687223c26067d53ca` | Merge of PR #17, including its final coverage-test correction |
| BitChord | `origin/main`, `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` | Fresh fetch still resolves to the Phase 1 pinned revision |

Branch for this review: `codex/automix-native-phase23`. No upstream source was
silently advanced. Source hashes below identify the exact examined bytes; the
inventory is a list of **candidates**, not a record of code imported into Tinium.

## Confirmed restriction and inclusion evidence

1. [Tinium LICENSE, lines 1–2](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/LICENSE#L1-L2) prefixes GPLv3 with a special
   Musixmatch linking exception and expressly prohibits copying, adapting,
   modifying or redistributing that module in another application, fork or
   repository without the author's express permission.
2. [Musixmatch.kt, lines 1–8](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/lyricsProvider/src/main/kotlin/com/music/musixmatch/Musixmatch.kt#L1-L8)
   and [models/Models.kt, lines 1–8](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/lyricsProvider/src/main/kotlin/com/music/musixmatch/models/Models.kt#L1-L8)
   say `VIVI-LYRICS (C) 2026`, viewing only, copying/modification/redistribution/reuse
   prohibited, and use exclusively in official vivimusic. The module's test source
   also carries the proprietary notice.
3. [settings.gradle.kts, line 39](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/settings.gradle.kts#L39) includes
   `:lyricsProvider`. [app/build.gradle.kts, line 301](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/app/build.gradle.kts#L301)
   uses unconditional `implementation(project(":lyricsProvider"))`; it is not a
   GMS-only dependency. Both GMS and FOSS therefore include this project.
4. [MusixmatchLyricsProvider.kt](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/app/src/main/kotlin/com/music/vivi/lyrics/MusixmatchLyricsProvider.kt)
   calls that module. [LyricsProviderRegistry.kt](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/app/src/main/kotlin/com/music/vivi/lyrics/LyricsProviderRegistry.kt)
   registers the provider. A disabled preference does not remove source or the
   declared dependency. The `foss` name is not evidence of licensing clearance.
5. The examined repository documents/notices contain no separate grant resolving
   use in this Tinium fork or combination with imported AGPL-derived code. A
   private grant could exist; its absence from the inspected evidence is **not**
   proof that none exists. The owner was asked for any existing written grant and
   its non-confidential scope. No such evidence was available when this review
   was written.

Two questions must be resolved: permission to copy/distribute the restricted
module in Tinium, and terms permitting the intended combined work and downstream
rights. Permission for personal use alone would not establish compatible public
source/APK distribution. The GPL-side exception does not itself grant rights on
behalf of the Orchard/BitChord AGPL contributors. Conversely, GPLv3 and AGPLv3 are
not inherently incompatible: [GPLv3 section 13](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/LICENSE#L558-L566) permits the
combination under its conditions. That permission does not settle a separate
proprietary-module restriction.

## BitChord source inventory and file-level licenses

Each of the following file headers identifies Orchard as the source, copyright
2026 SFG545 for the original work and 2026 Kushagra Singh for BitChord adaptation,
and AGPL version 3 or later. BitChord's root GPLv3 text does not replace those
file-level notices.

| Candidate upstream file | Intended role after clearance | License evidence | Import status |
| --- | --- | --- | --- |
| [native/analyzer/tempo_analysis.cpp](https://github.com/kushagrasinghx/BitChord/blob/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/native/analyzer/tempo_analysis.cpp#L1-L20) | `AnalyzeTempo`, onset FFT/flux, fractional tempo estimation and tracked beats | AGPLv3-or-later Orchard/BitChord header | **Not imported** |
| [native/analyzer/audio_analysis.h](https://github.com/kushagrasinghx/BitChord/blob/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/native/analyzer/audio_analysis.h#L1-L23) | `TempoResult` and `AnalyzeTempo` declarations; retain only justified contracts with notices | AGPLv3-or-later Orchard/BitChord header | **Not imported** |
| [native/analyzer/resampler.h](https://github.com/kushagrasinghx/BitChord/blob/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/native/analyzer/resampler.h#L1-L20) | Optional internal 22,050→11,025-Hz DSP adapter declaration | AGPLv3-or-later Orchard/BitChord header | **Not imported** |
| [native/analyzer/resampler.cpp](https://github.com/kushagrasinghx/BitChord/blob/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/native/analyzer/resampler.cpp#L1-L20) | Optional band-limited conversion to the upstream tuning rate | AGPLv3-or-later Orchard/BitChord header | **Not imported** |
| [BitChord LICENSE](https://github.com/kushagrasinghx/BitChord/blob/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/LICENSE) | Root repository licensing context | GPLv3 text; not an override for these AGPL files | Inspected, not copied |

The selected algorithm files use standard C++ math/containers and their own FFT;
this subset has no ONNX, FFTW, Essentia, Rubber Band, Python or model dependency.
The broader upstream CMake target includes extra analysis/model frontends and
DSD/DST targets; it must not be copied wholesale. Existing unrelated Tinium
`app/src/main/cpp/vibrafp` sources are not an AutoMix backend and were not changed.

### SHA-256 of inspected upstream files

```text
f6cde3e8a87fb1f42e356649fffe2a87b36f857be28d360b326c14d9c478bf2f  native/analyzer/tempo_analysis.cpp
36d65e53755dd4c11fdf2b377428e2411fcc033a17f58e2c26229beb3c461d1e  native/analyzer/audio_analysis.h
93ae72db79f64efb5c8e0bb3296a565ae7286185482de781134bccb621ce149e  native/analyzer/resampler.h
f516a5683d77537c73f628a4e45f44842ef4a5b3c6d9938d2014ac05e83884a0  native/analyzer/resampler.cpp
3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986  LICENSE
```

### SHA-256 of Tinium restriction evidence

```text
a1119653881d384286b71789e728decb43a7a65f2d74e3e943d9cc452dcf4a85  LICENSE
a24ef7d05f1c069130cae05a194ac25719fb5eaa8f389f6f9407cf266478865d  lyricsProvider/src/main/kotlin/com/music/musixmatch/Musixmatch.kt
03c0656a390b1e2f465e66148acef3ea4f2290f8b2e0897d1a0a3f34514207cf  lyricsProvider/src/main/kotlin/com/music/musixmatch/models/Models.kt
```

## Obligations to plan for if the gate is cleared

- Preserve relevant Orchard, BitChord, VIVI and other contributor notices. Do not
  replace file-level AGPL terms with Tinium's existing root exception.
- Supply applicable GPL/AGPL texts, source provenance and modification inventory.
  Identify copied versus adapted versus newly written files and their licenses.
- Provide required Corresponding Source and build/install information for the
  distributed combination, including JNI, native build inputs and modifications.
  Evaluate applicable installation-information and network-interaction provisions
  against the actual distribution/use. This is not satisfied by a credit or a
  source link alone; [AGPLv3](https://www.gnu.org/licenses/agpl-3.0.html) supplies
  the governing terms for the imported files.
- Review rights and exceptions for the complete combined work and redistributed
  runtime/dependencies, including the selected NDK C++ runtime's notices.
- Keep models out of scope: a runtime license does not establish model rights.
  No ONNX/Beat This!/vocal assets are needed for this proposed DSP subset.

No assertion is made that all existing application dependencies are legally
cleared. This review identifies a concrete unresolved gate; it is not an
exhaustive legal audit of every Tinium dependency.

## Resolution paths

| Path | Required evidence or authorization | Effect on this task |
| --- | --- | --- |
| Review an existing written grant | Identify the relevant rightsholders, exact files/versions, fork/source/APK redistribution and modification rights, downstream terms and intended AGPL combination | Resume import only if applicability and compatibility are established; a generic permission to use lyrics is insufficient |
| Obtain compatible permissions/relicensing | Appropriate rightsholders provide terms resolving the restricted module and any required exceptions for the intended combination; retain their grant | No unilateral license rewriting; do not contact authors automatically |
| Remove/replace/exclude restricted material in a separately approved change | Inventory the module and integration, preserve unrelated lyric providers/settings where possible, review remaining licenses and test affected behavior | Potentially clears the specific gate, but changes lyrics functionality/source composition and is not authorized silently by an AutoMix task |
| Separately authorized standalone DSP benchmark | A separate AGPL-compliant project containing only permitted source/fixtures and no restricted app module | Can investigate algorithm quality independently; does not clear integration or distribution of Tinium's combined app |
| Independently implement an alternative | Fresh implementation based on permitted general DSP literature, with its own accuracy validation and dependency review | Changes the requested BitChord-port strategy; requires a deliberate decision, not disguised rewriting of copied code |

A feature flag, debug-only switch, separate `.so` filename, FOSS flavor, public
GitHub repository or additional attribution does not by itself settle these
rights. Keeping normal playback on legacy is a technical safeguard, not a license
exception. No restricted functionality was removed to work around this gate.

## Next concrete step

Review the owner's existing permission evidence, if available, or choose a
separately scoped rights-resolution/removal path. Record the outcome and supporting
grant/review before the first source import. Once cleared, follow
[AUTOMIX_BITCHORD_NATIVE_ANALYZER.md](AUTOMIX_BITCHORD_NATIVE_ANALYZER.md).
