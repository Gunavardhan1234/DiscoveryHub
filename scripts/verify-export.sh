#!/usr/bin/env bash
set -u

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "Usage: $0 <package.zip>" >&2
  exit 2
fi

package_zip=$1
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

if ! unzip -q "$package_zip" -d "$tmp_dir"; then
  echo "FAIL package: unable to unzip $package_zip"
  exit 1
fi

manifest="$tmp_dir/manifest.json"
if [[ ! -f "$manifest" ]]; then
  echo "FAIL package: manifest.json is missing"
  exit 1
fi

failed=0
canonical="$tmp_dir/canonical-items"
: > "$canonical"

while IFS=$'\t' read -r path expected_size expected_hash; do
  [[ -z "$path" ]] && continue
  if [[ "$path" == /* || "$path" == .. || "$path" == ../* || "$path" == */../* ]]; then
    printf 'FAIL %-60s unsafe path\n' "$path"
    failed=1
    continue
  fi
  item="$tmp_dir/$path"
  if [[ ! -f "$item" ]]; then
    printf 'FAIL %-60s missing\n' "$path"
    failed=1
    continue
  fi
  actual_size=$(stat -c '%s' "$item")
  actual_hash=$(sha256sum "$item" | awk '{print $1}')
  printf '%s\n%s\n%s\n' "$path" "$actual_size" "$actual_hash" >> "$canonical"
  if [[ "$actual_size" == "$expected_size" && "$actual_hash" == "$expected_hash" ]]; then
    printf 'PASS %-60s size=%s sha256=%s\n' "$path" "$actual_size" "$actual_hash"
  else
    printf 'FAIL %-60s expected(size=%s sha256=%s) actual(size=%s sha256=%s)\n' \
      "$path" "$expected_size" "$expected_hash" "$actual_size" "$actual_hash"
    failed=1
  fi
done < <(jq -r '.items | sort_by(.path)[] | [.path, (.size | tostring), .sha256] | @tsv' "$manifest")

actual_package_hash=$(sha256sum "$canonical" | awk '{print $1}')
expected_package_hash=$(jq -r '(.package // .packageInfo).packageChecksum // empty' "$manifest")
if [[ -n "$expected_package_hash" && "$actual_package_hash" == "$expected_package_hash" ]]; then
  printf 'PASS package checksum: %s\n' "$actual_package_hash"
else
  printf 'FAIL package checksum: expected=%s actual=%s\n' "$expected_package_hash" "$actual_package_hash"
  failed=1
fi

if (( failed )); then
  exit 1
fi
exit 0
