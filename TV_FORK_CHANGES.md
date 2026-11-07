# TV fork modification notice

This source tree modifies BoostBuddy 1.16.0, upstream commit `b9acb6c2cde4f37907e70bf166a2f27c7d57c322`, to implement the requested Google TV client. Changes made on 2026-11-06 include:

- New Android TV sidebar, subscriptions directory, video grid, search and remote focus restoration.
- Native phone-code sign-in as the default, with international phone validation and a compact TV layout.
- Complete subscription pagination and safer feed refresh/pagination cancellation.
- Video-card metadata retention, playback eligibility states and consistent media request headers.
- Separate fork package, label, banner and build/signing configuration.
- Removal of the upstream analytics SDK, automatic update prompts and sensitive network/media diagnostics.
- Android unit tests, fork documentation, and a debug APK workflow for all branch pushes, pull requests, and manual runs.
- Actual emulator screenshots, with a debug-only offline fixture for the browsing captures.

## 1.16.0-tv.2 code review

The follow-up review on 2026-11-06 adds:

- Standard TLS certificate/hostname verification in debug and release HTTP clients and website sign-in, with real loopback HTTPS regression tests.
- One migrated credential record, shared refresh work, session/revision guards, origin-restricted Bearer credentials, and isolated profile validation before login is published.
- Native retry after a code was accepted but profile validation temporarily failed, without consuming another code.
- Session-aware cancellation and data clearing, paging-cursor preservation, and stale refresh/search protection.
- Fresh playback URL/access checks, reliable final progress saves, and direct lifecycle cleanup with position/play-state restoration.
- Explicit TV focus ownership, grid/footer restoration, preserved search drafts, and per-account browse-state reset.
- Unique lifecycle-bound exception handlers, safe root navigation, and website-view cleanup.
- Expanded regression coverage and an updated [review report](docs/CODE_REVIEW_TV.md) and [validation record](VALIDATION_TV.md).
- A root [AGENTS.md](AGENTS.md) documenting the current architecture, verification commands, and implementation rules for all contributors and coding tools.

## Shared development documentation

The repository uses [AGENTS.md](AGENTS.md) as its canonical project guide, with practical screen, navigation, API, and review workflows in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Tool-specific instruction files, model assignments, settings, and hooks were removed. Useful architectural guidance was retained and corrected against the current implementation.

## Automatic CI prereleases

The workflow update on 2026-11-07 adds:

- A new GitHub prerelease after each successful branch-push or manual run on a branch, with a separate publishing job that has `contents: write`. Build/test jobs remain read-only; pull requests produce artifacts and reports without a release or release write token. Branch deletions and tag refs do not publish.
- Tags of the form `ci-<GITHUB_RUN_ID>-<GITHUB_RUN_ATTEMPT>`, targeting the exact built commit through GitHub's refs API, with independent workflow-run concurrency and cancellation disabled. Reruns create a new publication-attempt tag, including when the source commit is unchanged; tag collisions fail without moving or overwriting an existing tag/release.
- Publication of the APK, `SHA256SUMS`, `build-info.json`, and `INSTALL.txt`, with the release kept in draft until uploads finish and are verified. Entries are prereleases and do not change the stable Latest marker.
- Download of the successful build artifact by ID. A publish-only retry reuses its original APK and signing certificate and preserves the build's `runAttempt` metadata, without rebuilding or re-signing; release notes record the separate publication attempt.
- Direct public APK downloads from the [Releases page](https://github.com/fgsfds/BoostBuddy-TV/releases). Actions artifacts/reports retain their 14-day lifetime; release assets have no automatic cleanup and are not subject to that artifact expiry.

The Android app remains `1.16.0-tv.2` / version code 90. Automatic prereleases contain debug APKs with ephemeral build keys; this change does not introduce stable release signing or a Google Play distribution.

The original source copyright notice remains in README.MD and docs/UPSTREAM_README.md; the Apache 2.0 license remains in LICENSE. No SmartTube source code or branding is included.
