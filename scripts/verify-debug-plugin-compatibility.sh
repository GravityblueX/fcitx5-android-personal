#!/usr/bin/env bash
set -euo pipefail

if (( $# < 2 )); then
  echo "Usage: $0 <debug-app-apk> <debug-plugin-apk>..." >&2
  exit 64
fi

host_apk=$1
shift
plugin_apks=("$@")
android_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [[ -z "$android_home" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT must be set." >&2
  exit 1
fi

apksigner="$(find "$android_home/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
aapt2="$(find "$android_home/build-tools" -type f -name aapt2 | sort -V | tail -n 1)"

if [[ -z "$apksigner" || -z "$aapt2" ]]; then
  echo "Could not locate apksigner and aapt2 in $android_home/build-tools." >&2
  exit 1
fi

verification_details() {
  "$apksigner" verify --verbose --print-certs "$1" 2>&1
}

fingerprints() {
  awk '/certificate SHA-256 digest:/ { print $NF }' |
    LC_ALL=C sort -u
}

manifest() {
  "$aapt2" dump xmltree --file AndroidManifest.xml "$1"
}

if ! host_verification="$(verification_details "$host_apk")"; then
  echo "Could not verify the host APK: $host_apk" >&2
  echo "apksigner: $apksigner" >&2
  printf '%s\n' "$host_verification" >&2
  exit 1
fi
host_fingerprints="$(fingerprints <<<"$host_verification")"
if ! host_package="$("$aapt2" dump packagename "$host_apk" 2>&1)"; then
  echo "Could not read the host package name: $host_apk" >&2
  echo "aapt2: $aapt2" >&2
  printf '%s\n' "$host_package" >&2
  exit 1
fi

if [[ -z "$host_fingerprints" ]]; then
  echo "Could not read a host signing certificate: $host_apk" >&2
  echo "apksigner: $apksigner" >&2
  printf '%s\n' "$host_verification" >&2
  exit 1
fi

if [[ -z "$host_package" ]]; then
  echo "Could not read the host package name: $host_apk" >&2
  echo "aapt2: $aapt2" >&2
  exit 1
fi

if [[ "$host_package" != *.debug ]]; then
  echo "The host artifact is not a debug APK: $host_package" >&2
  exit 1
fi

handwriting_seen=false
clipboard_filter_seen=false

for plugin_apk in "${plugin_apks[@]}"; do
  if ! plugin_verification="$(verification_details "$plugin_apk")"; then
    echo "Could not verify plugin APK: $plugin_apk" >&2
    printf '%s\n' "$plugin_verification" >&2
    exit 1
  fi
  plugin_fingerprints="$(fingerprints <<<"$plugin_verification")"
  if [[ -z "$plugin_fingerprints" ]]; then
    echo "Could not read a plugin signing certificate: $plugin_apk" >&2
    printf '%s\n' "$plugin_verification" >&2
    exit 1
  fi
  if [[ "$plugin_fingerprints" != "$host_fingerprints" ]]; then
    echo "Plugin signing certificate set differs from the debug host: $plugin_apk" >&2
    exit 1
  fi

  if ! plugin_manifest="$(manifest "$plugin_apk" 2>&1)"; then
    echo "Could not read plugin manifest: $plugin_apk" >&2
    echo "aapt2: $aapt2" >&2
    printf '%s\n' "$plugin_manifest" >&2
    exit 1
  fi
  if [[ -z "$plugin_manifest" ]]; then
    echo "Could not read plugin manifest: $plugin_apk" >&2
    echo "aapt2: $aapt2" >&2
    exit 1
  fi
  manifest_action="$host_package.plugin.MANIFEST"
  if ! grep -Fq "$manifest_action" <<<"$plugin_manifest"; then
    echo "Plugin is missing the host-specific discovery action: $manifest_action" >&2
    exit 1
  fi

  case "$(basename "$plugin_apk")" in
    *handwriting*)
      handwriting_seen=true
      for expected in         "$host_package.permission.IPC"         "$host_package.permission.PLUGIN"         "$host_package.plugin.ACTIVATE"         "$host_package.plugin.SERVICE"; do
        if ! grep -Fq "$expected" <<<"$plugin_manifest"; then
          echo "Handwriting plugin is missing expected host integration: $expected" >&2
          exit 1
        fi
      done
      ;;
    *clipboard_filter*)
      clipboard_filter_seen=true
      for expected in         "$host_package.permission.IPC"         "$host_package.permission.PLUGIN"         "$host_package.plugin.SERVICE"; do
        if ! grep -Fq "$expected" <<<"$plugin_manifest"; then
          echo "Clipboard filter plugin is missing expected host integration: $expected" >&2
          exit 1
        fi
      done
      ;;
  esac
done

if [[ "$handwriting_seen" != true || "$clipboard_filter_seen" != true ]]; then
  echo "Expected debug handwriting and clipboard filter plugins were not built." >&2
  exit 1
fi

echo "Verified debug host/plugin signing and service integration for $host_package."
