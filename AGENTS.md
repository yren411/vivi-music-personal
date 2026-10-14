# Release notes for Codex changes

After implementing a meaningful user-visible feature, bug fix, branding change,
or release-preparation task:

1. Inspect the actual diff and validation results. Draft accurate, concise notes
   describing user-visible behavior; omit invented features, unverified claims,
   private credentials and internal implementation detail that users do not need.
2. Use an exact target version only when the user specifies it or a reviewed
   release plan clearly assigns it. Gradle defaults, example versions and the
   presence of an older notes file are not a release assignment. Do not choose
   or reserve a versionCode, versionName or tag just to write notes.
3. For an assigned, unreserved candidate, create/update
   `release-notes/<versionName>.md` with `# Tinium <versionName>` as its heading.
   Preserve existing reviewed entries and deduplicate additions. Before changing
   versioned notes, inspect any existing candidate/tag/release reservation:
   already-reserved or published candidates are immutable. If the intended
   candidate is reserved, put the new changes in the unversioned draft and explain
   that a fresh user-chosen version is needed; never retarget a tag or overwrite
   candidate artifacts/notes to reuse it.
4. When no target is assigned, create/update `release-notes/unreleased.md` instead.
   Keep it explicitly unversioned and preserve others' entries. Ask for a version
   only when a requested release build actually requires that decision. Do not
   create unnecessary version numbers for small tasks or documentation-only work.
5. Keep notes non-empty UTF-8 Markdown, at most 50 KB, and exclude the reserved
   `tinium-release` provenance comment. Read `release-notes/README.md` and
   `docs/RELEASING.md` for the existing build/review/publish process.
6. Include the notes file in the same pull request as the implementation. Report
   its exact path and whether it is an unversioned draft or versioned candidate
   notes. Separate implemented behavior from any manual/device checks still needed.

Notes are drafts for manual review, not authorization to allocate releases,
change signing keys/secrets, dispatch release builds or publish. Preserve the
coordinated beta/stable code sequence, permanent signing, immutable reservations,
certificate checks and separate manual publishing approval. GitHub Actions must
still require reviewed `release-notes/<versionName>.md` before a candidate build;
never generate generic notes in CI to bypass that check. Unversioned drafts are
not build inputs. When the user chooses a fresh version, review/copy the relevant
entries into its versioned file and remove only the entries assigned to it from
the unversioned draft in the same reviewed PR.
