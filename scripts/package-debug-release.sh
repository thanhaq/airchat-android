#!/usr/bin/env bash
set -euo pipefail

version="${1:-local}"
flavor="fdroid"
variant="FdroidDebug"
variant_slug="fdroidDebug"

bash ./scripts/preflight.sh

release_dir="release"
mkdir -p "$release_dir"
find "$release_dir" -maxdepth 1 -type f \( \
  -name 'airchat-*.apk' -o \
  -name 'SHA256SUMS.txt' -o \
  -name 'RELEASE_NOTES.md' -o \
  -name 'RELEASE_MANIFEST.json' \
\) -delete

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

apk_source="app/build/outputs/apk/${flavor}/debug/app-${flavor}-debug.apk"
if [[ ! -f "$apk_source" ]]; then
  echo "Debug APK was not produced at $apk_source" >&2
  exit 1
fi

safe_version="$(printf '%s' "$version" | sed 's/[^A-Za-z0-9._-]/-/g')"
apk_name="airchat-${safe_version}-${flavor}-debug-test.apk"
apk_target="${release_dir}/${apk_name}"
cp "$apk_source" "$apk_target"

hash="$(sha256sum "$apk_target" | awk '{print $1}')"
source_commit="$(git rev-parse HEAD)"
apk_size="$(wc -c < "$apk_target" | tr -d ' ')"
generated_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
printf '%s  %s\n' "$hash" "$apk_name" > "${release_dir}/SHA256SUMS.txt"

cat > "${release_dir}/RELEASE_MANIFEST.json" <<EOF
{
  "schema": "dev.offlinemesh.airchat.release-manifest.v1",
  "app": "AirChat",
  "version": "$(json_escape "$version")",
  "variant": "debug-test",
  "distributionFlavor": "$(json_escape "$flavor")",
  "sourceCommit": "${source_commit}",
  "generatedAtUtc": "${generated_at}",
  "apk": {
    "file": "$(json_escape "$apk_name")",
    "sha256": "${hash}",
    "sizeBytes": ${apk_size},
    "signingCertificateSha256": null
  },
  "build": {
    "command": "bash ./scripts/package-debug-release.sh $(json_escape "$version")",
    "testGate": "bash ./scripts/preflight.sh",
    "gradleVariant": "$(json_escape "$variant_slug")",
    "compileSdk": 35,
    "minSdk": 26,
    "targetSdk": 35
  }
}
EOF

cat > "${release_dir}/RELEASE_NOTES.md" <<EOF
# AirChat ${version}

This is an early F-Droid-flavored Android debug test build for offline Wi-Fi mesh field testing.

## Verification

- Source commit: ${source_commit}
- Distribution flavor: ${flavor}
- Gradle variant: ${variant_slug}
- APK SHA-256: ${hash}
- Machine-readable manifest: \`RELEASE_MANIFEST.json\`
- Build command: \`bash ./scripts/package-debug-release.sh ${version}\`
- Test gate: \`bash ./scripts/preflight.sh\`

## Field-Test Before Public Promotion

- Run the LAN, Wi-Fi Direct, DM, file transfer, diagnostics, and background mesh checks in \`docs/TEST_PLAN.md\`.
- Attach diagnostics text from both test devices.
- Attach the Markdown comparison from \`scripts/compare-diagnostics.ps1\`.
- Label the APK as a debug test build until release signing is configured.
EOF

ls -lah "$release_dir"
