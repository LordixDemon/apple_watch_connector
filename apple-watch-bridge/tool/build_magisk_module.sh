#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
template_dir="$script_dir/magisk_module"
apk_path="${1:-$project_dir/app/build/outputs/apk/release/app-release.apk}"
output_path="${2:-$project_dir/build/apple-watch-bridge-magisk.zip}"

if [[ ! -f "$apk_path" ]]; then
  echo "APK not found: $apk_path" >&2
  exit 1
fi
if ! command -v zip >/dev/null 2>&1; then
  echo "zip is required" >&2
  exit 1
fi

work_dir="$(mktemp -d /tmp/apple-watch-bridge-magisk.XXXXXX)"
module_dir="$work_dir/module"
cleanup() {
  if [[ "$work_dir" == /tmp/apple-watch-bridge-magisk.* ]]; then
    rm -rf "$work_dir"
  fi
}
trap cleanup EXIT

cp -R "$template_dir" "$module_dir"
mkdir -p "$module_dir/system/priv-app/AppleWatchBridge"
cp "$apk_path" \
  "$module_dir/system/priv-app/AppleWatchBridge/AppleWatchBridge.apk"
mkdir -p "$(dirname "$output_path")"
(
  cd "$module_dir"
  zip -qr "$output_path" .
)

echo "Built local-only Magisk artifact: $output_path"
echo "No ADB, USB, phone, or Magisk command was executed."
