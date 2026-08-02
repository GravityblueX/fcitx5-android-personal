# Contributing to Fcitx17

Thanks for helping improve this personal Fcitx5 for Android build.

## Before opening an issue

- Search existing issues first.
- Use the installed app's **About** page to include the exact Fcitx17 version or source commit.
- For input problems, include the active input method, Android version, device model, and clear reproduction steps.
- Never attach private text, passwords, or unredacted clipboard contents.

## Before opening a pull request

1. Sync your branch with the current `master` branch.
2. Keep each pull request focused on one user-visible fix or improvement.
3. Add or update a unit test when changing pure Kotlin logic.
4. Run the relevant Gradle task locally when possible. The **Personal build** workflow runs `:app:testReleaseUnitTest` and builds the release APKs.
5. Explain how you tested the change and call out device-specific limitations.

## Upstream-friendly changes

Fcitx17 tracks [the personal upstream](https://github.com/Yizuka17/fcitx5-android-personal). Avoid mixing application identity, signing, or Fcitx17-only UX changes with changes that could be contributed upstream. See [docs/MAINTENANCE.md](docs/MAINTENANCE.md) for the remote layout and sync procedure.

## License

By contributing, you agree that your contribution is licensed under the repository's existing LGPL-2.1-or-later terms.
