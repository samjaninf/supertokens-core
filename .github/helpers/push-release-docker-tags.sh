#!/usr/bin/env bash
# Pushes all appropriate release tags for one registry.
# Usage: push-release-docker-tags.sh <dev_image> <registry/repo> <version> <is_canary> <is_latest_for_major> <is_latest_release>
set -euo pipefail

dev_image="$1"
registry_repo="$2"
version="$3"
is_canary="$4"
is_latest_for_major="$5"
is_latest_release="$6"

major=$(echo "$version" | cut -d. -f1)
minor=$(echo "$version" | cut -d. -f1,2)
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$is_canary" == "true" ]; then
  bash "$script_dir/release-docker.sh" "$dev_image" "$registry_repo:$version-canary"
else
  bash "$script_dir/release-docker.sh" "$dev_image" "$registry_repo:$version"
  bash "$script_dir/release-docker.sh" "$dev_image" "$registry_repo:$minor"

  if [ "$is_latest_for_major" == "true" ]; then
    bash "$script_dir/release-docker.sh" "$dev_image" "$registry_repo:$major"
  fi

  if [ "$is_latest_release" == "true" ]; then
    bash "$script_dir/release-docker.sh" "$dev_image" "$registry_repo:latest"
  fi
fi
