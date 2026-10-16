# Tinium experimental AutoMix JNI integration — 2026-10-16

This JNI adapter and the new native-engine Kotlin adapter/tests are provided under
AGPL-3.0-or-later. They use BitChord/Orchard DSP pinned to
`fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`:
<https://github.com/kushagrasinghx/BitChord/tree/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/native/analyzer>.

Copyright (C) 2026 SFG545 (original Orchard implementation)
Copyright (C) 2026 Kushagra Singh (BitChord adaptation)

Original DSP notices, full AGPLv3 text and SHA-256 source inventory are retained
in `experiments/bitchord-native-analysis/` and copied into APK assets at
`licenses/bitchord-automix/`. Corresponding Tinium integration source/build
instructions: <https://github.com/yren411/vivi-music-personal>,
`docs/AUTOMIX_BITCHORD_ANDROID_INTEGRATION.md`. Preserve corresponding source,
modification notices and license when conveying binaries; credit alone is not
enough. See the license for warranty disclaimer and applicable obligations.

Phase 2.3B adds JNI ownership/validation, Kotlin evidence/timestamp mapping and a
synchronous stage observer for cancellation tests. It reuses one authoritative
native source tree without changing DSP arithmetic. Host/reference programs are
excluded from Android packaging. No model or external DSP dependency is added.

The owner's Musixmatch permission is a development assumption. This notice does
not establish legal clearance for public redistribution of the combined app.
No lyrics implementation is changed. Public binary distribution must resolve
the previously documented external permission/compliance requirements first.

The build uses NDK 27 libc++ statically. `NDK-LIBCXX-NOTICE` is the unmodified
NDK 27.0.12077973 LLVM prebuilt toolchain NOTICE, including LLVM/libc++ notices,
Apache-2.0 WITH LLVM-exception and legacy dual-license texts. It is packaged
alongside these notices. Host-tool entries are overinclusive, not new app code.
