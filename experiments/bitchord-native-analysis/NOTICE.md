# Standalone native rhythm-analysis experiment

This directory is an independently buildable AGPL-3.0-or-later program. Phase
2.3A distributed it separately; Phase 2.3B also links its static core through an
experimental Tinium JNI adapter. Normal production AutoMix does not select it.
It contains no lyrics implementation, network provider, player or model assets
and can still be copied and built on its own. This notice does not change the licensing
of Tinium's other files or establish permission to distribute a future combined
Android application.

## Upstream source

BitChord: <https://github.com/kushagrasinghx/BitChord>

Pinned revision: `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`.

The four `vendor/bitchord/` files retain their original notices:

- Copyright (C) 2026 SFG545, original Orchard implementation
  (<https://github.com/SFG5453/Orchard>).
- Copyright (C) 2026 Kushagra Singh, BitChord adaptation.
- GNU Affero General Public License, version 3 or later; no warranty.

`tempo_analysis.cpp`, `resampler.cpp` and `resampler.h` are byte-identical to
their pinned originals. `audio_analysis.h` contains only the original notice,
`TempoResult` and `AnalyzeTempo` declarations; unrelated analyzer declarations
were omitted. Exact upstream and vendored hashes are in `provenance.json`.
The native files' AGPL notices govern these files; BitChord's root GPL text is
not a replacement for them.

## Adaptation and newly authored code

The Tinium Phase 2.3A adaptation was made on 2026-10-16. All other source,
scripts, tests, fixtures and build instructions in this directory are supplied
under AGPL-3.0-or-later, as indicated by their SPDX notices. Generated fixture
audio is synthesized from these scripts; no recordings are included.

`cmake/ControlledSources.cmake` generates the controlled implementation in the
build tree. It renames functions, threads an explicit per-call cancellation/
deadline object through helpers, and inserts loop checkpoints. The original
DSP arithmetic and thresholds are preserved. Hashes and replacement counts
make drift fail at configure time. `src/analyzer.cpp` adds bounded validation,
timing, error mapping, an exact-silence status and trailing-event validation.
It does not replace BitChord's detector. `reference_cli` builds the original
functions through the same public result-validation wrapper for comparison.
Phase 2.3B additionally adds a synchronous, optional analysis-stage observer to
`Control`/the wrapper for JNI cancellation tests, and optional standalone shared
library generation. No upstream DSP file or numerical algorithm is changed.

The optional `LegacyBaseline.java` tool communicates only with a separate JVM
process running selected nonrestricted Tinium musical-analyzer classes. No
lyrics classes are copied or loaded, and native building/testing does not
require that optional tool or Tinium's Android sources.

## Distribution obligations and limits

See `COPYING` for the complete AGPLv3 text. Preserve this notice, upstream
notices, modifications, license and corresponding source/build instructions
when conveying this program. A binary distribution needs a compliant way to
obtain its corresponding source under the license; attribution alone is not
sufficient. If this program is modified to offer remote network interaction,
assess AGPL section 13 and provide the required source offer. No hosted service
or binary distribution is supplied by this PR.

The only native dependencies are the C++ standard library and platform runtime.
The verified Android build statically links NDK libc++; future binary shipping
must also include the applicable LLVM/libc++ runtime notices from the selected
NDK. Host runtime obligations depend on the selected compiler/runtime. Python
and Java are benchmark/build tools rather than native runtime dependencies.
There are no ONNX, FFT-library, JNI or model licenses to add in this phase.

The owner's Musixmatch permission is a development working assumption for this
task. Permission verification for public redistribution of a future combined
Tinium/AGPL application remains external; this isolated prototype neither
reopens that investigation nor asserts legal clearance for that combination.
