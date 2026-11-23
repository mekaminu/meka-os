#!/usr/bin/env python3
"""No two of the Fold's screen shots in one area may be the same picture byte for byte.

Each shot stands for a state of a screen (empty, one, many, by hand, after a tap). When two are identical, one of
those states was never really shown, so nobody reviews it: the weekly walk-throughs kept finding such pairs
(2026-11-22, A11: Speaking up's empty = one = many, Vault and Train queue by hand = one, Watch by hand = empty...).
The screen tests check a reshoot against its opening shot, but a pair across two tests only shows up once both have
run, so this check reads the whole set after the Android tests, in CI.

Pairs still being fixed are listed in KNOWN (area, a, b); a listed pair that is no longer identical makes the check
fail too, so the list only shrinks.

    python3 tools/check-shot-copies.py android/app/build/outputs/roborazzi   # exit 1 on a new identical pair
    python3 tools/check-shot-copies.py --self-test
"""
import hashlib
import os
import sys
import tempfile

# Found and fixing week 5, A11: the identical pairs left on 2026-11-23 (screens branch, ci run 2167), each to fix.
KNOWN = {
    ("calendars", "cover-dark-many-on-today.png", "cover-dark-many.png"),
    ("calendars", "cover-light-many-on-today.png", "cover-light-many.png"),
    ("money", "cover-dark-accounts-layout.png", "cover-dark-accounts-list.png"),
    ("money", "cover-dark-accounts.png", "cover-dark-worth-other-row.png"),
}


def pairs(root):
    """Every identical pair as (area, a, b) with a < b, for each folder of shots under [root]."""
    out = set()
    for d, _, files in os.walk(root):
        by_hash = {}
        for f in sorted(files):
            if not f.endswith(".png"):
                continue
            with open(os.path.join(d, f), "rb") as fh:
                by_hash.setdefault(hashlib.md5(fh.read()).hexdigest(), []).append(f)
        area = os.path.relpath(d, root).replace(os.sep, "/")
        for names in by_hash.values():
            for i, a in enumerate(names):
                for b in names[i + 1:]:
                    out.add((area, a, b))
    return out


def norm(p):
    area, a, b = p
    return (area,) + tuple(sorted((a, b)))


def check(root, known):
    found = {norm(p) for p in pairs(root)}
    known = {norm(p) for p in known}
    areas = {d for d, _, _ in os.walk(root)}
    present = {os.path.relpath(d, root).replace(os.sep, "/") for d in areas}
    new = sorted(found - known)
    # A known pair whose files both exist and now differ is fixed: drop it from KNOWN.
    gone = sorted(p for p in known - found
                  if p[0] in present and all(os.path.exists(os.path.join(root, p[0], f)) for f in p[1:]))
    return new, gone


def main(root):
    if not os.path.isdir(root):
        print(f"no shots at {root}: nothing to check")
        return 0
    new, gone = check(root, KNOWN)
    for area, a, b in new:
        print(f"::error::{area}/{a} is byte for byte {area}/{b}: one of these states isn't shown, so it goes unreviewed")
    for area, a, b in gone:
        print(f"::error::{area}/{a} and {b} differ now: take the pair out of KNOWN in tools/check-shot-copies.py")
    if new or gone:
        return 1
    print(f"no new identical shots ({len(KNOWN)} known pairs still to fix)")
    return 0


def self_test():
    with tempfile.TemporaryDirectory() as t:
        os.makedirs(os.path.join(t, "vault"))
        os.makedirs(os.path.join(t, "watch"))
        def put(area, name, data):
            with open(os.path.join(t, area, name), "wb") as fh:
                fh.write(data)
        put("vault", "cover-dark-one.png", b"one")
        put("vault", "cover-dark-by-hand.png", b"one")
        put("vault", "cover-dark-many.png", b"many")
        put("watch", "cover-dark-empty.png", b"one")  # same bytes in another area is fine
        new, gone = check(t, set())
        assert new == [("vault", "cover-dark-by-hand.png", "cover-dark-one.png")], new
        assert gone == []
        new, gone = check(t, {("vault", "cover-dark-one.png", "cover-dark-by-hand.png")})
        assert new == [] and gone == [], (new, gone)
        put("vault", "cover-dark-by-hand.png", b"hand")
        new, gone = check(t, {("vault", "cover-dark-one.png", "cover-dark-by-hand.png")})
        assert new == [] and gone == [("vault", "cover-dark-by-hand.png", "cover-dark-one.png")], (new, gone)
        # A known pair in an area that wasn't shot this time (a partial run) isn't reported as fixed.
        new, gone = check(t, {("calendars", "a.png", "b.png")})
        assert gone == [], gone
    print("self-test ok")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "android/app/build/outputs/roborazzi"))
