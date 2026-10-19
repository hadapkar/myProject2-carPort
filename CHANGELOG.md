# Changelog

All notable changes to CarPort are listed here. Versions follow
[semantic versioning](https://semver.org): MAJOR.MINOR.PATCH.

Each release's section becomes its GitHub Release notes, which CarPort shows in its
in-app update card.

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
