#!/usr/bin/env bash
set -euo pipefail

remote_url="${1:-}"
tag="${2:-}"

if [[ -z "$remote_url" ]]; then
  echo "Usage: bash ./scripts/publish-github.sh <remote-url> [tag]" >&2
  exit 1
fi

if [[ ! -d ".git" ]]; then
  echo "Run this script from the AirChat repository root." >&2
  exit 1
fi

if [[ -n "$(git status --short)" ]]; then
  echo "Working tree is not clean. Commit or stash changes before publishing." >&2
  exit 1
fi

branch="$(git branch --show-current)"
if [[ "$branch" != "main" ]]; then
  echo "Expected branch main, got $branch." >&2
  exit 1
fi

if git remote get-url origin >/dev/null 2>&1; then
  existing_remote="$(git remote get-url origin)"
  if [[ "$existing_remote" != "$remote_url" ]]; then
    echo "origin already points to $existing_remote." >&2
    echo "Set the new remote manually with: git remote set-url origin $remote_url" >&2
    exit 1
  fi
else
  git remote add origin "$remote_url"
fi

git push -u origin main

if [[ -n "$tag" ]]; then
  if ! git rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1; then
    git tag -a "$tag" -m "AirChat $tag"
  fi
  git push origin "$tag"
fi

git remote -v
