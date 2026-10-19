# CarPort

Run your phone apps on your car's Android Auto screen. No root, no PC.

CarPort makes a car-ready copy of an app you already have installed: it adds the declarations
Android Auto looks for, re-signs the app with a key that lives on your phone, and installs it.
Everything happens on the device.

> **Parked or passenger use only.** Never watch or operate projected apps while driving.

## Features

- **Patch any installed app**, including apps installed in several parts (split APKs).
- **Patch an APK file** from storage.
- **Re-patch in place** after an Android update, keeping the app's data.
- **Restore original:** remove the patched copy and reopen the app's store page.
- **Display options:** unlock portrait-only apps and make screens resizable for the car display.
- **Signing key backup and import**, so patched apps stay updatable after reinstalling CarPort or switching phones.
- **Setup guide** for the one-time Android Auto settings.
- **Self-update** from this repository's GitHub Releases.
- Material You design that follows your wallpaper colours, light and dark.

## Requirements

- Android 15 or later
- Android Auto with **Unknown sources** turned on (the in-app setup guide shows how)

## Install

1. Download `CarPort-<version>.apk` from [Releases](../../releases/latest) and open it on your phone.
2. Open CarPort and follow **Set up CarPort**.
3. Tap an app › **Patch & install**, then connect to your car.

The first patch of an app uninstalls the original, because the patched copy has a different
signature. That erases the app's data on the phone. Re-patching later keeps it.

## Limits

- Apps with DRM, Play Integrity, or signature/tamper checks (most banking and streaming apps, most Google apps) may refuse to run after patching.
- The Play Store can't update a patched copy. To update: **Restore original**, update it, patch again.
- System apps can't be patched.
- Don't share patched APKs. They're for your own phone, and redistributing them can break the original developers' terms.

## How it works

The patcher (`patcher/`, plain Kotlin, unit-tested on the JVM) mirrors the manifest entries used
by the open-source [AA Browser](https://github.com/kododake/AABrowser):

- the `android.intent.category.CAR_LAUNCHER` category (plus the car-app navigation categories) on every launcher intent filter
- `com.google.android.gms.car.application` meta-data pointing at a new `res/xml/carport_automotive_app_desc.xml` (`<automotiveApp><uses name="media"/></automotiveApp>`)
- `distractionOptimized`, `android.max_aspect`, and optionally `resizeableActivity=true` and no `screenOrientation` lock on activities

It then strips the old signatures, aligns native libraries to 16 KB, and signs every APK with
APK Signature Scheme v2 + v3. Libraries used: [ARSCLib](https://github.com/REAndroid/ARSCLib),
[apksig](https://android.googlesource.com/platform/tools/apksig/), and Bouncy Castle.

## Development

```sh
./gradlew assembleRelease :app:testReleaseUnitTest :app:lintRelease
./gradlew :patcher:test -PtestApk=/path/to/any.apk   # patch a real APK and verify it
```

CI (`.github/workflows/build.yml`) runs all of the above on every push. It also patches
CarPort's own APK and checks the result with `apksigner`, `zipalign -P 16`, and `aapt2`.

### Signing CarPort itself

CarPort's own release key must never change. An update signed with a different key can only be
installed after uninstalling CarPort, and that deletes the key CarPort signs patched apps with.

- Local builds read `keystore/keystore.properties` (git-ignored). Back up the `keystore/` folder.
- CI reads two repository secrets:
  - `CARPORT_KEYSTORE_BASE64`: `base64 -w0 keystore/carport-release.p12`
  - `CARPORT_KEYSTORE_PASSWORD`: the `storePassword` from `keystore/keystore.properties`

### Releasing an update

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit, then tag and push: `git tag v<versionName> && git push origin v<versionName>`.
3. CI publishes the release, and installed copies of CarPort offer the update.

The update repository defaults to `hadapkar/myProject2-carPort`. Override it with
`-Pcarport.updateRepo=owner/name`. It must be public, because the app checks releases without logging in.
