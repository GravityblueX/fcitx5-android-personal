#!/usr/bin/env bash
set -euo pipefail

output_file="${1:-SHA256SUMS.txt}"
mapfile -d '' apk_files < <(find app/build/outputs/apk/release plugin -type f -name "*.apk" -path "*/build/outputs/apk/release/*" -print0 | sort -z)

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

: > "$output_file"
for apk in "${apk_files[@]}"; do
  printf '%s  %s\n' "$(sha256sum "$apk" | cut -d ' ' -f 1)" "$(basename "$apk")" >> "$output_file"
done
