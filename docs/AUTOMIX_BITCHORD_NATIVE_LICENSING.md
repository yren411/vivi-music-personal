# Phase 2.3 native AutoMix source and licensing reassessment

## Revised assessment and correction

**`rules.md` is affirmative, conditional GPL reuse evidence and must be considered
alongside the restrictive notices.** The earlier PR #18 statement that no separate
permission evidence was present in Tinium was incomplete: this exact file was
already in the inspected Tinium tree. That omission is corrected here.

The history supports GPL reuse of the earlier published implementation; it does
not unambiguously resolve the terms of all later code currently included in
Tinium. The same recorded author added `rules.md` on July 5, explicitly changed
Musixmatch notices to proprietary terms on July 6 without deleting those rules,
and later added generic GPL headers above the retained proprietary notice in
upstream. Neither “the header always wins” nor “rules.md overrides everything”
follows automatically from these facts.

**The remaining gate is narrow:** confirm which revisions/contributions the
standing reuse permission covers and reconcile simultaneous notices for the
current module. GPLv3/AGPLv3 compatibility is not itself a blanket blocker.
No automatic removal/replacement is recommended. This reassessment changes
**documentation only**, as requested: no app functionality, attribution UI,
source headers, licenses, native import, dependencies, settings or defaults change.
Phase 2.3 implementation remains incomplete, and PR #18 remains unmerged.

## 1. Repositories and exact evidence

| Source | Revision inspected | Scope |
| --- | --- | --- |
| Tinium merged main / PR base | `e3f1fb2abb8d79f959ee55c687223c26067d53ca` | PR #17 merge, actual included lyrics code and rules |
| Tinium initial PR #18 head | `40273dc33e32cbff597e1b115e8f0de9f4362934` | Earlier documentation corrected by this revision |
| Vivi Music fetched main | `b41c9e563ade592c03435d91d984a0ff56a42861` | Current upstream rules, exception and dual headers |
| BitChord pinned main | `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` | Selected Orchard-derived AGPL native files |

Full Git histories were available (neither Tinium nor the upstream reference clone
is shallow). `git log --follow`, actual patches, ancestor checks and blob hashes
were inspected. Dates below retain Git's recorded `+05:30` offset; ancestry and
content establish ordering rather than wall-clock dates alone. Recorded authorship
is evidence of publication/provenance, not independent proof of exclusive copyright
ownership or authority over every incorporated contribution.

## 2. Exact wording and scope

### Conditional reuse rules

[Upstream rules.md](https://github.com/vivizzz007/vivi-music/blob/b41c9e563ade592c03435d91d984a0ff56a42861/rules.md#L1-L22) is byte-identical to
[Tinium rules.md](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/rules.md#L1-L22). Its relevant wording is:

> If you copy, adapt, or reuse any part of the source code from the **Vivi Music** project in your own application or repository, you must adhere to the following rules under the GPL-3.0 license:

> If you reuse the Musixmatch lyrics scraping or signature logic:

> **Attribution**: You must state in your repository's documentation and source headers that the logic was obtained from [Vivi Music](https://github.com/vivizzz007/vivi-music).

> **UI Credit**: You must clearly display attribution inside your application's User Interface (e.g., on the lyrics viewer or lyrics settings screen). The provider name must be labeled as **`vivimusic lyrics provider`** (or specify that lyrics are powered/provided by Vivi Music).

Section 3 states:

> Since Vivi Music is licensed under the GPL-3.0 license, any project copying, modifying, or using this code **must also be fully open-sourced** under the GPL-3.0 license. You cannot copy this code into a closed-source or proprietary application.

This expressly contemplates applications/repositories outside official Vivi Music,
including adaptation and reuse of the named scraping/signature logic. Read with
the contemporaneous GPL headers and module document below, it is substantial
public permission evidence, not merely a private grant that must be obtained
individually. There is no requirement that express permission use a particular
magic phrase or be addressed to `yren411` personally.

However, the rules do not enumerate code versions, explain later relicensing,
identify all rights holders, or explicitly reconcile the current proprietary
module/source notices. Their named scope is scraping/signature **logic**; the
opening is broader, but coverage of every later module/model/test contribution
cannot simply be assumed. They do not license Musixmatch's lyric catalog,
third-party trademarks, service access, or any third-party rights the publisher
does not control. Those issues are distinct from permission to reuse source code.

Section 2 separately imposes documentation/source attribution and the UI label
**`JioSaavn (via vivimusic)`** (or equivalent powered-by credit) for reused JioSaavn
streaming/search/decryption logic. It is not a Musixmatch-only document; that
separate obligation should also be checked before distribution if that logic is
reused. No JioSaavn functionality is changed by this review.

### Simultaneous restrictive wording

[Upstream LICENSE, lines 1–2](https://github.com/vivizzz007/vivi-music/blob/b41c9e563ade592c03435d91d984a0ff56a42861/LICENSE#L1-L2), identical in
[Tinium](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/LICENSE#L1-L2), grants a special GPLv3 linking permission but says:

> You may inspect the source code of the "musixmatch" module, but copying, reproducing, adapting, modifying, or redistributing the "musixmatch" source code in any other application, fork, or repository is strictly prohibited without the express permission of the author.

Tinium's [Musixmatch.kt](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/lyricsProvider/src/main/kotlin/com/music/musixmatch/Musixmatch.kt#L1-L8)
and [Models.kt](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/lyricsProvider/src/main/kotlin/com/music/musixmatch/models/Models.kt#L1-L8)
retain `VIVI-LYRICS (C) 2026` and say:

> This file is source-available for viewing. Copying, modification,
> redistribution, or reuse in other applications is strictly prohibited.
> Licensed exclusively for use in the official vivimusic application.

Current upstream prepends a separate `vivimusic Project (C) 2026` /
`Licensed under GPL-3.0 | See git history for contributors` block to **both** files
while retaining those proprietary lines immediately below. That is competing
license evidence, not a deleted restriction or an explicit `GPL OR proprietary`
license choice. Tinium's inspected versions do not have that additional block.

## 3. History, authorship and applicable revisions

| Commit / recorded date | Actual change | Significance |
| --- | --- | --- |
| [f11905476a4ef74ecb79c81c290aafd76e8ea5ef](https://github.com/vivizzz007/vivi-music/commit/f11905476a4ef74ecb79c81c290aafd76e8ea5ef), July 5 20:26:42 | Make JioSaavn/Musixmatch integrations public for reproducible builds | Context for the subsequent reuse documents; not sole licensing proof |
| [5df1f44ed0b696ed77addd1b80246002fc262ed4](https://github.com/vivizzz007/vivi-music/commit/5df1f44ed0b696ed77addd1b80246002fc262ed4), July 5 21:05:39 | Add `musixmatch/musixmatch.md` and `jiosaavn/jiosaavn.md` | Module text explicitly says “This code is licensed under the GPL-3.0 license” and gives the same source/UI/open-source conditions |
| [c5d1d5f0937b9e4035e18a44ed84da5a80186833](https://github.com/vivizzz007/vivi-music/commit/c5d1d5f0937b9e4035e18a44ed84da5a80186833), July 5 21:08:02 | Add the 22-line `rules.md` | Root rules unchanged through both inspected current trees |
| [2d48eb187496bb2b02b519b5bacf57077e4f2075](https://github.com/vivizzz007/vivi-music/commit/2d48eb187496bb2b02b519b5bacf57077e4f2075), July 6 08:29:03 | “Relicense musixmatch module under proprietary source-available terms with GPL exception”; modify LICENSE and three source/test headers | About 11 hours later; same recorded author/committer; `rules.md` is left intact |
| [4214425d9a8d318772e3aaa1abcf2cde964f43a8](https://github.com/vivizzz007/vivi-music/commit/4214425d9a8d318772e3aaa1abcf2cde964f43a8), July 19 | Move the separate modules into `:lyricsProvider`; preserve package/source | Explains current paths; old `musixmatch/musixmatch.md` is deleted in consolidation, not a new permission/revocation statement |
| [fda74c92be5e6f2e35e20f4f98c61618aa76d2dd](https://github.com/vivizzz007/vivi-music/commit/fda74c92be5e6f2e35e20f4f98c61618aa76d2dd), September 14 | Optimize Musixmatch fetching, after other September/August updates | Tinium contains post-July-6 revisions, not only the original GPL snapshot |
| [0c94fdc0d95fae7d8223a500df1789e6d2d4749b](https://github.com/vivizzz007/vivi-music/commit/0c94fdc0d95fae7d8223a500df1789e6d2d4749b), September 27 | Add generic GPL project headers to 269 Kotlin files, including the two restricted files | Commit says it is a missing-header chore with no functional changes; leaves proprietary notice and root exception intact |
| [7ece5ea18ff5f60476a2373e7669096b04af0979](https://github.com/vivizzz007/vivi-music/commit/7ece5ea18ff5f60476a2373e7669096b04af0979), October 12 | Further upstream Musixmatch implementation update | Current upstream code differs from the Tinium snapshot |

The rules, July-6 relicensing, consolidation and listed module updates record
`vivizzz007` as author and committer. The September-27 generic header commit also
records that same actor. A common recorded publisher makes a standing permission
interpretation plausible, but the explicit relicensing message and retained
conflicting notices make its intended version coverage unclear. No
rights-holder clarification resolving that conflict was found in these documents.

Ancestor checks confirm `c5d1d5f` precedes `2d48eb1`; both are ancestors of Tinium's
base and current upstream. `0c94fdc` and `7ece5ea` are ancestors of current Vivi
main but **not** ancestors of Tinium's base. They must not be described as license
headers already present on Tinium's actual files.

### Earlier GPL rights are not automatically revoked

The July-6 patch changes the headers and root exception, not the implementations
of `Musixmatch.kt` or `Models.kt`. Removing just each file's leading comment shows
identical implementation bytes across that commit. Immediately before it, the
implementation carries an explicit GPL-3.0 header, and `Musixmatch.kt` expressly
requires attribution to the Vivi Music repository for copying/adaptation.

[GPLv3 section 2](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/LICENSE#L159-L164) says granted rights are irrevocable while
conditions are met. A later proprietary release or file header does not by itself
withdraw an earlier valid GPL grant for the already-published code. Conversely,
that earlier grant does not automatically license **new copyrightable additions**
in every later revision. Tinium's current Musixmatch and model files differ from
the pre-July-6 versions: ordinary Git text diff reports respectively 142 additions /
34 deletions and 21 additions / 3 deletions, including header/path differences.
These counts establish difference, not a legal attribution or originality test.

Therefore a version-specific GPL provenance review is a possible compliant path,
without accepting an automatic restriction-first rule or deleting the module.
It has not been completed for every later contribution. Do not silently roll back
to the older implementation or claim all newer material is licensed by that older
grant. The later generic GPL header may support a renewed/parallel grant, but its
coexistence with proprietary terms and absence from Tinium's actual files still
requires interpretation, rather than blindly importing the newest upstream code.

## 4. Does rules.md supply the contemplated express permission?

**For covered, validly GPL-granted code, the public rules can be the conditional
reuse permission; an individually addressed private letter is not inherently
required.** The historical GPL headers and module-specific reuse document
strengthen that reading. “There is no permission evidence” is no longer a tenable
assessment.

For **all current Tinium module contributions**, two live readings remain:

1. The root rules are a standing public exception allowing reuse in GPL forks,
   conditional on source/UI credit and full source publication, notwithstanding
   the retained proprietary-module notices.
2. The rules describe the earlier GPL release (or remain stale/general guidance),
   while the later explicit relicensing treats newer module additions differently.
   The September bulk-added GPL header creates a further inconsistency rather
   than clearly documenting the intended relationship.

History gives real support to both the earlier GPL grant and an unresolved
current-version conflict. It is insufficient to certify that the entire current
module is cleared simply by adding credit. A rights-holder clarification or a
complete version/contribution permission mapping would resolve the **narrow**
remaining gate. This is an evidence-based engineering assessment, not a ruling
that the owner's existing distribution is unlawful.

## 5. GPLv3 section 13, the rules' GPL requirement and BitChord AGPL

The root rules require fully open-source GPL-3.0 reuse; they do **not** say
“GPL-only”, “no AGPL components”, or prohibit GPL's own section-13 permission.
[GPLv3 section 13](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/LICENSE#L558-L566) expressly permits linking/combining GPLv3
and AGPLv3 covered works and conveying the combination. GPL terms continue for the
GPL portion, with applicable AGPL section-13 network requirements for the
combination. AGPLv3 section 13 reciprocally permits GPLv3 combinations while the
GPL work remains governed by GPLv3.

Consequently **retaining Vivi/Tinium GPLv3 code and adding a separately identified
AGPLv3-or-later DSP component is not inherently inconsistent with rules.md**.
There is no need to relabel the native files GPL-only or automatically relicense
all Vivi code as AGPL-only. Normal applicable source/notices/build/install and
network-interaction obligations must be honored. Networking to fetch music alone
is not proof of users remotely interacting with a modified covered program in the
sense of AGPL section 13; evaluate actual features/use instead of assuming either
universal applicability or exemption.

For a genuinely GPL-covered module, ordinary section-13 combination permission
means a separate special BitChord/Orchard exception is not automatically needed.
If the module remains proprietary for the relevant contributions, neither a
GPL-side proprietary linking exception nor section 13 automatically grants the
missing rights. That is the unresolved scope question; it is not a general
GPL/AGPL incompatibility.

The rules' reasonable source/UI attribution conditions should be preserved.
[GPLv3 section 7(b)/(c)](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/LICENSE#L368-L381) recognizes reasonable legal/author
notices and origin/modification labeling. Their exact legal classification should
not be invented or used to strip credits. Section 7's treatment of further
restrictions is not permission to erase proprietary notices on disputed code
without first establishing the underlying GPL grant and relevant rights.

The GNU-hosted AGPL text request returned HTTP 403 in this environment. Its section
13 was checked using the [SPDX license-list-data v3.27.0 AGPL-3.0-or-later text](https://github.com/spdx/license-list-data/blob/v3.27.0/text/AGPL-3.0-or-later.txt),
SHA-256 `d8a6cc31abc16b6748c7a21f21611f5a1ec33f67d22ca23d7da1c19b95496bee`.
Tinium's checked-in GPL text supplies the other cited license sections.

## 6. Exact attribution requirements and current gaps

If the rules/grant applies to the selected versions, the compliant path includes:

- **Repository documentation:** explicitly identify the Musixmatch scraping and
  signature logic as obtained from **Vivi Music**, linking
  `https://github.com/vivizzz007/vivi-music`, with pinned source/provenance and
  modifications. Generic app-fork credit does not on its own establish the named
  provider/logic attribution.
- **Source headers:** preserve relevant copyright/license notices and explicitly
  state that the reused logic was obtained from that same Vivi Music source.
  Record modifications as required. Do not remove conflicting notices until their
  intended grant/scope is resolved.
- **Visible provider-specific UI:** display **`vivimusic lyrics provider`**, or an
  explicit equivalent such as **“Lyrics provided by Vivi Music”**, where users can
  identify the lyrics provider (lyrics view/settings are examples in the rule).
  The rule does not require both literal provider wording and an additional
  equivalent sentence; it expressly allows the powered/provided-by alternative.
- **Open source:** provide the actual complete corresponding source and required
  license/build information for distributed versions; a public repository with
  unavailable dependencies is not automatically sufficient.
- **Native attribution later:** preserve SFG545/Orchard and Kushagra Singh/BitChord
  notices, AGPL text and actual copied/adapted source inventory; retain VIVI credit.

[Tinium README](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/README.md) already links and credits Vivi Music as its parent.
However, the inspected `Musixmatch.kt`/`Models.kt` headers do not contain the
specific obtained-from source statement. [ContentSettings.kt](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/app/src/main/kotlin/com/music/vivi/ui/screens/settings/ContentSettings.kt#L1058-L1062)
shows the title `Musixmatch` and generic database description;
[LyricsProviderRegistry.getDisplayName](https://github.com/yren411/vivi-music-personal/blob/e3f1fb2abb8d79f959ee55c687223c26067d53ca/app/src/main/kotlin/com/music/vivi/lyrics/LyricsProviderRegistry.kt#L68-L75)
also returns `Musixmatch`. Searches of the relevant Kotlin/resources found no
`vivimusic lyrics provider` or provider-specific powered/provided-by Vivi Music
credit. These paths do not demonstrate the required UI attribution. This is a
source audit, not a visual inspection of every rendered/translatable screen.

Future attribution work should change **display text**, not the persisted provider
key `Musixmatch`, saved order, settings or identities. No app/header/UI change is
made by this documentation reassessment. Adding a permission-analysis paragraph
here does not make the current UI satisfy the rule. Section-2 JioSaavn credits
also need their own compliance check if that code is reused.

## 7. Precise clarification needed, without automatically replacing the module

The following is a draft request for the relevant rights holder, **not a message
sent by this task**:

> At Vivi Music revision `b41c9e563ade592c03435d91d984a0ff56a42861`, `rules.md`
> permits GPL reuse with documentation/source/UI attribution, while LICENSE and
> the Musixmatch headers retain the July-6 proprietary restriction. Does rules.md
> provide the express permission contemplated by that exception for GPL forks
> such as `yren411/vivi-music-personal`, including its current Musixmatch scraping,
> signature, models and relevant test code? Please identify which revisions and
> post-July-6 additions are covered, including Tinium's files at
> `e3f1fb2abb8d79f959ee55c687223c26067d53ca` (hashes below).
>
> Does the grant permit copying, adaptation/modification and redistribution of
> source and APKs with normal downstream GPLv3 rights when the stated credits and
> open-source conditions are met? How should the retained proprietary and later
> generic GPL notices be interpreted/preserved? Please confirm the grant is given
> by or with authority from the relevant rights holders, identifying any exclusions.
>
> We intend to retain the Vivi-derived portions under GPLv3 and combine a separately
> identified AGPLv3-or-later Orchard/BitChord DSP component using GPLv3 section 13.
> Does the GPL requirement mean ordinary GPLv3 including that permission, rather
> than an additional prohibition on such components? Is the exact UI credit
> `vivimusic lyrics provider` (or explicit provided/powered-by Vivi Music wording)
> plus linked source/documentation attribution sufficient for the named credit
> conditions?

A public clarification pinned to code versions can resolve this; no custom private
exception should be demanded if an applicable public GPL grant is established.
Alternatively, a version-by-version audit can establish which current portions are
covered by already-valid GPL grants. Automatic module removal, source replacement,
rollback or upstream update is neither necessary as a first step nor authorized by
this task. No one was contacted through email, GitHub comments or other messaging.

## 8. BitChord source inventory and file-level licenses

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


### Additional reuse-rule evidence hashes

```text
2e2b6ea5dadcd3a8cdc85b028d38a5bdf24b27ce168fd1d6b44fc1c179fb80b3  rules.md (both Tinium base and Vivi inspected main)
673fc3c016ffe7e1308c653cccda56d38cdeec9dad6c12b82496cdc68ce55715  Musixmatch.kt (old musixmatch/ path at c5d1d5f)
19a4aa009b110068119b79888800db7788c339cb0a6639b5bdafce0a47b52adf  models/Models.kt (old musixmatch/ path at c5d1d5f)
a73db2b8b516275b1a15daee08d5e4bc06fe287c4693e7105d165bc278627d88  upstream current Musixmatch.kt
8bb990b6f5eacb5a18f3b877a0ba058578ec6948741c9df62715c4aa0e8bdcd5  upstream current models/Models.kt
```

Tinium and upstream `rules.md` Git blob: `1fb5ad78ac78e202ff7b9d25aa8482c15d1d8f16`.
Both root LICENSE blobs: `1503e5f69bedb42a22745b69a8a031ac075d449b`.
Tinium Musixmatch/model blobs: `1c7dc01777a97cc631529579db70f553a923e05c` /
`237a9f8b5363afd0416d42189ba9dd059e658abb`.
Current upstream Musixmatch/model blobs: `df0f1b8a2cd1ab1150fa5c729878834c62b59d82` /
`9775a3f424235b01533c65167ed51f9d2d04e2fd`.

## 9. Revised decision and continuation path

1. Treat the public GPL reuse rules and historical GPL module releases as genuine
   permission evidence; retract the earlier blanket absence-of-permission claim.
2. Resolve the current revision/contribution ambiguity through the specific
   clarification above or complete GPL provenance mapping. Do not require a
   blanket AGPL exception solely because the root rules say GPLv3.
3. Before distribution under that path, implement/review the exact source,
   documentation and visible provider credit conditions in a separately authorized
   app change, preserving stored provider keys and existing functionality.
4. Resume the minimal pinned native implementation only under a subsequent
   authorization, retaining applicable GPL/AGPL notices and corresponding-source
   obligations. The architecture checkpoints remain in
   [AUTOMIX_BITCHORD_NATIVE_ANALYZER.md](AUTOMIX_BITCHORD_NATIVE_ANALYZER.md).

This is **conditionally resumable**, with a narrower current-code permission and
attribution gate, not proof that the complete current application is cleared for
redistribution. No functionality, license text, restricted implementation,
release notes, dependencies, native code, APK, release or production default was
changed or published during this reassessment.
