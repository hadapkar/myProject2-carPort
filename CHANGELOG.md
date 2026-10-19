# Changelog

All notable changes to CarPort are listed here. Versions follow
[semantic versioning](https://semver.org): MAJOR.MINOR.PATCH.

Each release's section becomes its GitHub Release notes, which CarPort shows in its
in-app update card.

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
