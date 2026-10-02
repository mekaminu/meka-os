#!/usr/bin/env bash
# Builds the MEKA OS Mac app locally (ad-hoc signed, for this Mac only) and opens it.
# Developer ID signing + notarisation is the release path (ADR-010); this is the dev path.
# Run from anywhere on the Mac:  ./tools/install-mac.sh
set -euo pipefail
cd "$(dirname "$0")/.."
# Keep a full log so problems can be diagnosed without scrolling the terminal.
mkdir -p build && exec > >(tee "build/install-mac.log") 2>&1

if ! xcodebuild -version >/dev/null 2>&1; then
  echo "Xcode is needed for the Mac app. Install Xcode from the App Store, open it once, then run this again."
  exit 1
fi
echo "$(xcodebuild -version | head -1)"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

if ! command -v xcodegen >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then brew install xcodegen
  else echo "Needs XcodeGen: install Homebrew (https://brew.sh) then run this again."; exit 1; fi
fi

./gradlew :core:facade:assembleMekaKitReleaseXCFramework
xcodegen generate --spec macos/project.yml --quiet
xcodebuild -project macos/MekaOS.xcodeproj -scheme MekaOS -configuration Debug \
  -destination 'platform=macOS,arch=arm64' -derivedDataPath build/mac \
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= -quiet build

APP="build/mac/Build/Products/Debug/MekaOS.app"
mkdir -p "$HOME/Applications"
rm -rf "$HOME/Applications/MekaOS.app"
cp -R "$APP" "$HOME/Applications/"
open "$HOME/Applications/MekaOS.app"
echo "Installed to ~/Applications/MekaOS.app and opened it."
