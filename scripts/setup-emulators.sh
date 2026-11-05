#!/usr/bin/env bash
# Provision independent device data; share only the installed read-only SDK image.
set -euo pipefail
command -v avdmanager >/dev/null || { echo 'Install Android SDK command-line tools first.' >&2; exit 1; }
# Match the directory used by the T3 Code device host.
export ANDROID_USER_HOME="$HOME/.android"
export ANDROID_AVD_HOME="$HOME/.android/avd"
for name in tm-agent-1 tm-agent-2; do
  if avdmanager list avd -c | rg -Fxq "$name"; then
    echo "$name already exists; preserving its data."
  else
    avdmanager create avd --name "$name" --package 'system-images;android-36;google_apis;x86_64' --device pixel_7 </dev/null
  fi
done
