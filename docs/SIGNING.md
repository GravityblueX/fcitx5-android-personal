# Release signing

Fcitx17 release APKs are signed with a stable project key so Android can install
new builds as upgrades instead of requiring an uninstall.

## CI configuration

The **Personal build** workflow reads these GitHub Actions secrets:

- `SIGN_KEY_BASE64`: Base64-encoded PKCS12 or Java keystore file.
- `SIGN_KEY_PWD`: Keystore and key password.
- `SIGN_KEY_ALIAS`: Signing key alias.

The build convention applies this key to every release app and plugin. They must
share the same certificate because service plugins use signature-level
permissions. The host also verifies the package-name pattern and matching signing
certificate before reading plugin assets or adding native libraries to Fcitx's
search path. Legacy discovery actions remain available for upgrades from older
Fcitx17 plugin APKs, but they do not bypass the signature check.

## Key handling

- Never commit the keystore, password, or Base64 value to this repository.
- Keep an encrypted offline backup in a password manager or another controlled
  location. Losing the key prevents upgrade-compatible releases under the same
  Android package name.
- Do not reuse the release key for the debug variant.
- Before publishing, verify a release APK with `apksigner verify --verbose --print-certs` and compare the certificate fingerprint against the maintained backup record.

## Rotation

A replacement key creates a different Android signing identity. Users must
uninstall the existing Fcitx17 release before installing an APK signed with a
rotated key, so key rotation should be reserved for compromise recovery.

## Certificate fingerprint

The current Fcitx17 release certificate SHA-256 fingerprint is:

```
17:F1:52:BA:04:A3:02:0A:4D:CD:54:63:63:D5:19:5B:7D:86:65:C9:6D:54:D9:D1:B5:90:7B:E5:6A:83:CA:52
```

This fingerprint identifies the public certificate only; it does not expose the
private signing key.
