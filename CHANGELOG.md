# Changelog

All notable changes to CarPort are listed here. Versions follow
[semantic versioning](https://semver.org): MAJOR.MINOR.PATCH.

Each release's section becomes its GitHub Release notes, which CarPort shows in its
in-app update card.

## [0.7.2] - 2026-10-21

### Fixed

- **Previous / next buttons missing on the car screen when −10 s / +10 s are on.** The ±10 s
  buttons took the previous/next places. They now go in the secondary places (or the overflow
  menu, if the car has no room), and CarPort tells Android Auto to always keep space for previous
  and next, in both the Classic and Media3 engines.

## [0.7.1] - 2026-10-21

### Changed

- **"Test without a car" shows the −10 s / +10 s buttons in the car player row** (⏪ ⏮ ▶ ⏭ ⏩),
  read from CarPort's live media session, the same list Android Auto reads, so they appear
  there exactly when they appear in the car (option on and a seekable song playing).
- **Landscape view** in "Test without a car" (button in the top bar): rotates the phone while on
  that screen and lays the player out like Android Auto's wide screen, with large artwork left and
  details and controls right. The data and buttons are exactly what the car gets; the exact look
  is drawn by Android Auto itself and varies by car and Android Auto version.

## [0.7.0] - 2026-10-21

### Added

- **Open my music link when the car connects** (Settings › Car music, off by default): if there's
  no paused song to resume after connecting, CarPort opens your saved link in Samsung Browser.
- **"Music link" Quick Settings tile**: add it from the Quick Settings edit screen; one tap opens
  your music link (Samsung Browser first).
- **Recently played** (Settings › Car music): the last 30 songs played through car media
  controls, stored only on this phone, with a clear button.

### Fixed

- **Hold to skip 10 s with the Media3 engine**: Media3 gives apps only the first press of a key
  (no repeats, no release), so next/previous waited for a release that never came and did
  nothing. With Media3 they now act immediately; hold-to-skip works with the Classic engine (and
  with cars that send fast-forward/rewind keys in both). With Classic, a missing release still
  counts as a tap after 1.5 s.
- **Car controls briefly jumping to another app**: when Samsung Browser replaced its media
  session between songs, CarPort could follow another app's paused session (e.g. YouTube) for a
  moment. It now stays with the same app and only switches to an app that is actually playing.

## [0.6.0] - 2026-10-21

### Added

Both off by default, in Settings › Car music, and working in the Classic and Media3 engines:

- **−10 s / +10 s buttons** in the car player, shown when the playing song allows seeking.
- **Hold next/previous to skip 10 s** on the steering wheel: a tap still changes the song (on
  release), and holding jumps 10 s, repeating every half second while held. Works with cars
  that repeat the key while it's held or send fast-forward/rewind; the log shows what your car
  sends.
- "Test without a car" has **−10 s / +10 s** buttons and **Hold ⏮ / Hold ⏭**, which imitate
  holding a steering-wheel button.

## [0.5.0] - 2026-10-21

### Added

- **Media3 engine for car media controls** (Settings › Car music › Use Media3 engine, off by
  default). The same car controls built on Google's current Media3 library (1.11.1): artwork,
  title, progress bar, play/pause, next/previous, seeking, steering-wheel keys, the music link
  tile, resume/pause on connect and smart play all work through it.
- **Classic stays the default and the fallback.** If anything misbehaves with Media3 in your
  car, turn the switch off: CarPort switches back immediately, and Android Auto only ever sees
  one CarPort player. "Test without a car" uses whichever engine is selected.

## [0.4.1] - 2026-10-21

### Added

- **Expressive design** (Settings › CarPort, on by default): the parts of Material 3 Expressive
  that stable Material 3 offers, with rounder, larger shapes and bolder titles. Turn it off for
  the classic Material look. Expressive motion is still internal to the stable library, so it
  will come with a later Material 3 release.

## [0.4.0] - 2026-10-21

### Changed

- **Built with the latest stable Android tools and libraries:** Android Gradle Plugin 9.4.1,
  Gradle 9.8.2, Kotlin 2.4.21, Compose BOM 2026.09.00 (Material 3 1.4), Activity 1.13,
  Lifecycle 2.11, Core 1.19.1, Car App Library 1.7.0, apksig 9.4.1, Bouncy Castle 1.86,
  Coroutines 1.11.0, compiled against Android 17 (API 37).
- **Compatibility fallbacks:** CarPort still targets Android 15 (API 35) so Android's newer
  background-launch and install rules don't change its behaviour, and ARSCLib stays on 1.3.5
  because 1.4.0 renamed the manifest-editing API the patcher relies on.
- The APK is larger (about 38 MB instead of 29 MB) because of the newer libraries.

## [0.3.3] - 2026-10-20

### Changed

- **Friendlier Settings**, laid out like Android's own: short rows with an icon, a title and one
  line, switches you can toggle by tapping the whole row, status chips ("✓ On", "Allowed",
  "Set up"), and the longer explanations behind "Learn more" in each group.
- The music link is edited in its own dialog with validation and a one-tap YouTube Music default.
- The signing key fingerprint is hidden until you tap "Key fingerprint".

## [0.3.2] - 2026-10-20

### Changed

- **"Test without a car" looks like Android Auto's music player**: a car-display frame with
  Android Auto's app rail, blurred artwork background, large artwork, title, artist and app,
  a white progress bar with times, and the round play button between previous and next.
- **Settings are organised into groups**: Car music, Patching apps, CarPort (updates and
  signing key) and Help, with an update banner at the top when a new version is available.

## [0.3.1] - 2026-10-20

### Added

- "Test without a car" can now check the new features: **Car connects** and **Car disconnects**
  buttons run the real resume and pause logic (with your settings), and a button sends the same
  request as tapping the car's **"Open my music link"** tile.

## [0.3.0] - 2026-10-20

### Added

All off by default, in Settings › Car media controls and Smart play button:

- **Resume when the car connects**: a paused song (for example in Samsung Browser) starts
  playing a moment after the phone connects to Android Auto.
- **Pause when the car disconnects**: the music doesn't continue out loud from the phone
  speaker when you leave the car.
- **"Open my music link" tile** in CarPort's car browse screen, shown when a music link is
  saved: tap it to open the link in Samsung Browser.

## [0.2.7] - 2026-10-20

### Added

- **Progress bar in "Test without a car"**: shows the song's position and length exactly as
  Android Auto gets them, whether seeking is allowed, and lets you drag to seek through the same
  path as the car, so you can check the car's progress bar on the phone.

## [0.2.6] - 2026-10-20

### Changed

- The smart play button always tries **Samsung Browser first**. Another browser is used only if
  Samsung Browser is missing, disabled or can't open the link; the log says which one opened.

## [0.2.5] - 2026-10-20

### Added

- **Smart play button** (Settings, off by default). When you press play in the car (steering
  wheel, car screen or "Hey Google, play") and there's no song to resume, CarPort opens your
  saved music link in Samsung Browser instead of doing nothing. The link is editable, for
  example a YouTube Music playlist. Needs "Display over other apps"; a locked phone shows the
  page after unlocking, and the browser may need one tap to start.

## [0.2.4] - 2026-10-20

### Added

- **Test car media controls without a car** (Settings › Car media controls, or the setup guide).
  It shows what Android Auto would show for the music playing on your phone, with the car's
  screen buttons and simulated steering-wheel buttons, which take the same path as in the car,
  plus a live log of what happened.

## [0.2.3] - 2026-10-20

### Improved

- **Car media controls run only while the phone is connected to Android Auto**, and stop
  completely when it disconnects.
- Android Auto's player for CarPort now gets everything YT Music shows: the track's artwork in
  the browse list, title, artist and app name, list styling, a monochrome CarPort icon for the
  media bar, and "open on phone".

## [0.2.2] - 2026-10-20

### Improved

- **No need to open CarPort in the car.** Car media controls now run in the background
  whenever notification access is on. When music starts on the phone (for example in Samsung
  Browser), CarPort is already showing it as playing, so Android Auto can switch to it by
  itself and the steering-wheel buttons pause and change songs straight away.

## [0.2.1] - 2026-10-20

### Improved

- **Steering-wheel buttons** for car media controls: button presses are passed straight to the
  playing app (for example Samsung Browser), like a Bluetooth headset button, so next/previous
  work even when the app doesn't advertise them.
- Play/pause and next/previous always show in the car; when the app has no matching action,
  CarPort sends the media key instead.

## [0.2.0] - 2026-10-20

### Added

- **Car media controls.** CarPort appears in Android Auto as a media app showing whatever is
  playing on your phone (for example music in Samsung Browser), with artwork, play/pause,
  next/previous and seeking, even with the phone locked. Needs notification access once; the
  setup guide shows how.
- **Repair all** re-patches every patched app in place after Android or Android Auto updates.
- Option: **keep page open when switching screens** (off by default).
- Option: **show every launcher icon in the car** (off by default).
- The success message lists what was changed, and a **diagnostic log** can be shared from
  Settings or from any error message.

### Fixed

- **Patched apps now appear in Android Auto's app list.** They're declared in the game
  category, which Android Auto requires for listing sideloaded ordinary apps (as AA Browser
  does). Re-patch apps patched with an older CarPort.
- Apps with alternate icons get one car icon instead of one per icon (Firefox: 1 instead of 24).
- Turning a display option off and patching again now restores the app's original setting.

## [0.1.1] - 2026-10-19

### Fixed

- Large apps such as Samsung Browser and Firefox no longer fail with "The phone ran out of
  memory". CarPort now adds its one resource without loading the app's whole resource table,
  so patching Firefox needs under 96 MB instead of over 192 MB.
- CarPort may use a larger memory allowance on phones that offer one.

## [0.1.0] - 2026-10-19

First release.

- Patch any installed app (including split APKs) so Android Auto lists it.
- Patch an APK file from storage.
- Re-patch in place after Android updates, keeping the app's data.
- Restore original: remove the patched copy and reopen the app's store page.
- Display options: unlock orientation and make screens resizable.
- Signing key backup and import.
- Setup guide for Android Auto's Unknown sources setting.
- Self-update from GitHub Releases.
- Safe interruption handling: failed or cancelled installs keep the patched copy with Try again.
