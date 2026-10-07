#!/usr/bin/env bash
# Hands-free phone updates (build plan): copies this Mac's Android debug key — the one that signed MEKA on the Fold —
# to the clipboard as base64, then opens the repo's Actions secrets page. Paste it as a new repository secret named
# MEKA_FOLD_SIGNING_KEY. GitHub then signs each phone build with it, so the Fold accepts the update.
# Double-click in Finder, or run:  "./tools/Copy phone signing key.command"
# The key never goes anywhere else: it is not committed, printed or uploaded by this script.
set -euo pipefail
KEY="${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore"
if [ ! -f "$KEY" ]; then
  echo "No debug key at $KEY. Build MEKA once in Android Studio (or run tools/install-fold.sh) on the Mac that installed it."
  read -r -p "Press Return to close." _; exit 1
fi
KT=$(command -v keytool || echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool")
if [ -x "$KT" ] && ! "$KT" -list -keystore "$KEY" -storepass android -alias androiddebugkey >/dev/null 2>&1; then
  echo "That key doesn't open with the debug defaults (password android, alias androiddebugkey); GitHub can't use it."
  read -r -p "Press Return to close." _; exit 1
fi
base64 -i "$KEY" | tr -d '\n' | pbcopy
open "https://github.com/mekaminu/meka-os/settings/secrets/actions/new"
echo "Copied. On the GitHub page: Name = MEKA_FOLD_SIGNING_KEY, paste into Secret, then Add secret."
echo "Then clear the clipboard by copying something else."
read -r -p "Press Return to close." _
