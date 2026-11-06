# BoostBuddy TV validation

Validation date: **2026-11-06**. Upstream base: BoostBuddy **1.16.0**, commit `b9acb6c2cde4f37907e70bf166a2f27c7d57c322`.

## Build correction and scope

An earlier APK assembled from the working build tree contained stale compiled classes and crashed at startup. **That APK is superseded.** The current validation used a fresh source copy, isolated build outputs, and no build cache. Its normal TV activity and offline preview activity then launched and rendered successfully, with no Android runtime fatal exceptions observed during the capture session.

The APK identity and digest below refer to this **clean local debug build**, which includes the debug-only offline screenshot fixture. Later GitHub Actions builds have their own commit, signing certificate, and checksum in their artifact's `build-info.json` and `SHA256SUMS`.

## Automated checks

| Check | Result |
| --- | --- |
| Fresh Android debug APK assembly | Passed with JDK 17, Android SDK 35, and Gradle 8.14.3; private outputs and no build cache. |
| `:composeApp:testDebugUnitTest` | **18 tests, 0 failures, 0 errors, 0 skipped.** |
| International phone normalization | 4 tests passed. |
| Subscription pagination | 8 tests passed. |
| TV video-card mapping | 6 tests passed. |
| APK signature verification | `apksigner verify --verbose` passed; APK Signature Scheme v2 verified. |
| Package and launcher inspection | `aapt dump badging` confirmed the fork package, version, SDK levels, and TV entry point. |
| Whitespace / patch integrity | `git diff --check` passed. |

The fresh test reports were recorded at **11:07:47 UTC** on 2026-11-06. Tests use synthetic data and cover international calling codes, more than 30 subscriptions, malformed/duplicate rows, server page sizes, stale totals, repeated/ignored offsets, bounded pagination, multi-video posts, stable identities, locked teasers, processing videos, duration, and progress.

Feed refresh and token-renewal interactions also received source review. Refresh invalidates its generation before suspending; pagination rejects stale generations after token retrieval. The rotated refresh token and expiry are saved before publishing the new access token. Those concurrency paths were reviewed in source, not exercised against a live Boosty session.

The build retains upstream deprecation and Kotlin Multiplatform warnings. The iOS targets were not built on this Linux host.

## Emulator runtime checks

Device: **Android TV 10 / API 29, x86, 1280 × 720 pixels, 213 dpi, software emulation**.

| Check | Observed result |
| --- | --- |
| Normal `TvAppActivity` launch | Native sign-in form rendered; phone-number field remained empty and the keyboard was dismissed for capture. |
| Offline feed preview | Actual TV sidebar and video-grid components rendered using fictional local fixtures. |
| Video-card focus | D-pad movement from the first card to the second was checked in Android's UI hierarchy. |
| Sidebar navigation | Left returned focus to the sidebar. |
| Subscriptions navigation | Down/Center opened Subscriptions; the first creator's focus was confirmed in the UI hierarchy. |
| Runtime stability in this session | No Android runtime fatal exceptions after the clean build. |
| Emulator shutdown | Graceful shutdown; capture session exited with status 0. |

The three screenshots are unedited `adb exec-out screencap -p` captures. The feed and subscriptions captures visibly display **DEMO DATA · OFFLINE**. Their creators, thumbnails, avatars, durations, dates, and progress values are fixed local fixtures; they do not show a connected account. Playback and refresh actions in that fixture are inert.

| Capture | Content |
| --- | --- |
| [tv-login.png](docs/screenshots/tv-login.png) | Normal native login, empty phone-number field. |
| [tv-feed-demo.png](docs/screenshots/tv-feed-demo.png) | Real feed components with offline demo data. |
| [tv-subscriptions-demo.png](docs/screenshots/tv-subscriptions-demo.png) | Real creator directory with offline demo data. |

See [screenshot provenance and reproduction steps](docs/screenshots/README.md). The fixture activity is debug-only and excluded from release builds.

## Validated local APK

| Property | Value |
| --- | --- |
| Application ID | `ru.slartus.boostbuddy.tvfork` |
| Version | `1.16.0-tv.1` / version code 89 |
| Launcher label | BoostBuddy TV |
| TV activity | `ru.slartus.boostbuddy.TvAppActivity` |
| Android SDK levels | Minimum 24, target 34, compile 35 |
| Build | Clean local debug APK with offline preview support |
| Signing | Local Android debug certificate; APK Signature Scheme v2 verified |
| Size | **28,602,830 bytes** |
| Network/SDK changes | AppMetrica SDK and package-install permission removed |

SHA-256:

```text
6035e2d89f10f0bdd19c4dd01ff53ca6851f28f12e8cc4bdbe88b358a511512a
```

This replaces the earlier 28,917,113-byte APK and its checksum. A CI artifact's digest will differ; verify it against that artifact's accompanying `SHA256SUMS`.

## GitHub Actions coverage

The [Build TV APK workflow](.github/workflows/build-tv.yml) is configured for every branch push, pull request, and manual dispatch. It assembles the debug APK and runs the unit tests before uploading an APK. APK filenames include the app version, version code, and built commit. The archive contains a checksum, build metadata, and installation notes; test/build reports are a separate artifact.

Both artifact types are retained for **14 days**, subject to repository policy. Each fresh runner uses an ephemeral debug key, so later CI APKs may require uninstalling a previously installed build. A persistent owner-controlled release key is needed for stable in-place updates. See the [installation and signing guide](README_TV.md).

This document records the local build and emulator session. Consult the [Actions run](https://github.com/fgsfds/BoostBuddy-TV/actions/workflows/build-tv.yml) corresponding to a commit for that CI build's result; the local checks do not imply that later runs passed.

## Account and hardware coverage

**No live account sign-in, verification-code request, or video playback was attempted.** No user token, paid-content session, physical TV, or hardware-decoding test was used. Token renewal against Boosty, actual subscription/feed responses, account entitlements, and signed media URL recovery still require an account/device smoke test.

The offline navigation checks establish that the tested components render and respond to the remote in the emulator. They do not establish successful authentication or playback. The validated APK is a debug build for sideload testing, not a store release.

## Remaining device smoke checks

1. Navigate the normal login form using only the remote, then sign in with a number linked to an existing Boosty account.
2. Open a real creator, scroll to a later video, play it, seek, change quality, and return with Back.
3. Confirm the focused video and grid position are restored after actual playback.
4. Search real posts, refresh the feed, and sign out through the sidebar.
5. Where available, check more than 30 subscriptions, a text-only page between videos, an inaccessible post, token renewal, and an expired media URL.

These account/hardware checks remain follow-up work; they are not claimed as completed.
