# Maintaining the Fcitx17 fork

This repository is a personal distribution of Fcitx5 for Android. It tracks
[`Yizuka17/fcitx5-android-personal`](https://github.com/Yizuka17/fcitx5-android-personal)
as its immediate upstream and deliberately uses a different Android application
ID (`org.fcitx.fcitx17.android`).

## Remote layout

The clone uses the conventional remote names:

- `origin`: this fork, used for pushes.
- `upstream`: `Yizuka17/fcitx5-android-personal`, used to receive updates.
- `canonical`: the original `fcitx5-android/fcitx5-android` project, useful for
  inspecting where an upstream change originated.

Verify the layout with:

```shell
git remote -v
```

## Syncing from the personal upstream

Before making Fcitx17-specific changes, bring `master` forward with a
fast-forward merge. This avoids accidentally hiding the source project's work.

```shell
git fetch upstream master
git switch master
git merge --ff-only upstream/master
git push origin master
```

If the merge cannot fast-forward, stop and inspect the local commits before
rebasing or merging. Package-name, signing, and release changes should remain
small and reviewable.

## Producing a test build

The `Personal build` workflow runs on pushes to `master` and can also be started
manually from the Actions tab. It runs lint and unit tests, then retains both
installable debug APKs and unsigned release APKs as workflow artifacts for 14 days.

For a local build, first install the SDK, NDK, CMake, `extra-cmake-modules`, and
GNU gettext prerequisites listed in the root README. Then run:

```shell
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :assembleDebugPlugins :app:assembleRelease :assembleReleasePlugins
```

Installable debug APKs are placed under `app/build/outputs/apk/debug/`. Release
APKs under `app/build/outputs/apk/release/` are unsigned until a protected signing
key is configured. Do not publish a release build until it has been signed,
installed, and smoke-tested on a device.

## Release checklist

1. Sync from `upstream` and confirm the build succeeds.
2. Install the generated APK on a physical device and verify input, settings,
   clipboard, handwriting, and one-handed mode.
3. Record the source commit and user-facing changes in the GitHub release notes.
4. Use a protected signing key stored in GitHub Actions secrets before publishing
   a generally installable release.
