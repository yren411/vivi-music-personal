# Release notes for Codex changes

After a meaningful user-visible feature, bug fix, branding change or release
preparation, inspect the actual diff/tests and draft accurate user-facing notes
in the implementation PR. Preserve existing entries, deduplicate additions, and
do not invent features, private credentials, verified device checks or versions.

- Without an assigned public-release target, maintain `release-notes/unreleased.md`.
  A private beta/production build may use this draft even when its versionName is
  known. Do not create a version-specific file merely to unblock a test build.
- For public release preparation with an explicitly assigned fresh version, draft
  `release-notes/<versionName>.md` with `# Tinium <versionName>` as its heading.
  The user must review it before it is committed/used as publishable source notes.
  Gradle defaults, examples and older notes filenames are not release assignments.
- Already-reserved/published candidates pin their source and exact artifacts.
  Never retarget tags or modify artifacts to promote an unreviewed candidate.
  Use a fresh user-chosen version after adding reviewed notes/changing source.
- Keep source notes non-empty UTF-8 Markdown, <=50 KB, with meaningful content
  and no reserved `tinium-release` provenance comment. Public notes must not
  contain the internal-testing placeholder or unreviewed-draft banner.
- Include the notes file in the same PR; report its exact path and whether it is
  an unversioned draft or version-specific source intended for review. Remove
  only entries assigned to a reviewed release from the accumulating draft.

Read `release-notes/README.md` and `docs/RELEASING.md`. Candidate builds select
valid version-specific notes, otherwise meaningful `unreleased.md`, otherwise an
identified internal-testing placeholder. They snapshot exact notes/source hashes
and review status in immutable artifacts without committing generated files.
Draft/placeholder candidates are NOT publishable. Public publication requires
reviewed version-specific notes from the built commit, exact snapshot/hash checks,
explicit `notes_reviewed` confirmation and the existing manual typed-tag approval.
Never mark drafts as reviewed or remove placeholder markers in CI to bypass this.
Preserve version allocation, permanent signing, certificate/provenance checks and
manual publishing. Notes drafting is not permission to dispatch builds, allocate
versions, change keys/secrets or publish. Do not force version bumps for small tasks.

# Future commit attribution

For owner-authorized new commits in this repository, use the owner's requested
name `yren411` and exact owner-provided GitHub noreply address
`285469303+yren411@users.noreply.github.com` (GitHub login/account ID verified).
Set repository-local `user.name` and `user.email`, then check `git var
GIT_AUTHOR_IDENT` and `git var GIT_COMMITTER_IDENT` before committing. Avoid
changing global identity or attributing work to an official bot. Never rewrite
historical authors or upstream attribution. Local Git configuration is not
tracked: repeat this check in fresh cloud checkouts. Environment variables,
host-enforced identity and GitHub-created merge commits can override local Git
settings; AGENTS.md cannot override the host. Report such limits rather than
claiming attribution is guaranteed.
