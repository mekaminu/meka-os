#!/usr/bin/env python3
"""Ask knows how Navilife itself works (build plan): docs/help/*.md → core's bundled help index.

Each docs/help/<id>.md is one feature: a front matter block (id, title, keywords, where it lives on the Fold and the
Mac, and the screen Take me there opens) and lines `what:`, `how:`, `mac_how:` and `tip:`. This writes
core/domain/src/commonMain/kotlin/os/meka/core/domain/HelpIndex.kt, so the entries ship inside both apps and work
offline. The repo is public (build plan rule 13): no personal details in an entry.

    python3 tools/gen-help.py           write the Kotlin file
    python3 tools/gen-help.py --check   exit 1 if it is out of date or an entry is malformed (CI)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs", "help")
OUT = os.path.join(ROOT, "core", "domain", "src", "commonMain", "kotlin", "os", "meka", "core", "domain", "HelpIndex.kt")
SHELL_NAV = os.path.join(ROOT, "android", "app", "src", "main", "kotlin", "os", "meka", "android", "shell", "ShellNav.kt")
HELP_KT = os.path.join(ROOT, "core", "domain", "src", "commonMain", "kotlin", "os", "meka", "core", "domain", "Help.kt")

FRONT = {"id", "title", "keywords", "data", "fold", "fold_where", "mac", "mac_where"}
BODY = {"what", "how", "mac_how", "tip"}
MAX_LINE = 400


def places():
    """The HelpPlace names declared in Help.kt, so an entry can only point at a real screen."""
    src = open(HELP_KT, encoding="utf-8").read()
    block = re.search(r"enum class HelpPlace\(.*?\{(.*?);", src, re.S)
    if not block:
        raise SystemExit("gen-help: enum class HelpPlace not found in Help.kt")
    return set(re.findall(r"^\s*([A-Z][A-Z_]+)\(", block.group(1), re.M))


def more_items():
    """Ask's rows on the Fold (MoreItem in ShellNav.kt): every one is a HelpPlace, so every feature can have an entry."""
    src = open(SHELL_NAV, encoding="utf-8").read()
    block = re.search(r"enum class MoreItem\(.*?\) \{(.*?);", src, re.S)
    if not block:
        raise SystemExit("gen-help: enum class MoreItem not found in ShellNav.kt")
    return re.findall(r"^\s*([A-Z][A-Z_]+)\(", block.group(1), re.M)


def coverage(entries, known):
    """Every Ask row has a HelpPlace and every Navilife screen (not Settings itself, not Android's pages) an entry."""
    for item in more_items():
        if item not in known:
            raise SystemExit(f"gen-help: Ask's row {item} has no HelpPlace in Help.kt: add one and a docs/help entry")
    opened = {e["fold"] for e in entries} | {e["mac"] for e in entries}
    missing = sorted(p for p in known if not p.startswith("SYSTEM_") and p != "SETTINGS" and p not in opened)
    if missing:
        raise SystemExit("gen-help: no docs/help entry opens " + ", ".join(missing))


def parse(path, known):
    text = open(path, encoding="utf-8").read()
    m = re.match(r"---\n(.*?)\n---\n(.*)", text, re.S)
    if not m:
        raise SystemExit(f"gen-help: {path}: needs a --- front matter block")
    front, body = {}, {"tip": []}
    for line in m.group(1).splitlines():
        if not line.strip():
            continue
        k, _, v = line.partition(":")
        k = k.strip()
        if k not in FRONT:
            raise SystemExit(f"gen-help: {path}: unknown front matter key {k!r}")
        front[k] = v.strip()
    for line in m.group(2).splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        k, _, v = line.partition(":")
        k = k.strip()
        if k not in BODY:
            raise SystemExit(f"gen-help: {path}: lines start with what:, how:, mac_how: or tip: (got {line[:30]!r})")
        v = v.strip()
        if len(v) > MAX_LINE:
            raise SystemExit(f"gen-help: {path}: a line is over {MAX_LINE} characters")
        if k == "tip":
            body["tip"].append(v)
        elif k in body:
            raise SystemExit(f"gen-help: {path}: {k}: given twice")
        else:
            body[k] = v
    for k in ("id", "title", "keywords", "fold", "fold_where", "mac"):
        if not front.get(k):
            raise SystemExit(f"gen-help: {path}: missing {k}")
    for k in ("what", "how"):
        if not body.get(k):
            raise SystemExit(f"gen-help: {path}: missing {k}:")
    if front["id"] + ".md" != os.path.basename(path):
        raise SystemExit(f"gen-help: {path}: id must match the file name")
    for k in ("fold", "mac"):
        v = front[k]
        if v != "none" and v not in known:
            raise SystemExit(f"gen-help: {path}: {k}: {v} is not a HelpPlace")
    if front["mac"] != "none" and not front.get("mac_where"):
        raise SystemExit(f"gen-help: {path}: mac_where is needed when mac isn't none")
    if front.get("data", "no") not in ("yes", "no"):
        raise SystemExit(f"gen-help: {path}: data: is yes or no")
    if front["mac"].startswith("SYSTEM_"):
        raise SystemExit(f"gen-help: {path}: the Mac has no Android settings pages")
    if re.search(r"\bMEKA\b", text):
        raise SystemExit(f"gen-help: {path}: the app is called Navilife")
    keywords = [k.strip() for k in front["keywords"].split(",") if k.strip()]
    if not keywords:
        raise SystemExit(f"gen-help: {path}: no keywords")
    return {**front, **body, "keywords": keywords}


def kt(s):
    if s is None:
        return "null"
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def render(entries):
    out = [
        "// Generated by tools/gen-help.py from docs/help/*.md — do not edit by hand; edit the .md and run the tool.",
        "package os.meka.core.domain",
        "",
        "/** The bundled help entries, one per feature (Ask knows how Navilife itself works). */",
        "internal object HelpIndex {",
        "    val ENTRIES: List<HelpEntry> = listOf(",
    ]
    for e in entries:
        mac = "null" if e["mac"] == "none" else "HelpPlace." + e["mac"]
        fold = "null" if e["fold"] == "none" else "HelpPlace." + e["fold"]
        out += [
            "        HelpEntry(",
            f"            id = {kt(e['id'])},",
            f"            title = {kt(e['title'])},",
            "            keywords = listOf(" + ", ".join(kt(k) for k in e["keywords"]) + "),",
            f"            what = {kt(e['what'])},",
            f"            how = {kt(e['how'])},",
            "            tips = listOf(" + ", ".join(kt(t) for t in e["tip"]) + "),",
            f"            fold = {fold},",
            f"            foldWhere = {kt(e['fold_where'])},",
            f"            mac = {mac},",
            f"            macWhere = {kt(e.get('mac_where') or None)},",
            f"            macHow = {kt(e.get('mac_how'))},",
        ] + (["            data = true,"] if e.get("data") == "yes" else []) + [
            "        ),",
        ]
    out += ["    )", "}", ""]
    return "\n".join(out)


def main():
    known = places()
    files = sorted(f for f in os.listdir(SRC) if f.endswith(".md") and f != "README.md")
    entries = [parse(os.path.join(SRC, f), known) for f in files]
    coverage(entries, known)
    seen = {}
    for e in entries:
        for k in e["keywords"]:
            low = k.lower()
            if low in seen and seen[low] != e["id"]:
                raise SystemExit(f"gen-help: keyword {k!r} is in both {seen[low]} and {e['id']}")
            seen[low] = e["id"]
    text = render(entries)
    if "--check" in sys.argv:
        current = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
        if current != text:
            print("gen-help: HelpIndex.kt is out of date: run python3 tools/gen-help.py", file=sys.stderr)
            sys.exit(1)
        print(f"gen-help: {len(entries)} entries, HelpIndex.kt up to date")
        return
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"gen-help: wrote {len(entries)} entries")


if __name__ == "__main__":
    main()
