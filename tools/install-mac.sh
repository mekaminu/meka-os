#!/usr/bin/env bash
# Builds the MEKA OS Mac app locally (signed with a stable local dev identity, for this Mac only) and opens it.
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

# Sign with a stable local identity so the app's keychain items survive rebuilds (an ad-hoc signature changes on
# every build, and macOS then asks for the keychain password). The identity lives in its own keychain with a
# script-known password, so nothing here ever needs the login keychain password. Developer ID replaces this for
# release builds (ADR-010).
APP="build/mac/Build/Products/Debug/MekaOS.app"
DEV_KC="$HOME/Library/Keychains/meka-os-dev.keychain-db"
DEV_KC_PW="meka-os-local-dev"   # protects only a local, self-signed dev signing key
IDENTITY="MEKA OS Local Dev"
if [ ! -f "$DEV_KC" ]; then
  security create-keychain -p "$DEV_KC_PW" "$DEV_KC"
  security set-keychain-settings "$DEV_KC"   # no auto-lock
fi
security unlock-keychain -p "$DEV_KC_PW" "$DEV_KC"
if ! security find-certificate -c "$IDENTITY" "$DEV_KC" >/dev/null 2>&1; then
  TMP=$(mktemp -d)
  cat > "$TMP/req.cnf" <<CNF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $IDENTITY
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CNF
  /usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$TMP/req.cnf" -keyout "$TMP/k.pem" -out "$TMP/c.pem"
  /usr/bin/openssl pkcs12 -export -inkey "$TMP/k.pem" -in "$TMP/c.pem" -name "$IDENTITY" -out "$TMP/id.p12" -passout pass:tmp
  security import "$TMP/id.p12" -k "$DEV_KC" -P tmp -T /usr/bin/codesign
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$DEV_KC_PW" "$DEV_KC" >/dev/null
  rm -rf "$TMP"
fi
if codesign --force --deep --keychain "$DEV_KC" --sign "$IDENTITY" "$APP"; then
  echo "Signed with the stable local identity."
else
  echo "WARNING: stable signing failed; falling back to ad-hoc (macOS may ask for the keychain password)."
  codesign --force --deep --sign - "$APP"
fi

mkdir -p "$HOME/Applications"
rm -rf "$HOME/Applications/MekaOS.app"
cp -R "$APP" "$HOME/Applications/"
open "$HOME/Applications/MekaOS.app"
echo "Installed to ~/Applications/MekaOS.app and opened it."
