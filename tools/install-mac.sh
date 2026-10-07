#!/usr/bin/env bash
# Builds the MEKA OS Mac app locally (signed with your Apple Development certificate, for this Mac only) and opens it.
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
  CODE_SIGNING_ALLOWED=NO -quiet build

# Signing decides whether the app keeps its Keychain access across rebuilds (only for keys not yet moved to the
# Secure Enclave-sealed files, see macos/MekaOS/SealedKeyFiles.swift). macOS ties Keychain items to the
# signer's Apple Team ID only for Apple-issued certificates; anything else (ad-hoc, self-signed) is tied to the exact
# build, so every rebuild asks for the login keychain password. So: use the free "Apple Development" certificate
# Xcode creates once an Apple ID is added (Xcode > Settings > Accounts). Developer ID replaces this for releases.
APP="build/mac/Build/Products/Debug/MekaOS.app"
dev_id() { security find-identity -v -p codesigning 2>/dev/null | awk -F'"' '/Apple Development:/ {print $2; exit}'; }
DEV_ID=$(dev_id)
# The certificate exists but isn't trusted yet: usually Apple's current intermediate (WWDR G3) is missing.
if [ -z "$DEV_ID" ] && security find-identity -p codesigning 2>/dev/null | grep -q "Apple Development:"; then
  echo "Installing Apple's developer intermediate certificate (public, from apple.com)…"
  TMPCER=$(mktemp -d)/AppleWWDRCAG3.cer
  curl -fsSL https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer -o "$TMPCER" &&
    security import "$TMPCER" -k "$HOME/Library/Keychains/login.keychain-db" >/dev/null 2>&1 || true
  DEV_ID=$(dev_id)
fi
if [ -n "$DEV_ID" ]; then
  codesign --force --deep --options runtime --entitlements macos/MekaOS/MekaOS.entitlements --sign "$DEV_ID" "$APP"
  echo "Signed with $DEV_ID."
else
  codesign --force --deep --sign - "$APP"
  echo "No Apple Development certificate found, so this build is signed ad hoc. That's fine on a Mac with a Secure"
  echo "Enclave: MEKA keeps its keys in enclave-sealed files, so macOS only asks for the login keychain password once,"
  echo "to move keys from an earlier build. (A Mac without one keeps using the Keychain and asks after each rebuild.)"
fi

mkdir -p "$HOME/Applications"
rm -rf "$HOME/Applications/MekaOS.app"
cp -R "$APP" "$HOME/Applications/"
open "$HOME/Applications/MekaOS.app"
echo "Installed to ~/Applications/MekaOS.app and opened it."
