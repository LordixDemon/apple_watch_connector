#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
apk_path="${1:-$project_dir/app/build/outputs/apk/release/app-release.apk}"
output_path="${2:-$project_dir/build/apple-watch-bridge-magisk.zip}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required" >&2
  exit 1
fi

exec python3 "$project_dir/../tools/build_magisk_module.py" "$apk_path" "$output_path"
