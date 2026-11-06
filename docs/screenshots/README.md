# Screenshot provenance

These are unedited Android screenshots captured with `adb exec-out screencap -p`
on an Android TV emulator: Android 10 / API 29, 1280 × 720 pixels, 213 dpi.

| File | What it shows |
| --- | --- |
| `tv-login.png` | The normal `TvAppActivity` phone sign-in screen, with an empty phone-number field and the keyboard dismissed. |
| `tv-feed-demo.png` | The real TV sidebar and video-grid composables, populated with fictional local demo videos. |
| `tv-subscriptions-demo.png` | The real subscriptions-directory composable, populated with fictional local demo creators. |

The browsing captures display **DEMO DATA · OFFLINE** in the image itself.
Creator names, thumbnails, avatars, durations, dates, and progress values come
from fixed fixtures. The geometric artwork is generated locally by the fixture
code; it does not use actual creator images. These captures demonstrate rendered
UI and remote navigation. They do not verify account sign-in, subscription
entitlements, or live video playback.

The emulator run also checked D-pad movement from the first video card to the
second, return to the sidebar, and navigation into Subscriptions. Android's UI
hierarchy confirmed the changed focus positions and the first creator's focus.

## Reproducing the captures

Build and install the debug APK on a running Android TV emulator:

```sh
./gradlew :composeApp:assembleDebug
adb -e install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

For the normal sign-in screen:

```sh
adb -e shell am start -W -n ru.slartus.boostbuddy.tvfork/ru.slartus.boostbuddy.TvAppActivity
```

Wait for the form to finish drawing. If the keyboard is open, dismiss it with
Back. Leave the phone field empty, then capture the screen:

```sh
adb -e exec-out screencap -p > docs/screenshots/tv-login.png
```

For the offline feed preview:

```sh
adb -e shell am force-stop ru.slartus.boostbuddy.tvfork
adb -e shell am start -W -n ru.slartus.boostbuddy.tvfork/ru.slartus.boostbuddy.ui.tv.ScreenshotActivity --es screen feed
```

Wait for the screen and its local artwork to finish drawing. Choose **Лента**
(Feed) in the sidebar to place focus in the grid, then capture it:

```sh
adb -e exec-out screencap -p > docs/screenshots/tv-feed-demo.png
```

Use Left to return to the sidebar, then Down and Center to choose **Подписки**
(Subscriptions), or start that preview directly:

```sh
adb -e shell am force-stop ru.slartus.boostbuddy.tvfork
adb -e shell am start -W -n ru.slartus.boostbuddy.tvfork/ru.slartus.boostbuddy.ui.tv.ScreenshotActivity --es screen subscriptions
```

Wait for rendering, then capture it:

```sh
adb -e exec-out screencap -p > docs/screenshots/tv-subscriptions-demo.png
```

With software CPU emulation,
the first frame can arrive after `am start -W` has timed out. A startup timeout
alone is therefore not a screenshot-readiness check.

## Debug-only implementation

`ScreenshotActivity.kt` lives in `composeApp/src/androidDebug`. Its manifest entry
lives in `composeApp/src/debug` and has no launcher intent filter. Both are
excluded from release builds; the normal app launcher is unchanged.

The activity directly reuses `TvBrowseScreen`, `TvVideoBrowseContent`,
`TvCreatorDirectory`, and `videoCardsFromPosts`. It does not create the live
navigation controller or authenticate an account. Refresh and playback actions
are inert in this preview. Additional starting screens are `creator` and `search`.
