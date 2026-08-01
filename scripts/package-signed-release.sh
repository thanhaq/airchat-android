#!/usr/bin/env bash
set -euo pipefail

version="${1:-local}"

require_env() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" ]]; then
    echo "${name} is required for signed release packaging." >&2
    exit 1
  fi
  printf '%s' "$value"
}

find_apksigner() {
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$sdk_root" || ! -d "$sdk_root/build-tools" ]]; then
    return 1
  fi

  find "$sdk_root/build-tools" -type f \( -name apksigner -o -name apksigner.bat \) | sort | tail -n 1
}

keystore="$(require_env AIRCHAT_KEYSTORE)"
keystore_password="$(require_env AIRCHAT_KEYSTORE_PASSWORD)"
key_alias="$(require_env AIRCHAT_KEY_ALIAS)"
export AIRCHAT_KEY_PASSWORD="${AIRCHAT_KEY_PASSWORD:-$keystore_password}"

if [[ ! -f "$keystore" ]]; then
  echo "AIRCHAT_KEYSTORE does not exist: $keystore" >&2
  exit 1
fi

bash ./scripts/preflight.sh
bash ./gradlew :app:assembleRelease

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

apk_source="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$apk_source" ]]; then
  echo "Signed release APK was not produced at $apk_source" >&2
  exit 1
fi

safe_version="$(printf '%s' "$version" | sed 's/[^A-Za-z0-9._-]/-/g')"
apk_name="airchat-${safe_version}-signed.apk"
apk_target="${release_dir}/${apk_name}"
cp "$apk_source" "$apk_target"

if command -v sha256sum >/dev/null 2>&1; then
  hash="$(sha256sum "$apk_target" | awk '{print $1}')"
else
  hash="$(shasum -a 256 "$apk_target" | awk '{print $1}')"
fi

if ! apksigner="$(find_apksigner)"; then
  echo "apksigner not found. Install Android SDK build tools before packaging a signed release." >&2
  exit 1
fi
cert_output="$("$apksigner" verify --print-certs "$apk_target" 2>&1)"
fingerprint="$(printf '%s\n' "$cert_output" | awk -F': ' '/Signer #1 certificate SHA-256 digest/ { print $2; exit }')"
if [[ -z "$fingerprint" ]]; then
  echo "apksigner did not print a signer SHA-256 digest for $apk_target" >&2
  exit 1
fi

source_commit="$(git rev-parse HEAD)"
apk_size="$(wc -c < "$apk_target" | tr -d ' ')"
generated_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
printf '%s  %s\n' "$hash" "$apk_name" > "${release_dir}/SHA256SUMS.txt"

cat > "${release_dir}/RELEASE_MANIFEST.json" <<EOF
{
  "schema": "dev.offlinemesh.airchat.release-manifest.v1",
  "app": "AirChat",
  "version": "$(json_escape "$version")",
  "variant": "signed-release",
  "sourceCommit": "${source_commit}",
  "generatedAtUtc": "${generated_at}",
  "apk": {
    "file": "$(json_escape "$apk_name")",
    "sha256": "${hash}",
    "sizeBytes": ${apk_size},
    "signingCertificateSha256": "$(json_escape "$fingerprint")"
  },
  "build": {
    "command": "bash ./scripts/package-signed-release.sh $(json_escape "$version")",
    "testGate": "bash ./scripts/preflight.sh",
    "compileSdk": 35,
    "minSdk": 26,
    "targetSdk": 35
  }
}
EOF

cat > "${release_dir}/RELEASE_NOTES.md" <<EOF
# AirChat ${version}

This is a signed Android APK for offline Wi-Fi mesh field testing and public GitHub release review.

## Verification

- Source commit: ${source_commit}
- APK SHA-256: ${hash}
- Signing certificate SHA-256: ${fingerprint}
- Machine-readable manifest: \`RELEASE_MANIFEST.json\`
- Build command: \`bash ./scripts/package-signed-release.sh ${version}\`
- Test gate: \`bash ./scripts/preflight.sh\`

## Field-Test Before Public Promotion

- Run the LAN, Wi-Fi Direct, DM, file transfer, diagnostics, and background mesh checks in \`docs/TEST_PLAN.md\`.
- Attach diagnostics text from both test devices.
- Attach the Markdown comparison from \`scripts/compare-diagnostics.ps1\`.
- Keep the release as a draft until the field-test report is complete.
EOF

ls -lah "$release_dir"
