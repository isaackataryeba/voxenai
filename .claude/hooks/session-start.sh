#!/bin/bash
set -euo pipefail

# Only run in Claude Code on the web / remote sessions.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# Installs Android platform tools (adb, fastboot) and the Android Asset
# Packaging Tool (aapt) from Ubuntu's apt repos. The official Android SDK
# (sdkmanager, build-tools, platform images) lives on dl.google.com, which
# this environment's network policy blocks, so it can't be installed here.
if ! command -v adb >/dev/null 2>&1 || ! command -v fastboot >/dev/null 2>&1 || ! command -v aapt >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq adb fastboot aapt
fi
