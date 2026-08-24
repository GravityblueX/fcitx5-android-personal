#!/usr/bin/env bash
set -euo pipefail

output_file="${1:-SHA256SUMS.txt}"
apk_list="$(mktemp)"
trap 'rm -f -- "$apk_list"' EXIT
if ! find app/build/outputs/apk/release plugin -type f -name "*.apk" -path "*/build/outputs/apk/release/*" -print0 | sort -z > "$apk_list"; then
  echo "Could not discover release APKs" >&2
  exit 1
fi
mapfile -d '' apk_files < "$apk_list"

if [ "${#apk_files[@]}" -eq 0 ]; then
  echo "No release APKs found" >&2
  exit 1
fi

duplicate_names="$(for apk in "${apk_files[@]}"; do basename "$apk"; done | sort | uniq -d)"
if [ -n "$duplicate_names" ]; then
  echo "Duplicate APK asset names:" >&2
  echo "$duplicate_names" >&2
  exit 1
fi

checksum_lines=()
for apk in "${apk_files[@]}"; do
  if ! checksum_output="$(sha256sum -- "$apk")"; then
    echo "Could not calculate SHA-256 checksum: $apk" >&2
    exit 1
  fi
  checksum="${checksum_output%% *}"
  if [[ ! "$checksum" =~ ^[[:xdigit:]]{64}$ ]]; then
    echo "sha256sum returned an invalid digest for: $apk" >&2
    exit 1
  fi
  checksum_lines+=("$checksum  $(basename -- "$apk")")
done
printf '%s\n' "${checksum_lines[@]}" > "$output_file"
