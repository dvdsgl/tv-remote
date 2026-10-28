# Maintainer notes

The app is called "Remote" (launcher label, and the name the TV lists under Remotes); the
repo and package stay `tv-remote` / `xyz.dvdsgl.tvremote`. Test fixtures keep "TV Remote".

- This repo is public. Notes about a particular home network (TV names, addresses) go in
  the untracked `AGENTS.local.md`, never here.
- HomePods advertise Companion too, but can't be paired; discovery drops them (only
  `rpMd` models starting with "AppleTV" are kept).
- Look: black background, pad `#1C1C1E`, buttons `#2C2C2E`, secondary text `#8E8E93`,
  accent `#0A84FF`, Inter. No emoji.

## Direct Companion client (since 2026-10-27)

The app talks to the TVs itself. An earlier version went through a pyatv bridge on a
desktop machine; that was retired on 2026-10-28.

- `companion/` is plain Kotlin/JVM (no Android classes), ported from pyatv 0.18.0, so it
  runs in JVM tests:
  - `Opack.kt`, `Tlv8.kt`, `Bplist.kt` (keyboard RTI payloads, NSKeyedArchiver reads).
  - `Srp.kt` mirrors srptools 1.0.1 byte for byte (k and u hash padded values, all other
    numbers hash their minimal bytes). `Pairing.kt` has HAP pair-setup (M1 to M6) and
    pair-verify. Unlike pyatv, M4's server proof is checked.
  - `Connection.kt`: frames (type, 3-byte length), ChaCha20-Poly1305 after pair-verify
    (12-byte LE counter nonce, header as AAD), "_x" request ids.
  - `AppleTv.kt`: pyatv's connect sequence (_systemInfo, _touchStart, _sessionStart,
    TVRCSessionStart, _tiStart, _interest), keys, touch, power, keyboard, events.
- Crypto: SHA-512, HMAC (HKDF) and ChaCha20-Poly1305 come from the platform (Conscrypt
  names the cipher "ChaCha20/Poly1305/NoPadding", the JDK "ChaCha20-Poly1305"; `Crypto`
  tries both). X25519 and Ed25519 are in `Curve25519.kt` (RFC 7748 and 8032 reference
  algorithms on BigInteger), because Android 13 doesn't document raw-key import for XDH
  or Ed25519 signing.
- `Remote.kt` replaced `Bridge.kt` but keeps the old bridge's JSON ops (devices, select,
  key, touch, power, text, pair_begin, pair_pin) and callbacks, so `MainActivity` barely
  changed. All TV work runs on one worker thread. `Discovery.kt` uses NsdManager
  (`registerServiceInfoCallback` on API 34+, `resolveService` one at a time on 33).
- Storage: `tvs` SharedPreferences: `tvs` (last known name, model, host, port per TV, so
  a sleeping TV that misses mDNS can still be reached), `cred:<id>` (pyatv-format
  credentials `ltpk:ltsk:atv_id:client_id`), `rpId` and `deviceId` (our identity in
  `_systemInfo`). A TV's id is its `rpMRtID` TXT value, else its service name.
- Volume keys go to the TV from the activity and from bottom sheets (`volumeKey()`). The
  power/side key can't be taken over: Android never delivers it to apps.
- Key mapping: `home_hold` is a double press of TV (app switcher), `top_menu` holds Back,
  `control_center` is HID PageDown, skips are `_mcc` SkipBy +-10 s. Power on/off are a
  lone Wake/Sleep button-up, as pyatv does it.
- Touch coordinates are 0..1000 on both axes. Modes: 1 press, 3 hold/move, 4 release.
  The app sends moves at most every 16 ms and treats a quick tap with under 10dp of
  movement as select. Touches are dropped (and a reconnect queued) while disconnected.
- Pairing is a full-screen code pad in `MainActivity.pair()` (no system keyboard): four
  slots, a 3x4 numpad, inline status. It sends `pair_begin` when it opens and `pair_pin`
  on the fourth digit; a wrong code shakes the slots and sends `pair_begin` again after
  1.2 s (the TV needs a pause). Below 760dp of height (an unfolded foldable in landscape)
  it drops the TV icon and uses 68dp keys.
- After a dropped connection the app tries once to reconnect; otherwise the next button
  press reconnects.
- targetSdk is 36. At 37, Android's local network protection should require
  `ACCESS_LOCAL_NETWORK` for LAN sockets and NSD; check before bumping.

## Tests

- `tools/golden.py` runs pyatv 0.18.0 with fixed keys and writes
  `app/src/test/resources/companion_golden.json`; `GoldenTest` checks OPACK, TLV8, HKDF,
  ChaCha20-Poly1305, X25519, Ed25519, SRP, pair-setup, pair-verify and the RTI plists
  against it byte for byte. Regenerate with the `uv run` line at the top of the script.
- `FrameTest` checks encrypted frames on a socket; `CurveCrossCheckTest` checks the
  curves against the JDK's.
- `tools/e2e.sh` starts pyatv's fake Companion Apple TV (`tools/fake_tv.py`, from a
  pyatv v0.18.0 checkout) and runs `CompanionE2ETest` against it (skipped otherwise).
- pyatv 0.18.0 needs srptools 1.0.1: srptools 2.0 made `private` keyword-only and pairing
  fails. Pinned wherever pyatv runs (the test scripts).

## Not done yet

Now-playing info (needs AirPlay pairing too), app launcher, mute.

## Light, dark and the upgrade

Remote is free with no ads and starts in a light theme. Dark mode is a one-time $2.99
upgrade: the top bar's left button shows the current mode (a sun in light, a moon in dark).
On the free version, tapping the sun previews dark mode (crossfade) with the upgrade as a
bottom card; dismissing it fades back to light, buying keeps it and shows a thank-you.
Themes switch in place: `buildUi()` rebuilds the screen and `applyTheme()` crossfades from a
snapshot of the old one (the activity handles `uiMode` itself, so it isn't recreated).

- Two flavors (`store` dimension): `oss` is the GitHub build (dark mode free, no
  dependencies; what the release workflow publishes) and `play` is the Google Play build,
  with Play Billing (`com.android.billingclient:billing`) and the in-app product `dark_mode`.
  Both provide `Upgrade` (`src/oss`, `src/play`) with the same API.
- Ownership is cached in the `remote` prefs (`owned`) and refreshed from Play on every
  start; a refund turns dark mode off. The theme lives in `C` (light and dark values) and
  in `TouchSurface.dark`; switching saves `dark` (only for owners) and rebuilds the UI.
- The play build can't buy anything until it's installed from Play with `dark_mode` set
  up in the Play Console; sideloaded, the card says purchases aren't available.

## Design

The screens are designed in Paper ("TV Remote — Landing page" file: App — Remote, Pairing,
Choose TV, Keyboard, Remote (unfolded), plus the landing page). Code follows them:

- Always dark: `Theme.Remote` (black system bars, light icons, no contrast scrims) and
  `setApplicationNightMode(MODE_NIGHT_YES)`, so Samsung's gesture bar and taskbar strip stay
  dark when the phone is in light mode. Gboard still follows the system theme.
- Phone: top bar (64dp: TV name + chevron, centered on the screen's midline with a spacer
  balancing the chevron; the title box has equal 52dp side margins; a status line under it only while
  connecting or unpaired; power at right as a plain white 26dp icon in a 44dp target, no background), touch
  surface (radius 32, 20dp side margins), then one row of four
  68dp buttons spread across the width: back, TV, play/pause, Siri. There is no keyboard
  button; the keyboard sheet opens when the TV focuses a text field. Siri is hold to talk
  (HID 10 down on press, up on release). pyatv sends no microphone audio, so whether the
  TV hears anything depends on tvOS; verify on a real TV.
- Unfolded (width >= 600dp): the same 420dp column floats on one side. Tapping the empty
  side moves it there (animated, saved as `side` in the `remote` prefs); the column is
  clickable so taps on its own gaps don't count. Power sits on the column's outer edge.
  The pairing screen follows the same side.
- Pairing: "Enter code" header, 56x68 slots, and a compact numpad block: 78dp keys, 28dp
  column and 16dp row gaps (68/22/10 below 760dp of height). Keep the keys fixed-size;
  stretching columns to the width is what made the old pad look scattered.
- Sheets (`bottomSheet()`), #1C1C1E: the TV picker floats as a card (10dp from the edges,
  above the navigation bar, all corners 28dp); the keyboard sheet is attached (top corners
  only, flush on the IME).
- No icon splash at launch: `values-v31/themes.xml` sets a transparent splash icon on the
  theme's background (`@color/splash`, white or black by night mode).
- Launcher icon: the app in miniature. An adaptive icon whose background is the remote's
  dark body (so the launcher's mask is the remote's outline); the foreground is the tall
  rounded-rectangle touch surface and the row of four buttons, kept inside the 66dp safe
  zone (`mipmap-anydpi-v26/ic_launcher.xml`). Tones are a step lighter than the app's so
  the icon shows on dark launchers. `docs/icon.svg` is the same design, face scaled 1.28x.
- Quick Settings tile: `RemoteTile` (TileService) with a monochrome `ic_tile` (touch
  surface and four buttons; the system tints it). Its subtitle is the current TV's name;
  tapping it opens the app (`startActivityAndCollapse`, PendingIntent on API 34+). After
  the first successful connection the app asks once to add it (`requestAddTileService`,
  `tileOffered` pref).
- Models show as "Apple TV 4K" / "Apple TV HD" (`modelName()`), from the `rpMd` TXT value.

## Releases

`.github/workflows/build.yml`: every push to `main` that touches `android/**` runs the unit
tests, builds a release APK and publishes GitHub release `v1.0.<run number>` with
`Remote.apk` (versionCode = run number, so it always rises). No tags by hand. Pull requests
only build and test. The site's download button is
`releases/latest/download/Remote.apk`.

- Signing: the release key lives on home in `~/.config/remote-release/` (`release.jks`,
  `passwords.env`, 600) and in the repo's Actions secrets (`RELEASE_KEYSTORE_BASE64`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`);
  `set-github-secrets.sh` there sets them. Losing the key means users can't update
  without uninstalling. Without the secrets the workflow builds but doesn't publish.
- `app/build.gradle.kts` reads `VERSION_CODE`, `VERSION_NAME` and the `RELEASE_*` variables
  through `providers.environmentVariable`; without a keystore, release builds use the
  debug key.
- Debug builds installed by hand (adb) are signed with the debug key, so switching to a
  release APK means uninstalling first (and pairing again).

## Website

GitHub Pages caches assets, so `index.html` refers to `demo.css`, `icon.svg` and `icon.png`
with a content hash (`?v=`). Run `tools/bust-cache.sh` before every commit that touches
`docs/`.

`docs/` is the GitHub Pages site (served from `main`, folder `/docs`): one static
`index.html` in a light, Apple-style layout (hero animation, h1, Download and GitHub, then install), no build step.

The hero is an HTML/CSS animation, not a video (`docs/demo.css` + the `.demo` markup in
`index.html`), storyboarded in Paper as the "Hero anim" frames (Android-style launch: the app window
grows out of the icon or Quick Settings tile as a clip-path rounded rect, straight into the remote; no icon splash): a light Pixel-style home screen (so the dark Remote icon stands out), tap Remote;
the app opens from the icon; choose a TV; type 3815; then swipe right, left and tap; back home, pull down Quick Settings, tap the
Remote tile and swipe again. It's
laid out in a 402x770 space (the Paper frames, 104 shorter) and scaled to `--w`; every animation shares one
20 s duration; the keyframes are generated from a timeline in seconds by
`tools/hero_keyframes.py` (paste its output into `demo.css`). Reduced
motion shows the remote still. The hero fills one screen: portrait stacks phone, headline and Download;
landscape puts the phone beside the copy. The phone's scale is `min(--sh, --sw)`, set by
media-query steps that `tools/hero_sizes.py` writes into `index.html` (height left after
the copy, and a share of the width), so there's no length-by-length `calc()` division,
which Firefox can't do. A round down-arrow Download button is pinned top right in
portrait only. To check frames, pause it at a negative animation-delay;
that can show white corners that real playback doesn't (a compositing artifact). The
screenshots in `docs/` are still used by the README and the social card.
Keep the copy plain: no marketing claims beyond the h1, no nav bar. The site only ever
shows the app in dark mode (never mention the light theme there). Install is the APK
download; the coding-agent prompt lives only in the README and `docs/llms.txt`. Share metadata (Open Graph and
Twitter card) points at `docs/og.png`, a 2400x1260 card rendered from `tools/og.html`, plus
`og:video` -> `docs/og.mp4`, the same card with the hero animation playing (20 s, 1200x630,
H.264). Rebuild it with `node tools/og-video.mjs` after changing the animation; it takes the
phone markup from `index.html` and steps each frame by pausing at a negative
animation-delay. X ignores og:video (it would need an approved Player card) and shows
og.png; Facebook, LinkedIn and others can play the video.

Screenshots come from the Android emulator, never from the owner's phone:

1. Debug builds have a demo mode: `adb shell am start -S -n xyz.dvdsgl.tvremote/.MainActivity
   --es demo remote|pairing|picker|keyboard|offline` shows that screen with sample data and no
   network (ignored in release builds).
2. AVDs `tvr_pixel_9` and `tvr_pixel_9_pro_fold` (android-36 google_apis x86_64). Run them
   headless (`emulator -avd ... -no-window -gpu swiftshader_indirect`), turn on dark mode
   (`cmd uimode night yes`) and the SystemUI demo status bar (clock 0941, `network -e wifi show -e level 4 -e fully true -e mobile hide`; without `fully true` the Wi-Fi icon shows a no-internet mark), and
   `adb emu unfold` the foldable. On the foldable, `screencap -d <inner display id>`.
3. Save raw captures as `docs/screenshots/<name>.png` (`fold-*.png` for the foldable) and
   run `tools/frame.sh`: it draws the device frames (tall phone, foldable)
   with headless Chromium and renders `docs/og.png` and `docs/icon.png`.
