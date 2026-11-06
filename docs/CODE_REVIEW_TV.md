# BoostBuddy TV code review — 1.16.0-tv.2

Review date: **2026-11-06**. Baseline: commit `b73674c913cc71fc2db5db499263adb45afeed0f` (`1.16.0-tv.1`).

This review follows the Android TV path from native login through subscriptions, feed/search, video selection, playback, and logout. It also checks the inherited networking and website-login code used by the installable debug APKs. The changes preserve the existing TV layout and Boosty endpoints.

## Findings and corrections

### High: HTTPS verification was disabled

The inherited Android HTTP engine selected a trust-all certificate manager and hostname verifier when debugging/logging was enabled. The embedded website login also proceeded past certificate errors, and its Android 7 compatibility client had its own trust-all configuration. Those paths handle real credentials even in debug builds.

All three now use normal certificate and hostname verification. The website cancels a failed TLS connection and shows an error. Browser auth cookies are accepted only from the official HTTPS website origins. Login-page JavaScript console messages and token-bearing error URLs are no longer copied into application logs. The compatibility fetch is limited to main-frame GET requests so a login POST cannot be accidentally replayed as a GET.

Regression coverage uses a real local HTTPS server: untrusted certificates must fail in debug and release configurations; a trusted certificate with the wrong hostname must fail; a trusted matching certificate must succeed. No Boosty account or live endpoint is used by those tests. Origin tests cover lookalike hosts, URL credentials, HTTP, and alternate ports.

### High: old refreshes could overwrite logout or a newer login

Access token, refresh token, and expiry were stored separately. Cancellation between writes could leave mixed credentials. A delayed refresh could restore a signed-out session, and simultaneous rejected requests could each rotate the refresh token. Screens also treated every access-token rotation as an account change, potentially cancelling the request that triggered renewal.

Credentials now form one stored record, migrated from existing settings. One shared settings repository publishes an atomic session snapshot. Every update changes its revision; login/logout changes its session identity. Refresh commits compare the original revision and cannot overwrite a later login or logout. Concurrent requests share a refresh, and cancelling one caller does not abandon an already rotating refresh token. Content loaders observe account/session changes rather than ordinary token rotation. API requests cannot forward the stored Bearer token to another origin or follow redirects with credentials.

Tests exercise logout and account replacement during refresh, concurrent 401 responses, cancelled callers, storage migration and corruption, cancellation during credential persistence, and actual client retry/redirect behavior with a mock HTTP engine.

### High: a transient profile failure could consume the login code without completing sign-in

The original flow published candidate credentials before profile validation, then discarded them if profile loading failed. A successful code confirmation followed by a temporary network failure left the user trying to reuse a consumed code.

Candidate credentials are now validated by an isolated request to the existing current-user endpoint before becoming the app's session. A temporary validation failure offers **Повторить вход** and retries validation using the in-memory candidate; it does not send or confirm another code. Rejected credentials are discarded. Component regression tests cover the consumed-code retry and cancelled/late validation cases.

### Medium: feed updates and session changes could produce stale data or pagination

Polling replaced a post while discarding the feed's next-page cursor. A failed refresh/search could leave the previous query's pagination state. Some creator/stream requests continued through logout and could repopulate old data. Subscription rows were not consistently cleared on logout.

Post updates preserve paging metadata. New refreshes invalidate old requests and reset paging state. Results are accepted only for their original active session and request generation. Logout/account replacement clears data and cancels feed, creator, and subscription work. Regression tests exercise poll replacement, cancelled and delayed requests, failed refreshes, pagination, and token rotation without restarting the feed.

### Medium: playback resume and recovery could use stale state

The progress manager read the replay cache of a flow configured with no replay, so it had no final position to save. It also treated a failed repository Result as a successful save. Initial playback refreshed post metadata but retained the old signed URL; recovery did not consistently enforce fresh access or stream identity.

Progress writes are serialized and conflated, failed saves remain retryable, and a bounded final save captures the latest position before leaving. A final seek to zero is supported. Initial and recovered playback use a freshly resolved usable URL and current access information; recovery cannot silently switch to another livestream. The Android player snapshots and releases resources directly in the stop lifecycle callback, without requiring background UI recomposition, then restores position and paused/playing intent on return. Stale listeners and seek jobs cannot access a released player.

Tests cover final position saves, failed-save retry, forced zero, scope cancellation, stale pending positions, and denied/missing/different-stream playback resolution. These tests do not establish live Boosty playback or hardware decoder behavior.

### Medium: TV focus and search state could move unexpectedly

Async loading could steal focus from the search field or header. The sidebar had no content target while loading, and replacing a paging footer could lose remote focus. Re-entering Search could overwrite an unsubmitted draft or focus old results instead of the query field.

Focus requests now retain their intended owner, loading/error content remains reachable, and grid/footer transitions restore an appropriate target. Automatic initial sidebar focus no longer cancels content/bookmark restoration; explicit remote input still takes priority. Search drafts and explicit search-focus requests survive ordinary route changes. Account-specific browse bookmarks are cleared when entering the login route. Focus/search helper tests cover these transitions; emulator navigation checks are recorded in the validation report.

### Medium: lifecycle and root navigation retained stale handlers/screens

Global error handlers used epoch seconds as IDs, so rapid registrations overwrote one another. Android activities never unregistered their handlers and could retain destroyed roots. Logout also left the independent dialog slot and saved TV browse state intact.

Handlers now have unique IDs, thread-safe snapshot dispatch, and activity-bound cleanup. UI error routing runs in the root's scope. Root navigation observes the session directly, starts at login when signed out, dismisses stale dialogs, and avoids recreating an already displayed login component. Delayed playback navigation is discarded after a route or session change. The website view and its scanning work are disposed when leaving it.

Regression tests cover rapid handler registration, removal, dispatch order, and removal during dispatch. Cold-start and D-pad checks use the Android TV emulator.

## Verification and limits

The [validation report](../VALIDATION_TV.md) records the clean build, automated-test totals, emulator checks, and exact local APK checksum. CI runs the same unit-test task before publishing an APK.

Live account sign-in, delivery of verification codes, live Boosty subscriptions/entitlements, video playback, and hardware decoding still require an account/device smoke test. The inherited iOS target is outside this Android TV review. CI builds still use ephemeral debug signing keys; persistent release signing is documented in [README_TV.md](../README_TV.md).

## Implementation references

- Session storage and refresh: `SettingsRepository.kt`, `SessionTokenManager.kt`, `HttpClient.kt`.
- Login: `AuthComponent.kt`, `ProfileRepository.kt`, `AuthScreen.kt`.
- TLS and website fallback: Android `HttpEngineFactory.kt`, `WebView.kt`, `BoostyWebOrigin.kt`.
- Data and playback: `PostsFeedComponent.kt`, `SubscribesComponent.kt`, `BlogComponent.kt`, `VideoComponent.kt`, `TimeCodeManager.kt`, `PlayerUrlSelection.kt`, Android `VideoPlayer.kt`.
- TV navigation: `ui/tv/`, `RootComponent.kt`, `App.android.kt`, `GlobalExceptionHandlersChain.kt`.

Relevant platform guidance: [Android WebView SSL error handling](https://developer.android.com/reference/android/webkit/WebViewClient#onReceivedSslError(android.webkit.WebView,%20android.webkit.SslErrorHandler,%20android.net.http.SslError)) and [coroutine cancellation handling](https://developer.android.com/kotlin/coroutines/coroutines-best-practices#watch-out-for-exceptions).
