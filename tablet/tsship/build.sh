#!/bin/sh
# Builds the userspace Tailscale bridge for the tablet build type into build/tsship.aar.
# Needs Go, gomobile (go install golang.org/x/mobile/cmd/gomobile@latest) and the Android NDK.
set -eu
cd "$(dirname "$0")"
export PATH="$PATH:$(go env GOPATH)/bin"
[ -d "${ANDROID_HOME:-}" ] || ANDROID_HOME="$(sed -n "s/^sdk.dir=//p" ../../local.properties)"
export ANDROID_HOME
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | tail -1)}"
mkdir -p build
gomobile bind -target=android/arm64 -androidapi 26 -trimpath -ldflags=-s -o build/tsship.aar .
