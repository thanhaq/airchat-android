#!/usr/bin/env bash
set -euo pipefail

script_dir="${BASH_SOURCE[0]%/*}"
if [[ "$script_dir" == "${BASH_SOURCE[0]}" ]]; then
  script_dir="."
fi
script_dir="$(cd "$script_dir" && pwd)"
ps_script="$script_dir/compare-diagnostics.ps1"

if command -v pwsh >/dev/null 2>&1; then
  exec pwsh -NoProfile -ExecutionPolicy Bypass -File "$ps_script" "$@"
fi

if command -v powershell.exe >/dev/null 2>&1; then
  if command -v cygpath >/dev/null 2>&1; then
    ps_script="$(cygpath -w "$ps_script")"
  fi
  exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$ps_script" "$@"
fi

echo "PowerShell 7 (pwsh) or Windows PowerShell is required to compare AirChat diagnostics." >&2
exit 1
