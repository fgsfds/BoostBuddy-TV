# BoostBuddy TV: installation and development

A native Android TV / Google TV fork of [BoostBuddy](https://github.com/slartus/BoostBuddy), with a remote-oriented sidebar, subscriptions directory, video grid, and native phone-code login. Start with the [project homepage and screenshots](README.MD).

Based on upstream tag **1.16.0**, commit **b9acb6c2cde4f37907e70bf166a2f27c7d57c322**. The layout takes inspiration from SmartTube; it contains no imported SmartTube UI source. This is an independent, unofficial Boosty client.

## What is implemented

| Area | Behavior |
| --- | --- |
| TV navigation | Collapsible sidebar with feed, subscriptions, search, settings, sign out, and followed creators. |
| Video browsing | Responsive 3–4-column grid, 16:9 artwork, titles, creator names, dates, durations, progress indicators, and locked/processing states. |
| Remote control | D-pad focus highlights, Left to return to the sidebar, Right to enter content, Back to the sidebar before navigating back, and per-route scroll/focused-card restoration. |
| Subscriptions | Loads returned subscription pages and deduplicates creators, rather than stopping at the first 30 rows. |
| Feed/search | Existing BoostBuddy APIs connect to the new screens. Pagination continues past text-only pages; page failures offer retry. |
| Native login | Editable country code and phone number, verification-code entry, resend cooldown, profile-validation retry, loading/error states, and an explicit website fallback. |
| Playback | BoostBuddy's native Media3 player with fresh URL resolution, quality selection, serialized progress saves, and lifecycle-aware position/play-state restoration. HLS, DASH, and direct media requests use the same browser User-Agent as the API client. |
| App identity | Separate package `ru.slartus.boostbuddy.tvfork`, label **BoostBuddy TV**, version **1.16.0-tv.2** (code 90), and a TV launcher banner. It can coexist with upstream BoostBuddy. |

The interface retains BoostBuddy's Russian UI language. The main sidebar labels are **Лента** (Feed), **Подписки** (Subscriptions), **Поиск** (Search), **Настройки** (Settings), and **Выйти** (Sign out).

## Download from GitHub Releases

1. Open the repository's [**Releases page**](https://github.com/fgsfds/BoostBuddy-TV/releases).
2. Choose a **Pre-release** for the desired commit and workflow run.
3. Under **Assets**, download the file ending in **`.apk`**. Public release assets can be downloaded without signing in to GitHub, and the APK does not need to be extracted from a ZIP.

Each automated prerelease uploads these four files:

| Asset | Purpose |
| --- | --- |
| `BoostBuddy-TV-…-debug.apk` | Installable debug APK for sideload testing. |
| `SHA256SUMS` | SHA-256 checksum for the APK. |
| `build-info.json` | Exact built/source commits, original build run (`runId`, `runNumber`, `runAttempt`), and APK metadata. |
| `INSTALL.txt` | Installation and signing notes. |

APK filenames follow this pattern:

```text
BoostBuddy-TV-<version>-c<version-code>-<12-character-built-commit>-debug.apk
```

Release assets are not subject to the Actions artifact expiry, and the workflow does not automatically clean up releases. Automated entries are marked as prereleases and leave GitHub's stable **Latest** marker unchanged; use the Releases page to choose a build.

### Actions artifacts and reports

For pull-request APKs or build diagnostics, open [**Actions → Build TV APK**](https://github.com/fgsfds/BoostBuddy-TV/actions/workflows/build-tv.yml) and select the desired run. A successful build provides an APK artifact whose name begins `BoostBuddy-TV-`; test/build reports are separate. Artifact downloads may require GitHub sign-in and arrive as ZIP archives that must be extracted.

Actions APK artifacts and reports have **14-day retention**, subject to repository policy. An already published release remains available after its Actions artifact expires. If no usable build is available, **Run workflow** on a branch creates a new build and, after success, a prerelease. For a repository fork where GitHub has disabled inherited workflows, enable Actions before running it.

## Install the APK

These are **debug builds for sideload testing**. Minimum Android version: **7.0 / API 24**.

Transfer the downloaded APK to the TV and install it using your preferred APK installer, allowing installation from that app if Android asks. Alternatively, with Android Platform Tools and an already connected TV, replace the path below with the APK's actual filename:

```sh
adb install -r path/to/downloaded.apk
```

Launch **BoostBuddy TV** from the TV's apps list. For a developer launch:

```sh
adb shell am start -n ru.slartus.boostbuddy.tvfork/ru.slartus.boostbuddy.TvAppActivity
```

### Signing and later updates

Android requires the same signing certificate to update an installed app in place. **Each fresh CI build uses an ephemeral debug signing key**, so an APK from another build may not install over an earlier one. This applies to both release assets and Actions artifacts. If Android reports a signature mismatch, uninstall the previous fork build before installing the new one; uninstalling removes this app's local sign-in state and settings.

Publishing or retrying a prerelease does not re-sign its APK or increment the Android version. The current app remains **1.16.0-tv.2 / version code 90** until the build configuration is changed explicitly. These are debug builds for sideload testing, not a Google Play distribution.

For stable updates, build releases with your own persistent signing key. The repository does not contain a private signing key, and the default CI workflow needs no signing secrets. Upstream BoostBuddy remains a separate application.

## Native sign-in

1. Enter the country calling code and national phone number in separate fields. `+381` and `+7` are shortcuts; the country code remains editable.
2. Choose **Получить код** and enter the code from Boosty's verification flow.
3. After successful token and profile validation, the app opens the TV feed.

If the code was accepted but a temporary network error prevents profile validation, **Повторить вход** retries that validation. You do not need to request another code for that retry. Candidate credentials remain in memory until validation succeeds or the sign-in attempt is discarded.

Use a phone number already linked to your Boosty account. For accounts created through Google, email, or another provider, Boosty provides a phone-linking option in its website profile settings. See [Boosty's phone-linking guide](https://boosty.to/boosty/posts/e2ca36ab-73ac-44b9-9890-660f9ebd7c3c).

Code delivery depends on the account and Boosty's current behavior. Check messages in the Boosty app or a linked Telegram bot as well as SMS. A visible cooldown prevents repeated sends, including after a request whose response was lost.

**Войти через сайт** is an optional fallback using BoostBuddy's embedded website flow. Normal phone-code sign-in uses native Compose controls and does not open a browser.

## Build from source

Requirements:

- JDK 17.
- Android SDK platform 35 and Build Tools 35.x, with accepted Android SDK licenses.
- Internet access for Gradle and Maven dependencies.

Set `JAVA_HOME` and `ANDROID_HOME` for your installations, or set `sdk.dir` in an untracked `local.properties`. From the repository root:

```sh
./gradlew :composeApp:assembleDebug :composeApp:testDebugUnitTest
```

On Windows, use `gradlew.bat`. The Gradle wrapper is pinned to **8.14.3** with a distribution SHA-256 checksum. The local APK output is:

```text
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Continuous integration

[`.github/workflows/build-tv.yml`](.github/workflows/build-tv.yml) runs on **every branch push, pull request, and manual dispatch**. Its build job uses a read-only repository token, JDK 17 and Android SDK 35, validates the Gradle wrapper, runs the credential-free Python publishing checks in `.github/tests/`, assembles the debug APK, and runs `:composeApp:testDebugUnitTest`.

A successful build/test step produces the APK artifact with its checksum, metadata, and installation notes. Test/build reports are uploaded when available, including after failures. Both Actions artifact types have **14-day retention**, subject to repository policy.

| Trigger | Build and test | Publish a prerelease |
| --- | --- | --- |
| Branch push | Yes | After the build succeeds. |
| Manual dispatch on a branch | Yes | After the build succeeds. |
| Pull request | Yes | No; artifacts and reports only, with no release write token. |

Publication runs in a **separate job with `contents: write`**, after the successful read-only build, and only for eligible branch refs. Branch-deletion events and manual runs on tags do not publish. The publisher creates the tag `ci-<GITHUB_RUN_ID>-<GITHUB_RUN_ATTEMPT>` through GitHub's refs API at the exact built commit, using the current publication attempt. An existing-tag collision fails instead of moving the tag or overwriting a release. Each workflow run has its own concurrency group with cancellation disabled, so separate pipelines are preserved even when they build the same commit. A rerun uses a new attempt tag.

The publisher downloads the successful build's artifact **by artifact ID** and uploads only the APK, `SHA256SUMS`, `build-info.json`, and `INSTALL.txt`. It keeps the release as a draft until all uploads finish and are verified, then publishes it with prerelease status and without changing the stable Latest marker. There is no automatic release cleanup and no Google Play upload.

A **publish-only rerun** reuses the original successful build artifact; it does not rebuild or re-sign the APK. `build-info.json` preserves the original build's `runAttempt`, which may differ from the publication attempt in the new release tag and release notes. This keeps the published files, checksum, and certificate tied to the build that produced them. If that Actions artifact has expired, a new full build is needed to publish again.

Check the run's build and publish jobs for their actual results. The local validation report does not establish that later pipelines or releases succeeded.

#### Publication troubleshooting

If the build succeeds but publication is denied, inspect the publisher log and repository workflow permissions. GitHub can require additional workflow permission to tag some older or non-default workflow commits. The publisher fails instead of silently targeting a different SHA; resolve permissions for the intended commit before retrying. The checked APK remains available as an Actions artifact while it is retained.

### Release signing

Use a persistent keystore that you own. Release builds use it only when all four variables below are supplied:

- `BOOSTBUDDY_TV_KEYSTORE_FILE`
- `BOOSTBUDDY_TV_STORE_PASSWORD`
- `BOOSTBUDDY_TV_KEY_ALIAS`
- `BOOSTBUDDY_TV_KEY_PASSWORD`

Then run:

```sh
./gradlew :composeApp:assembleRelease
```

Without those variables, the release artifact is unsigned. Keep the key stable for subsequent releases and keep private keys/passwords outside the repository. If you add a signed-release CI job, provide the signing material through your own secured CI configuration; the included debug workflow does not use it.

## Screenshots and the offline preview

The [homepage screenshots](README.MD#screenshots) are unedited captures from an Android TV 10 emulator. The login capture uses the normal activity with an empty phone-number field. The feed and subscriptions images use the real TV components with fictional local fixtures and display **DEMO DATA · OFFLINE**.

The fixture activity is included only in debug builds and has no launcher entry. It does not authenticate, load a real account, or play a video. [Screenshot provenance and reproduction instructions](docs/screenshots/README.md) describe how to launch it; release builds exclude it.

## Implementation map

Use [AGENTS.md](AGENTS.md) for shared project rules and [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for implementation workflows and examples. Both guides apply to human contributors and automated coding tools.

| Path | Responsibility |
| --- | --- |
| `composeApp/src/androidMain/kotlin/ru/slartus/boostbuddy/ui/tv/` | Native TV shell, grid, creator directory, focus, and saved browse state. |
| `composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/components/tv/TvVideoCards.kt` | Maps post/video metadata to stable video cards and calculates progress/duration. |
| `composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/ui/screens/AuthScreen.kt` | Native phone/code forms, TV focus behavior, and explicit web fallback. |
| `composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/components/auth/` | Authentication state, international phone validation, challenged-phone binding, and resend controls. |
| `composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/data/repositories/SubscribesRepository.kt` | Numeric-offset subscription pagination and deduplication. |
| `composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/components/feed/PostsFeedComponent.kt` | Generation-aware refresh, pagination, and playback eligibility checks. |
| `composeApp/src/androidMain/kotlin/ru/slartus/boostbuddy/ui/widgets/VideoPlayer.kt` | Existing Media3 player with consistent media request headers. |
| `composeApp/src/androidDebug/` and `composeApp/src/debug/` | Offline screenshot activity and its debug-only manifest entry. |
| `composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/data/ktor/SessionTokenManager.kt` | Shared refresh work with session/revision guards against logout and account-switch races. |
| `composeApp/src/commonTest/` | Authentication, session storage/refresh, feeds, subscriptions, playback, focus/search, and exception-handler regression tests. |
| `composeApp/src/androidUnitTest/` | Real loopback HTTPS certificate and hostname verification tests for the Android HTTP engine. |

## Privacy and update behavior

This fork removes the AppMetrica tracker and SDK dependency, disables verbose HTTP/media logging, sanitizes API error logging, and disables Android backup of application data. Access/refresh tokens and expiry use one app-private settings record, with migration from the previous separate fields. Shared refresh work cannot restore a signed-out session or overwrite a newer account. No remote credential service is introduced.

Debug and release HTTP clients use normal certificate and hostname checks. Website login cancels certificate errors, restricts auth-cookie capture to the official HTTPS website origins, and excludes JavaScript console output and token-bearing page-error URLs from logs. The API client sends stored Bearer credentials only to the exact HTTPS Boosty API origin and does not follow redirects.

The fork does not check for or offer upstream BoostBuddy APK updates. Install a newer fork APK explicitly. Existing user-selected log collection remains available, but the modified network/media logging paths do not deliberately record HTTP bodies, verification codes, or signed playback URLs.

## Validation and current limits

[VALIDATION_TV.md](VALIDATION_TV.md) records the fresh local APK, **84 passing automated tests**, emulator rendering, and D-pad navigation checks. [The code review](docs/CODE_REVIEW_TV.md) describes the issues corrected in `1.16.0-tv.2`; [AGENTS.md](AGENTS.md) records the implementation rules and verification workflow for future changes.

- Boosty's unofficial API can change. A paid video requires the appropriate account entitlement.
- The new grid targets native Boosty video attachments. Text/image posts do not become cards. External embedded players and a dedicated livestream directory are outside this first TV grid implementation.
- The existing player is connected, but does not replicate every SmartTube control or feature.
- Watch-progress indicators reflect currently loaded feed metadata. Refresh the feed to obtain newer server progress.
- The inherited iOS target and phone layout remain in the source; this fork has been built and checked for Android only.
- **Live sign-in, code delivery, token renewal, subscription responses, and playback have not been tested with a real Boosty account.** No physical TV or hardware decoding was tested. Offline screenshots do not establish those capabilities.

Suggested first account/device check: sign in, open a creator, play a video, seek and change quality, press Back, confirm focus returns to the same card, try search, then sign out. Where available, check a creator with text-only posts and an account with more than 30 subscriptions.

## Attribution

Original BoostBuddy copyright: **2024 Artem Slinkin**. The [upstream README and dependency credits](docs/UPSTREAM_README.md), [Apache 2.0 license](LICENSE), and [fork modification notice](TV_FORK_CHANGES.md) are preserved.
