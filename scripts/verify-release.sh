#!/usr/bin/env bash
set -euo pipefail

if [[ -d /usr/bin ]]; then
  PATH="/usr/bin:$PATH"
fi

manifest_path="release/RELEASE_MANIFEST.json"
skip_certificate="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-certificate)
      skip_certificate="true"
      shift
      ;;
    *)
      manifest_path="$1"
      shift
      ;;
  esac
done

fail() {
  echo "verify-release: $*" >&2
  exit 1
}

normalize_hex() {
  printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]' | tr -cd '0-9a-f'
}

strip_cr() {
  printf '%s' "${1:-}" | tr -d '\r'
}

find_apksigner() {
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$sdk_root" || ! -d "$sdk_root/build-tools" ]]; then
    return 1
  fi

  find "$sdk_root/build-tools" -type f \( -name apksigner -o -name apksigner.bat \) | sort | tail -n 1
}

if [[ ! -f "$manifest_path" ]]; then
  fail "release manifest not found: $manifest_path"
fi

python_bin="${PYTHON:-}"
if [[ -z "$python_bin" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    python_bin="python3"
  elif command -v python >/dev/null 2>&1; then
    python_bin="python"
  else
    fail "python3 or python is required to parse RELEASE_MANIFEST.json"
  fi
fi

manifest_fields_file="$(mktemp)"
trap 'rm -f "$manifest_fields_file"' EXIT

"$python_bin" - "$manifest_path" > "$manifest_fields_file" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
apk = manifest.get("apk") or {}

fields = [
    manifest.get("schema"),
    manifest.get("version"),
    manifest.get("variant"),
    apk.get("file"),
    apk.get("sha256"),
    apk.get("sizeBytes"),
    apk.get("signingCertificateSha256"),
    manifest.get("distributionFlavor"),
    (manifest.get("build") or {}).get("gradleVariant"),
]
for field in fields:
    print("" if field is None else str(field))
PY

schema="$(strip_cr "$(sed -n '1p' "$manifest_fields_file")")"
version="$(strip_cr "$(sed -n '2p' "$manifest_fields_file")")"
variant="$(strip_cr "$(sed -n '3p' "$manifest_fields_file")")"
apk_file="$(strip_cr "$(sed -n '4p' "$manifest_fields_file")")"
manifest_hash="$(normalize_hex "$(sed -n '5p' "$manifest_fields_file")")"
manifest_size="$(strip_cr "$(sed -n '6p' "$manifest_fields_file")")"
manifest_fingerprint="$(normalize_hex "$(sed -n '7p' "$manifest_fields_file")")"
distribution_flavor="$(strip_cr "$(sed -n '8p' "$manifest_fields_file")")"
gradle_variant="$(strip_cr "$(sed -n '9p' "$manifest_fields_file")")"

[[ "$schema" == "dev.offlinemesh.airchat.release-manifest.v1" ]] || fail "unsupported release manifest schema: $schema"
case "$variant" in
  debug-test|signed-release) ;;
  *) fail "unsupported release variant: $variant" ;;
esac
if [[ -n "$distribution_flavor" ]]; then
  [[ "$distribution_flavor" == "fdroid" ]] || fail "unsupported distribution flavor: $distribution_flavor"
  expected_gradle_variant="fdroidRelease"
  if [[ "$variant" == "debug-test" ]]; then
    expected_gradle_variant="fdroidDebug"
  fi
  [[ "$gradle_variant" == "$expected_gradle_variant" ]] || fail "Gradle variant mismatch. Manifest=$gradle_variant, expected=$expected_gradle_variant"
fi
[[ -n "$apk_file" ]] || fail "manifest is missing apk.file"
[[ ${#manifest_hash} -eq 64 ]] || fail "manifest apk.sha256 is missing or invalid"
[[ "$manifest_size" =~ ^[0-9]+$ ]] || fail "manifest apk.sizeBytes is missing or invalid"

release_dir="$(cd "$(dirname "$manifest_path")" && pwd)"
apk_path="$release_dir/$apk_file"
[[ -f "$apk_path" ]] || fail "APK listed in manifest was not found: $apk_path"

actual_size="$(wc -c < "$apk_path" | tr -d ' ')"
[[ "$actual_size" == "$manifest_size" ]] || fail "APK size mismatch. Manifest=$manifest_size, actual=$actual_size"

if command -v sha256sum >/dev/null 2>&1; then
  actual_hash="$(sha256sum "$apk_path" | awk '{print tolower($1)}')"
else
  actual_hash="$(shasum -a 256 "$apk_path" | awk '{print tolower($1)}')"
fi
[[ "$actual_hash" == "$manifest_hash" ]] || fail "APK SHA-256 mismatch. Manifest=$manifest_hash, actual=$actual_hash"

sha_file="$release_dir/SHA256SUMS.txt"
[[ -f "$sha_file" ]] || fail "SHA256SUMS.txt not found beside manifest"
sha_file_hash="$(awk -v f="$apk_file" '$2 == f || $2 == "*" f { print tolower($1); exit }' "$sha_file")"
[[ -n "$sha_file_hash" ]] || fail "SHA256SUMS.txt does not contain an entry for $apk_file"
[[ "$sha_file_hash" == "$actual_hash" ]] || fail "SHA256SUMS.txt hash mismatch. SHA256SUMS=$sha_file_hash, actual=$actual_hash"

if [[ "$variant" == "signed-release" ]]; then
  [[ ${#manifest_fingerprint} -eq 64 ]] || fail "signed release manifest must include a 64-character signing certificate SHA-256 fingerprint"

  if [[ "$skip_certificate" != "true" ]]; then
    apksigner="$(find_apksigner)" || fail "apksigner not found. Install Android SDK build tools or pass --skip-certificate for hash-only verification."
    cert_output="$("$apksigner" verify --print-certs "$apk_path" 2>&1)"
    actual_fingerprint="$(printf '%s\n' "$cert_output" | awk -F': ' '/Signer #1 certificate SHA-256 digest/ { print $2; exit }' | tr '[:upper:]' '[:lower:]' | tr -cd '0-9a-f')"
    [[ ${#actual_fingerprint} -eq 64 ]] || fail "apksigner did not print a signer SHA-256 digest for $apk_path"
    [[ "$actual_fingerprint" == "$manifest_fingerprint" ]] || fail "signing certificate mismatch. Manifest=$manifest_fingerprint, actual=$actual_fingerprint"
  fi
fi

echo "Verified release manifest: $manifest_path"
echo "Verified APK: $apk_path"
echo "Version: $version"
echo "Variant: $variant"
if [[ -n "$distribution_flavor" ]]; then
  echo "Distribution flavor: $distribution_flavor"
  echo "Gradle variant: $gradle_variant"
fi
echo "SHA-256: $actual_hash"
if [[ "$variant" == "signed-release" && "$skip_certificate" != "true" ]]; then
  echo "Signing certificate SHA-256: $manifest_fingerprint"
fi
