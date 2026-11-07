# Working on BoostBuddy TV

This is the shared repository guide for human contributors and automated coding tools. It applies to the whole repository and is the canonical source of project rules. The [development guide](docs/DEVELOPMENT.md) supplements it with implementation workflows and source examples.

BoostBuddy TV is an unofficial Android TV / Google TV fork of BoostBuddy, implemented with Kotlin Multiplatform, Compose, Decompose, Ktor, Kotlinx Serialization, Kodein, and Android Media3. The Android application ID is `ru.slartus.boostbuddy.tvfork`; the Kotlin package remains `ru.slartus.boostbuddy`.

## Start here

- Read [README_TV.md](README_TV.md) for installation, build, signing, and implementation details.
- Read [docs/CODE_REVIEW_TV.md](docs/CODE_REVIEW_TV.md) before changing authentication, networking, playback, or TV navigation. It explains regressions that the current design prevents.
- Use [VALIDATION_TV.md](VALIDATION_TV.md) for recorded evidence and limitations, not as proof that a later change passed.
- Read [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) when adding screens, navigation, or API integrations. Use the current `BaseComponent`, `Inject`, and `SessionTokenManager` APIs described there.
- Inspect `git status` and the relevant diff before editing. Preserve unrelated work and coordinate file ownership when multiple agents share a checkout. Prefer `rg` for searches.
- Follow the user's requested scope and existing authorization. Do not introduce another confirmation step for actions already authorized in the conversation. Commit, push, or publish when that work is within the authorized task; report the actual result.

## Repository map

Shared Kotlin paths below are relative to `composeApp/src/commonMain/kotlin/ru/slartus/boostbuddy/`. Android Kotlin paths are relative to `composeApp/src/androidMain/kotlin/ru/slartus/boostbuddy/`.

| Location | Responsibility |
| --- | --- |
| Shared `components/BaseComponent.kt`, `components/RootComponent.kt` | Component lifecycle, state/actions, root stack and dialog navigation, session routing. |
| Shared `components/auth/`, `ui/screens/AuthScreen.kt` | Native phone/code login, candidate validation, retry, web fallback, TV field focus. |
| Shared `data/api/`, `data/repositories/` | Existing Boosty endpoints, response models, repository mapping, pagination, and settings. |
| Shared `data/repositories/SettingsRepository.kt` | Singleton session storage, migration, atomic snapshots, session identity and revision checks. |
| Shared `data/ktor/HttpClient.kt`, `data/ktor/SessionTokenManager.kt` | Configured clients, origin restrictions, shared token refresh and retry behavior. |
| Shared `components/feed/`, `components/blog/`, `components/subscribes/` | Feed/search generations, paging, creator/subscription data, session-bound loading. |
| Shared `components/tv/` | Pure TV card mapping, stable identities, eligibility, and browse/focus decisions. |
| Shared `components/video/` | Fresh URL resolution, access checks, stream identity, progress saves, playback lifecycle policy. |
| Shared `data/Inject.kt`, `utils/PlatformDataConfiguration.kt` | Kodein access and dependency bindings. |
| Android `App.android.kt`, `ui/tv/` | TV entry point, sidebar, grid, creator directory, search, remote focus and browse bookmarks. |
| Android `ui/widgets/VideoPlayer.kt`, `ui/widgets/VideoPlayerControls.kt` | Media3 resources, lifecycle handling, media headers and remote controls. |
| Android `ui/widgets/WebView.kt`, `data/ktor/HttpEngineFactory.kt` | Website fallback, cookie-origin filtering, WebView cleanup, Android TLS behavior. |
| `composeApp/src/commonTest/` | Shared logic, coroutine/component and mock HTTP regression tests. |
| `composeApp/src/androidUnitTest/` | Android engine tests, including loopback HTTPS trust/hostname verification. |
| `.github/tests/` | Credential-free tests of artifact validation and prerelease publishing failure paths. |
| `composeApp/src/androidDebug/`, `composeApp/src/debug/` | Debug-only offline screenshot activity and manifest entry. |
| `composeApp/build.gradle.kts`, `gradle/libs.versions.toml`, `.github/workflows/build-tv.yml` | Android identity/signing, dependency versions, APK/test CI gate, artifacts, and prerelease publishing. |

The inherited iOS target remains in the tree. Keep shared code portable and preserve `expect`/`actual` contracts; Android verification does not establish an iOS build or runtime result.

## Build and test

Use **JDK 17**, **Android SDK platform 35**, **Build Tools 35.0.0**, and the checked-in **Gradle 8.14.3 wrapper**. Set `JAVA_HOME` and `ANDROID_HOME` for the installed toolchains, or use an untracked `local.properties` for `sdk.dir`. Android SDK licenses must be accepted. Use `gradlew.bat` on Windows.

If the SDK packages are missing:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Run commands from the repository root. Start with the tests relevant to the change; for example:

```sh
./gradlew :composeApp:testDebugUnitTest \
  --tests 'ru.slartus.boostbuddy.components.auth.AuthFlowTest' \
  --tests 'ru.slartus.boostbuddy.data.ktor.SessionTokenManagerTest'
```

For code or build changes, the required final Android gate is the same APK-and-test task combination used by CI:

```sh
python3 -m unittest discover -s .github/tests -p 'test_*.py' -v
./gradlew :composeApp:assembleDebug :composeApp:testDebugUnitTest \
  --no-daemon --stacktrace --console=plain
git diff --check
```

- APK: `composeApp/build/outputs/apk/debug/composeApp-debug.apk`.
- Test XML: `composeApp/build/test-results/testDebugUnitTest/`.
- Test HTML: `composeApp/build/reports/tests/testDebugUnitTest/`.
- Read the generated reports for counts and failures; do not copy totals from an earlier validation document.
- Run one Gradle build at a time per checkout. Do not clean outputs while another agent is building. Use an isolated checkout/output location when necessary.
- A stale-output startup crash has occurred before. If artifacts and source disagree, rebuild from a fresh source tree or use `clean` with `--no-build-cache --rerun-tasks`, then test the newly built APK. Do not distribute the earlier artifact.
- Do not hand-edit generated code or `build/` outputs. Change wrapper, dependency, or signing infrastructure only when relevant to the task.
- For documentation-only edits, inspect links, commands and `git diff --check`; a new app build is not needed solely for prose changes.

Review substantive changes before delivery, using available peer review where practical. Report what changed, why, which checks actually ran, and any remaining limits. Do not describe a build, CI job, or device check as passed until its result is observed.

## Architecture and coroutine rules

- Keep business logic and asynchronous work in Decompose components/repositories. Compose screens render immutable state, dispatch user events, and consume one-time actions. Follow the current component's API rather than copying outdated sample helpers.
- Use Material 3 and shared Compose resources for new UI. Keep Android APIs in `androidMain`; do not import Android or UIKit APIs into `commonMain`.
- Reuse configured clients and repositories through Kodein. Do not create ad hoc HTTP clients in screens/components or replace the session manager with a second competing auth mechanism.
- Rethrow `CancellationException`. Scope ordinary screen work to its component, reject late results using both the request generation and session identity where relevant, and clear account data on logout/replacement.
- Shared refresh work intentionally outlives an individual waiting request. Keep that exception tied to the HTTP client's lifetime. Restrict non-cancellable work to short credential transactions and the bounded final playback save.
- Preserve independent navigation slots: logout/auth routing must dismiss stale dialogs as well as change the stack. Activity error handlers must have unique IDs and be unregistered on destruction; route UI changes on the root's scope.

## Authentication and transport invariants

- Native phone login is the default. Keep the country code editable and separate from the national number. `+381` and `+7` are shortcuts, not implicit numbering assumptions; do not convert arbitrary ten-digit numbers to Russian numbers or strip national zeros globally.
- Bind confirmation/resend to the phone number that received the challenge. Prevent duplicate submissions while loading and retain the resend cooldown after an ambiguous timeout. Code delivery can use Boosty messages, a linked Telegram bot, or SMS; do not promise SMS-only delivery.
- Keep the explicit website fallback. Validate candidate credentials using the isolated current-user request before publishing a signed-in session. A transient profile failure must retry the private candidate without resending or reconfirming a consumed code. Discard candidates when their attempt is abandoned or rejected.
- `SettingsRepository` must remain a singleton. Access token, refresh token, and expiry belong to one stored record. Use the session APIs, including `clearSession()`, rather than restoring independent token writes.
- `sessionId` changes for login/logout/account replacement; `revision` changes on every credential update. Loaders observe account/session changes, not routine access-token rotation. Refresh/login commits must not overwrite a newer session.
- Coalesce refreshes, preserve rotated refresh credentials when a caller is canceled, and do not replay an old account's request with a replacement account's token. Never hold the settings lock across network I/O.
- Preserve normal certificate and hostname verification in **debug and release** builds. WebView SSL errors must cancel. Do not add trust-all clients, permissive hostname verifiers, or SSL-error `proceed()` calls to resolve connectivity problems.
- Stored Bearer credentials go only to the exact HTTPS Boosty API origin. Keep credential-bearing clients from following redirects. Website auth-cookie capture must pass the official HTTPS website-origin check. Preserve WebView disposal and its scope-bound scanning work.
- Never log tokens, cookies, verification codes, auth response bodies, or signed media URLs. Use safe status/category messages and keep credential models' diagnostic output redacted. Do not re-enable verbose HTTP or website-console logging in debug builds.
- The Boosty API is unofficial. Reuse verified existing endpoints and response semantics; do not invent endpoints, pagination rules, or entitlement bypasses.

## Browsing, playback, and remote-control invariants

- Subscription loading follows the server's numeric-offset semantics, deduplicates creators, and terminates if paging makes no progress. Feed cursors belong to a particular query/filter generation; post replacement must preserve valid paging metadata.
- Old refresh/search/pagination responses cannot repopulate a newer route or account. A text-only page may still have another page of videos. Keep loading, error and retry states reachable with the remote.
- Cards retain stable video identities and distinguish usable, locked and processing content. Opening or recovering playback must resolve fresh media/access data; a stale signed URL or unknown access state is not proof of entitlement. Livestream recovery must preserve stream identity.
- Use consistent browser User-Agent headers for HLS, DASH and direct media requests. Progress saves must remain serialized, handle failed `Result` values, capture the final position including a seek to zero, and avoid sending an older queued position after the final save.
- Snapshot and release the Android player directly on lifecycle stop; do not rely on a later Compose recomposition to release it. Restore position and paused/playing intent on return. Cancel stale listeners/seek work before it can touch a released player.
- Preserve D-pad Left/Right/Back behavior between sidebar and content, visible focus, and per-route card/scroll restoration. Async data or disappearing footers must not steal focus from search, the header, or another explicit focus owner.
- Keep search drafts across ordinary route changes, honor explicit search-field focus, and clear account-specific browse bookmarks when entering login. In native text fields, remote arrows navigate controls with the keyboard closed and retain normal editing behavior with the keyboard open.
- Check changed TV layouts at realistic density, including roughly **960 × 540 dp**. All primary actions must remain visible or scrollable, focusable, and usable without a touchscreen.

## Validation boundaries

Automated tests use fake credentials, synthetic responses, or a loopback HTTPS server. Do not make routine tests depend on Boosty, a paid subscription, verification-code delivery, or a real user's token. Live account/device checks belong only to an authorized task that includes them; existing authorization does not need to be requested again.

For UI work, use the normal TV activity for native login and the debug-only fixture for offline browsing. See [docs/screenshots/README.md](docs/screenshots/README.md) for reproduction commands. Keep the visible **DEMO DATA · OFFLINE** label and exclude the fixture from release builds. Fixture playback and refresh are inert.

Separate evidence clearly: source review, mock/loopback tests, emulator rendering/navigation, live authentication/playback, and physical-device decoding prove different things. Offline screenshots do not establish login, paid access, token renewal, or real video playback. If a live check was not run, say so. Do not claim iOS verification from Android checks.

## CI, signing, and attribution

- `.github/workflows/build-tv.yml` builds and tests on branch pushes, pull requests, and manual dispatch with a read-only repository token. A separate `contents: write` job publishes a prerelease after a successful branch-push or manual run on a branch; branch deletions and tag refs do not publish. Pull requests retain APK/report artifacts and must never receive the release job's write token.
- Preserve one release per eligible run/publication attempt, tagged `ci-<GITHUB_RUN_ID>-<GITHUB_RUN_ATTEMPT>` at the exact built commit through GitHub's refs API. Keep concurrency unique to the workflow run with cancellation disabled so pipelines for the same commit do not cancel each other. Permission failures and tag collisions must fail without retargeting another SHA, moving a tag, or overwriting a release.
- Keep a release in draft until its four assets—the APK, `SHA256SUMS`, `build-info.json`, and `INSTALL.txt`—are uploaded and verified. Publish it as a prerelease without changing the stable Latest marker. Download the successful build artifact by ID; a publish-only rerun must reuse its files and retain the original `runId`, `runNumber`, and build `runAttempt` metadata rather than rebuild or re-sign them. Release notes identify the separate publication attempt.
- The public Releases page is the primary APK download path. Actions artifacts/reports retain their 14-day lifetime; release assets are outside that artifact expiry and have no automatic cleanup. Use the build metadata and checksum to identify the exact APK, and verify the actual publish job/release before reporting success. A local pass does not imply a later CI pass.
- Prereleases contain debug APKs with ephemeral build-runner keys. Publishing them does not increment the Android version or create a Play Store release. An APK from another build may have a different certificate and require uninstalling the previous build, which removes its local app data. Do not promise in-place updates between arbitrary CI builds.
- Stable releases use an owner-controlled persistent key through the `BOOSTBUDDY_TV_KEYSTORE_FILE`, `BOOSTBUDDY_TV_STORE_PASSWORD`, `BOOSTBUDDY_TV_KEY_ALIAS`, and `BOOSTBUDDY_TV_KEY_PASSWORD` environment variables. Without the full signing configuration, release output is unsigned. Never commit keys, passwords, tokens, or local signing/SDK configuration.
- Preserve the separate fork application identity, disabled upstream APK-update behavior, disabled Android app-data backup, and removal of the upstream analytics SDK.
- Preserve [LICENSE](LICENSE), upstream copyright/credits in [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md), and the modification record in [TV_FORK_CHANGES.md](TV_FORK_CHANGES.md). The project is Apache 2.0; the TV layout's inspiration does not authorize importing incompatibly licensed source or branding.
