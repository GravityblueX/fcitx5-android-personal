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

fingerprint() {
  verification_details "$1" | awk '/Signer #1 certificate SHA-256 digest:/ { print $NF; exit }'
}

manifest() {
  "$aapt2" dump xmltree --file AndroidManifest.xml "$1"
}

host_fingerprint="$(fingerprint "$host_apk")"
host_package="$("$aapt2" dump packagename "$host_apk")"

if [[ -z "$host_fingerprint" ]]; then
  echo "Could not read a host signing certificate: $host_apk" >&2
  echo "apksigner: $apksigner" >&2
  verification_details "$host_apk" >&2 || true
  exit 1
fi

if [[ -z "$host_package" ]]; then
  echo "Could not read the host package name: $host_apk" >&2
  exit 1
fi

if [[ "$host_package" != *.debug ]]; then
  echo "The host artifact is not a debug APK: $host_package" >&2
  exit 1
fi

handwriting_seen=false
clipboard_filter_seen=false

for plugin_apk in "${plugin_apks[@]}"; do
  plugin_fingerprint="$(fingerprint "$plugin_apk")"
  if [[ "$plugin_fingerprint" != "$host_fingerprint" ]]; then
    echo "Plugin signing certificate differs from the debug host: $plugin_apk" >&2
    exit 1
  fi

  case "$(basename "$plugin_apk")" in
    *handwriting*)
      handwriting_seen=true
      plugin_manifest="$(manifest "$plugin_apk")"
      for expected in         "$host_package.permission.IPC"         "$host_package.permission.PLUGIN"         "$host_package.plugin.ACTIVATE"         "$host_package.plugin.SERVICE"; do
        if ! grep -Fq "$expected" <<<"$plugin_manifest"; then
          echo "Handwriting plugin is missing expected host integration: $expected" >&2
          exit 1
        fi
      done
      ;;
    *clipboard_filter*)
      clipboard_filter_seen=true
      plugin_manifest="$(manifest "$plugin_apk")"
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
