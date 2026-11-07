# BoostBuddy TV validation — 1.16.0-tv.2

Validation date: **2026-11-06**. Review baseline: `b73674c913cc71fc2db5db499263adb45afeed0f` (`1.16.0-tv.1`). Upstream base: BoostBuddy **1.16.0**, commit `b9acb6c2cde4f37907e70bf166a2f27c7d57c322`.

The [code-review report](docs/CODE_REVIEW_TV.md) explains the findings and corrections. This record distinguishes automated coverage, offline emulator checks, and account/device checks that remain outstanding.

## Clean build and automated checks

The final source was copied to an isolated build directory and built with **JDK 17, Android SDK 35, and Gradle 8.14.3**. Build outputs were cleaned, build-cache reuse was disabled, and the compiled source was compared with the working tree before publication. The equivalent repository command is:

```sh
./gradlew --no-build-cache :composeApp:clean :composeApp:assembleDebug :composeApp:testDebugUnitTest
```

**Result: APK assembly passed; 84 tests passed, with 0 failures, 0 errors, and 0 skipped.** This adds 66 regression tests to the previous 18. The final local test reports were recorded at **13:24:34–13:24:36 UTC**.

| Coverage | Result |
| --- | --- |
| Login validation, consumed-code retry, and cancellation | 4 passed |
| International phone normalization | 4 passed |
| Feed sessions, polling, refresh, and pagination | 7 passed |
| TV focus ownership, restoration, and search drafts | 13 passed |
| TV video mapping, availability, and progress | 7 passed |
| Synchronous stop/release and stale-player ownership | 3 passed |
| Fresh playback URLs, access checks, and stream identity | 5 passed |
| Final position, failed-save retry, cancellation, and forced zero | 9 passed |
| HTTP authentication retries, origin restrictions, and redirects | 3 passed |
| Real loopback HTTPS certificates and hostname checks | 4 passed |
| Concurrent refresh, logout/account replacement, caller cancellation | 5 passed |
| Credential persistence, migration, corruption, and revision guards | 6 passed |
| Subscription pagination and deduplication | 8 passed |
| Trusted website origin validation | 2 passed |
| Handler identity, dispatch order, and removal | 4 passed |

The HTTPS tests use an actual local TLS server and the Android HTTP engine. They require rejection of untrusted certificates in both debug and release configurations, rejection of a trusted certificate for the wrong hostname, and success for a trusted matching certificate. Authentication tests use a mock HTTP engine; other tests use synthetic data and controlled coroutine scheduling. These are JVM/component tests, not a live Boosty integration suite or instrumented Compose UI tests.

Additional checks:

- `apksigner verify --verbose` passed; APK Signature Scheme v2 verified.
- APK metadata confirmed package `ru.slartus.boostbuddy.tvfork`, version `1.16.0-tv.2`, code 90, minimum SDK 24, target SDK 34, and compile SDK 35.
- `git diff --check` passed.
- The source changes received independent reviews of authentication/session behavior, feed/playback behavior, and TV navigation; lifecycle cleanup and initial focus were corrected after those reviews.

Upstream deprecation and Kotlin Multiplatform warnings remain. The inherited iOS targets were not built on this Linux host. The earlier stale-output APK from the first implementation cycle remains superseded; this review used isolated clean outputs again.

## Emulator runtime checks

Device: **Android TV 10 / API 29, x86, 1280 × 720 pixels, 213 dpi, software emulation**.

The following were observed during the review cycle using the normal activity or the debug-only offline fixture:

| Check | Observed result |
| --- | --- |
| Normal `TvAppActivity` launch | Native sign-in form rendered with an empty phone-number field. |
| Login remote focus | D-pad Down moved from the phone field to **Получить код**. |
| Empty-phone validation | Center on **Получить код** displayed local validation, with focus on **Понятно**; no verification-code request was sent. |
| Offline feed preview | Actual sidebar/video-grid components rendered with fictional local fixtures. |
| Explicit grid entry and movement | Right entered content from the sidebar; subsequent Right moved to the next video card, confirmed in Android's UI hierarchy. |
| Sidebar and subscriptions | Left returned to the sidebar; Down/Center opened Subscriptions and focused the first creator. |
| Search entry | Selecting Search focused its native text field; a local draft was entered successfully. |

The first candidate exposed an initial-focus problem: Android's automatic menu focus cancelled the pending grid/bookmark restoration. The correction distinguishes automatic initial focus from explicit remote input and has two dedicated regression tests. Search-draft round-trip behavior is covered by the helper tests; an intermediate emulator hierarchy capture timed out and is not counted as a completed runtime round-trip check.

The exact final APK identified below was then installed successfully on a freshly wiped emulator using non-streaming installation. The normal TV activity reached `RESUMED`, and Android recorded its `Displayed` event after 49.652 seconds. The crash buffer was empty. Screenshot and UI-hierarchy capture timed out, so **initial first-card focus on the final APK remains runtime-unverified**. The captured general log was partial and also contained ANRs in Google keyboard/background services; that suggests emulator performance trouble but does not establish the cause of the capture failures. The bounded session stopped the emulator and ADB server afterward. No further runtime result is inferred from those incomplete captures.

### Screenshot provenance

The README's three screenshots remain the unedited `1.16.0-tv.1` captures of the same screen layouts. They are not relabelled as new screenshots of `tv.2`. Feed/subscriptions images visibly display **DEMO DATA · OFFLINE**; their creators, thumbnails, durations, dates, and progress are fictional fixtures. Playback and refresh actions in that fixture are inert.

| Capture | Content |
| --- | --- |
| [tv-login.png](docs/screenshots/tv-login.png) | Normal native login, empty phone-number field. |
| [tv-feed-demo.png](docs/screenshots/tv-feed-demo.png) | Actual feed components with offline demo data. |
| [tv-subscriptions-demo.png](docs/screenshots/tv-subscriptions-demo.png) | Actual creator directory with offline demo data. |

See [screenshot provenance and reproduction steps](docs/screenshots/README.md). The fixture is debug-only and excluded from release builds.

## Validated local APK

| Property | Value |
| --- | --- |
| Application ID | `ru.slartus.boostbuddy.tvfork` |
| Version | `1.16.0-tv.2` / version code 90 |
| Launcher label | BoostBuddy TV |
| TV activity | `ru.slartus.boostbuddy.TvAppActivity` |
| Android SDK levels | Minimum 24, target 34, compile 35 |
| Build | Clean local debug APK with offline preview support |
| Signing | Local Android debug certificate; APK Signature Scheme v2 verified |
| Size | **28,651,982 bytes** |
| Network/SDK properties | Normal TLS verification; no AppMetrica SDK or package-install permission |

SHA-256 of this local APK:

```text
7cf09837c64cf601b4248f59a27355381d8c263290a62817d1a9dbf2d7243ae6
```

A GitHub Actions APK has its own checksum and signing certificate. Verify it against the `SHA256SUMS` included in that artifact, rather than this local-build digest.

## GitHub Actions coverage

The [Build TV APK workflow](.github/workflows/build-tv.yml) runs for every branch push, pull request, and manual dispatch. Its read-only build job assembles the debug APK and runs `:composeApp:testDebugUnitTest` before uploading the APK artifact. APK filenames include the app version, version code, and built commit; test/build reports are a separate artifact.

The workflow also runs credential-free Python checks of the publishing scripts in `.github/tests/`. Those checks are separate from the historic Android test totals recorded above.

After a successful branch-push or manual run on a branch, a separate job with `contents: write` is configured to publish a new prerelease tagged `ci-<GITHUB_RUN_ID>-<GITHUB_RUN_ATTEMPT>` at the exact built commit. Pull requests retain their artifacts and reports without release publication or a release write token; branch deletions and tag refs do not publish. Separate workflow-run concurrency groups with cancellation disabled preserve independent pipelines, including those for the same commit. The publisher fails on a tag collision or permission rejection instead of moving a tag or changing the target commit.

The release stays in draft until its APK, `SHA256SUMS`, `build-info.json`, and `INSTALL.txt` are uploaded and verified; it is then published as a prerelease without changing the stable Latest marker. Reruns use a new publication-attempt tag. A publish-only rerun downloads the original successful build artifact by ID and preserves its original build `runAttempt` metadata, APK bytes, and certificate without rebuilding or re-signing; release notes identify the separate publication attempt.

The public [Releases page](https://github.com/fgsfds/BoostBuddy-TV/releases) provides direct APK downloads without an artifact ZIP or GitHub sign-in. Actions APK artifacts and reports retain their **14-day** lifetime, subject to repository policy. Release assets are not subject to that artifact expiry, and the workflow does not automatically delete releases.

Fresh builds still use ephemeral debug keys, so an APK from another build may require uninstalling a previous installation. A persistent owner-controlled release key is needed for stable in-place updates; see the [installation and signing guide](README_TV.md).

Consult the [Actions run](https://github.com/fgsfds/BoostBuddy-TV/actions/workflows/build-tv.yml) and its release for the desired commit to see the actual build and publication results. This section describes the current CI configuration; the historic local checks above do not establish that a later pipeline or release succeeded.

## Account and hardware coverage

**No live account sign-in, verification-code request, or video playback was attempted.** No user token, paid-content session, physical TV, or hardware-decoding test was used. Tests for refresh, access checks, media URL recovery, and playback lifecycle establish local behavior with controlled inputs; they do not establish compatibility with every current Boosty response or TV decoder.

Remaining account/device smoke checks:

1. Sign in through the native form with a phone number linked to an existing Boosty account.
2. Open a real creator, scroll to a later video, play it, seek, change quality, pause/resume, and background/return to the app.
3. Press Back and confirm the focused video and grid position are restored after actual playback.
4. Search real posts, refresh the feed, and sign out through the sidebar.
5. Where available, check more than 30 subscriptions, a text-only page between videos, an inaccessible post, token renewal, and an expired media URL.

The APK is a debug build for sideload testing, not a store release.
