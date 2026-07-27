#!/usr/bin/env bash
set -euo pipefail

version="${1:-local}"

bash ./scripts/preflight.sh

release_dir="release"
mkdir -p "$release_dir"

apk_source="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$apk_source" ]]; then
  echo "Debug APK was not produced at $apk_source" >&2
  exit 1
fi

safe_version="$(printf '%s' "$version" | sed 's/[^A-Za-z0-9._-]/-/g')"
apk_name="airchat-${safe_version}-debug-test.apk"
apk_target="${release_dir}/${apk_name}"
cp "$apk_source" "$apk_target"

hash="$(sha256sum "$apk_target" | awk '{print $1}')"
printf '%s  %s\n' "$hash" "$apk_name" > "${release_dir}/SHA256SUMS.txt"

cat > "${release_dir}/RELEASE_NOTES.md" <<EOF
# AirChat ${version}

This is an early Android debug test build for offline Wi-Fi mesh field testing.

## Verification

- Source commit: $(git rev-parse HEAD)
- APK SHA-256: ${hash}
- Build command: \`bash ./scripts/package-debug-release.sh ${version}\`
- Test gate: \`bash ./scripts/preflight.sh\`

## Field-Test Before Public Promotion

- Run the LAN, Wi-Fi Direct, DM, file transfer, diagnostics, and background mesh checks in \`docs/TEST_PLAN.md\`.
- Attach diagnostics text from both test devices.
- Attach the Markdown comparison from \`scripts/compare-diagnostics.ps1\`.
- Label the APK as a debug test build until release signing is configured.
EOF

ls -lah "$release_dir"
