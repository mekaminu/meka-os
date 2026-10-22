#!/usr/bin/env bash
# The quality gate's screenshots, where a build run can see them (build plan: "a quality gate so Meka stops being the
# tester", slice 2). CI's artifacts download from a host the build container can't reach, but git it can: after a green
# run on main, CI copies the shots into the `screens` branch under docs/screens/<area>/ and pushes one commit, only when
# an image actually changed (unchanged PNGs cost nothing in history). The branch is append-only: never force-pushed,
# never deleted; a shot that no longer exists is removed by a normal commit.
#
#   tools/publish-screens.sh <shots dir> <area> <note>
#     e.g. tools/publish-screens.sh android/app/build/outputs/roborazzi fold "ci run 1035 at 70d53be"
#
# A build run reads them with:
#   git fetch origin screens && git worktree add ../screens origin/screens --detach   # then docs/screens/<area>/...
#
# Never fails the caller for a publishing hiccup (CI runs it with continue-on-error too); exits non-zero only on misuse.
set -uo pipefail

SRC="${1:?shots dir}"; AREA="${2:?area, e.g. fold}"; NOTE="${3:-}"
BRANCH="${SCREENS_BRANCH:-screens}"
REMOTE="${SCREENS_REMOTE:-origin}"
case "$AREA" in *[!a-z0-9-]*|"") echo "area must be lower-case letters, digits and dashes" >&2; exit 2 ;; esac
SRC="$(cd "$SRC" 2>/dev/null && pwd)" || { echo "no shots folder: nothing to publish"; exit 0; }
[ -n "$(find "$SRC" -name '*.png' -print -quit)" ] || { echo "no shots: nothing to publish"; exit 0; }

WT="$(mktemp -d)/screens"
cleanup() { git worktree remove --force "$WT" >/dev/null 2>&1; rm -rf "$(dirname "$WT")"; }
trap cleanup EXIT

# The README lists what's there (paths only: no run ids, so an unchanged set of images makes no commit).
write_index() {
  {
    echo "# Navilife screens"
    echo
    echo "Written by CI after each green run on main (tools/publish-screens.sh). Not code: the quality gate's"
    echo "screenshots, for review. Invented data only. This branch is append-only."
    echo
    (cd "$WT/docs/screens" && find . -name '*.png' | sed 's|^\./||' | LC_ALL=C sort) | while read -r f; do
      echo "- [$f](docs/screens/$f)"
    done
  } > "$WT/README.md"
}

for attempt in 1 2 3; do
  git worktree remove --force "$WT" >/dev/null 2>&1; rm -rf "$WT"
  if git fetch --quiet --depth=1 "$REMOTE" "$BRANCH" 2>/dev/null; then
    git worktree add --quiet --detach "$WT" FETCH_HEAD || { echo "worktree failed"; exit 0; }
  else
    # First publish: a branch with no history of main's, holding only the screens.
    git worktree add --quiet --detach "$WT" HEAD || { echo "worktree failed"; exit 0; }
    git -C "$WT" checkout --quiet --orphan "screens-$$" && git -C "$WT" rm -rfq . >/dev/null
    find "$WT" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  fi
  rm -rf "$WT/docs/screens/$AREA"
  mkdir -p "$WT/docs/screens/$AREA"
  (cd "$SRC" && find . -name '*.png' -print0 | while IFS= read -r -d '' f; do
    mkdir -p "$WT/docs/screens/$AREA/$(dirname "$f")"; cp "$f" "$WT/docs/screens/$AREA/$f"
  done)
  write_index
  git -C "$WT" add -A
  if git -C "$WT" diff --cached --quiet; then echo "screens unchanged: nothing to publish"; exit 0; fi
  git -C "$WT" -c user.name="navilife-ci" -c user.email="navilife-ci@users.noreply.github.com" \
    commit --quiet -m "screens: $AREA${NOTE:+ · $NOTE}" || { echo "commit failed"; exit 0; }
  if git -C "$WT" push --quiet "$REMOTE" "HEAD:refs/heads/$BRANCH"; then
    echo "published $(git -C "$WT" show --stat --format= HEAD | tail -1) to $BRANCH"
    exit 0
  fi
  echo "push rejected (attempt $attempt): another run published first, trying again on top of it"
  sleep $((attempt * 3))
done
echo "could not publish the screens after 3 attempts"
exit 0
