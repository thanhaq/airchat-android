#!/usr/bin/env bash
set -euo pipefail

tasks=(":app:testDebugUnitTest" ":app:assembleDebug")
if [[ "${1:-}" != "--skip-lint" ]]; then
  tasks+=(":app:lintDebug")
fi

bash ./gradlew "${tasks[@]}"

apk="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$apk" ]]; then
  echo "Debug APK was not produced at $apk" >&2
  exit 1
fi

if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$apk"
else
  sha256sum "$apk"
fi
