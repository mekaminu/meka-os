#!/usr/bin/env python3
"""No personal details in the public repository (build plan rule 13; the guard is item (3) of "move every personal
detail out of the code into Meka's data").

The deny list is NOT in the repo: it comes from the GitHub Actions secret PERSONAL_DENY_LIST (env var of the same
name), one entry per line:
  - a plain entry matches case-insensitively as a whole word or phrase ("Alex", "Oakfield School");
  - an entry that is all digits (spaces, dashes, + and brackets allowed) is a phone number or postcode-like code: it
    matches those digits however they are spaced or punctuated ("07700 900123" also finds "07700-900-123",
    "+44 7700 900123" finds "07700900123" via its national form);
  - "re:<pattern>" is a case-insensitive regular expression;
  - blank lines and lines starting with # are ignored.
With no secret (a fork, a local run) the guard says so and passes.

What it scans: every file git tracks (text files only), and the newest commit's message. It never prints an entry or
the matching text, because CI logs are public: only file:line and which entry (by its number) matched.

Ways out, each visible in review:
  - tools/personal-guard-exempt.txt lists whole paths that can't change (e.g. an applied migration, whose checksum
    would break), one per line with a reason after " # ".
  - a block between lines containing "personal-guard: off" and "personal-guard: on" is skipped (release 1's one-off
    copies, removed with release 2's final slice).

Run from the repo root: python3 tools/check-personal.py   (exit 1 lists each hit)    --self-test checks the scanner.
"""
import os
import re
import subprocess
import sys

EXEMPT_FILE = "tools/personal-guard-exempt.txt"
OFF, ON = "personal-guard: off", "personal-guard: on"


def compile_entry(entry):
    """One deny-list line → a compiled pattern, or None for blanks and comments."""
    entry = entry.strip()
    if not entry or entry.startswith("#"):
        return None
    if entry.startswith("re:"):
        return re.compile(entry[3:], re.IGNORECASE)
    if re.fullmatch(r"[\d\s()+\-]+", entry) and sum(c.isdigit() for c in entry) >= 5:
        digits = re.sub(r"\D", "", entry)
        if digits.startswith("44") and len(digits) > 10:
            digits = digits[2:]          # +44 7700 … → 7700 … (then matched with or without a leading 0 / +44)
        elif digits.startswith("0"):
            digits = digits[1:]
        sep = r"[\s\-().]*"
        body = sep.join(re.escape(d) for d in digits)
        return re.compile(r"(?<!\d)(?:(?:\+|00)?44" + sep + r"(?:\(0\))?" + sep + r"|0" + sep + r")?" + body + r"(?!\d)")
    words = [re.escape(w) for w in entry.split()]
    return re.compile(r"(?<![\w])" + r"\s+".join(words) + r"(?![\w])", re.IGNORECASE)


def parse_deny_list(text):
    """[(entry number, pattern)] — numbered by line so a hit can be named without printing it."""
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        p = compile_entry(line)
        if p is not None:
            out.append((n, p))
    return out


def parse_exempt(text):
    paths = set()
    for line in text.splitlines():
        path = line.split(" # ")[0].strip()
        if path and not path.startswith("#"):
            paths.add(path)
    return paths


def hits_in(text, patterns):
    """[(line number, entry number)] for each matching line outside personal-guard: off/on blocks."""
    hits, skipping = [], False
    for i, line in enumerate(text.splitlines(), 1):
        if OFF in line:
            skipping = True
            continue
        if ON in line:
            skipping = False
            continue
        if skipping:
            continue
        for n, p in patterns:
            if p.search(line):
                hits.append((i, n))
    return hits


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8", "replace").split("\0") if p]


def read_text(path):
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        return None
    if b"\0" in data[:8192]:
        return None                     # binary (images, fonts, keystores)
    return data.decode("utf-8", "replace")


def scan(patterns, exempt):
    found = []
    for path in tracked_files():
        if path in exempt or path == EXEMPT_FILE:
            continue
        text = read_text(path)
        if text is None:
            continue
        for line, n in hits_in(text, patterns):
            found.append(f"{path}:{line} · deny-list entry {n}")
    msg = subprocess.run(["git", "log", "-1", "--format=%B"], capture_output=True).stdout.decode("utf-8", "replace")
    for line, n in hits_in(msg, patterns):
        found.append(f"newest commit message, line {line} · deny-list entry {n}")
    return found


def self_test():
    pats = parse_deny_list("# invented\n\nAlex\nOakfield School\n07700 900123\nre:AB1\\s?2CD\n")
    assert [n for n, _ in pats] == [3, 4, 5, 6], pats
    t = lambda s: [n for _, n in hits_in(s, pats)]
    assert t("Message for alex today") == [3]
    assert t("Alexander and Alexa") == []                      # whole words only
    assert t("at OAKFIELD   school gates") == [4]
    assert t("call 07700900123") == [5]
    assert t("call +44 7700 900 123 now") == [5]
    assert t("call 0044 (0)7700-900123") == [5]
    assert t("call 17700900123") == []                          # not inside a longer number
    assert t("call 07700 900124") == []
    assert t("post to ab12cd please") == [6]
    blocked = "a\n// personal-guard: off · why\nAlex\n// personal-guard: on\nAlex\n"
    assert hits_in(blocked, pats) == [(5, 3)], hits_in(blocked, pats)
    assert parse_exempt("# c\nbackend/x.sql # applied migration\n\n") == {"backend/x.sql"}
    assert parse_deny_list("") == []
    print("check-personal self-test ok")


def main():
    if "--self-test" in sys.argv:
        self_test()
        return 0
    deny = os.environ.get("PERSONAL_DENY_LIST", "")
    patterns = parse_deny_list(deny)
    if not patterns:
        print("::notice title=Personal details guard::No PERSONAL_DENY_LIST secret here, so nothing was checked "
              "(add it under the repo's Settings → Secrets and variables → Actions).")
        return 0
    exempt = parse_exempt(read_text(EXEMPT_FILE) or "")
    found = scan(patterns, exempt)
    if found:
        for f in found:
            print(f"::error title=Personal detail in the repo::{f}")
        print(f"{len(found)} line(s) hold an entry from the private deny list: replace them with roles or invented "
              f"names (rule 13). The entries themselves are never printed.")
        return 1
    print(f"No personal details: {len(patterns)} deny-list entries checked against {len(tracked_files())} files "
          f"and the newest commit message.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
