#!/usr/bin/env bash
set -euo pipefail

variant="FdroidDebug"
apk="app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk"
tasks=(":app:test${variant}UnitTest" ":app:assemble${variant}")
if [[ "${1:-}" != "--skip-lint" ]]; then
  tasks+=(":app:lint${variant}")
fi

bash ./gradlew "${tasks[@]}"

if [[ ! -f "$apk" ]]; then
  echo "Debug APK was not produced at $apk" >&2
  exit 1
fi

if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$apk"
else
  sha256sum "$apk"
fi
