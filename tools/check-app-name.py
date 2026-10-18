#!/usr/bin/env python3
"""The rename to Navilife (Meka, 2026-10-13): no user-visible "MEKA" left in the apps' copy.

Scans what a person reads: string literals in the Kotlin and Swift sources of the core, the Fold, the watch, the Mac and
the server (comments and identifiers are the code name and stay), the Android string resources, the Mac's Info.plists
and project.yml, and the family page. "MEKA" is allowed only in wire formats that must not change (signatures, user
agents) and in the line that says the old name still works when he talks to it. Data he typed isn't in the repo.

Run from the repo root: python3 tools/check-app-name.py   (exit 1 lists each hit)    --self-test checks the scanner.
"""
import os
import re
import sys

NAME = re.compile(r"\bMEKA\b")
# Wire formats and the one deliberate mention (AppUpdateRules.ICON_WHY).
ALLOWED = [re.compile(p) for p in (
    r"\bMEKA-APPROVAL1\b", r"\bMEKA-OS/1\b", r"\bMEKA-RECOVERY-1\b", r"\bMEKA-VAULT-(ID|CODE|WRAP|RECOVERY)-1\b", r"\bMEKA/1 \(", r"MEKA@gmail\.com",
    r'\\"MEKA\\" still works',
)]
CODE_ROOTS = ["core", "android/app/src/main", "wear/app/src/main", "macos/MekaOS", "macos/NewsWidget",
              "macos/NewsWidgetShared", "backend/src/main"]
def literal_spans(src, swift):
    """Yield (start,end) of string literal text (excluding interpolation code). Kotlin & Swift."""
    i=0;n=len(src);spans=[]
    stack=[]  # contexts: ('code',depth) for interpolation
    def code(i, stop_depth=None):
        depth=0
        while i<n:
            c=src[i]
            if src.startswith('//',i):
                j=src.find('\n',i); i = n if j<0 else j; continue
            if src.startswith('/*',i):
                d=1;i+=2
                while i<n and d:
                    if src.startswith('/*',i): d+=1;i+=2
                    elif src.startswith('*/',i): d-=1;i+=2
                    else: i+=1
                continue
            if src.startswith('"""',i):
                i=string(i+3,'"""'); continue
            if c=='"':
                i=string(i+1,'"'); continue
            if c=="'" and not swift:
                # char literal
                j=i+1
                if j<n and src[j]=='\\': j+=2
                else: j+=1
                if j<n and src[j]=="'": i=j+1; continue
                i+=1; continue
            if stop_depth is not None:
                if c in '({': depth+=1
                elif c in ')}':
                    if depth==0: return i+1
                    depth-=1
            i+=1
        return i
    def string(i,q):
        start=i
        while i<n:
            if src.startswith(q,i):
                spans.append((start,i)); return i+len(q)
            c=src[i]
            if q=='"' and c=='\n':
                spans.append((start,i)); return i
            if swift and src.startswith('\\(',i):
                spans.append((start,i)); i=code(i+2,0); start=i; continue
            if not swift and src.startswith('${',i):
                spans.append((start,i)); i=code(i+2,0); start=i; continue
            if c=='\\' : i+=2; continue
            i+=1
        spans.append((start,i)); return i
    code(0)
    return spans


def hits_in(text):
    """The "MEKA"s in [text] that aren't allowed."""
    masked = text
    for p in ALLOWED:
        masked = p.sub(lambda m: "x" * len(m.group(0)), masked)
    return [m.start() for m in NAME.finditer(masked)]


def scan_code(path, src):
    swift = path.endswith(".swift")
    out = []
    for a, b in literal_spans(src, swift):
        for i in hits_in(src[a:b]):
            out.append((src.count("\n", 0, a + i) + 1, src[a:b].strip()[:90]))
    return out


def scan_xml_values(src):
    """<string> bodies in Android resources and plists, comments left out."""
    src = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    out = []
    for m in re.finditer(r"<string[^>]*>(.*?)</string>", src, flags=re.S):
        for i in hits_in(m.group(1)):
            out.append((src.count("\n", 0, m.start(1) + i) + 1, m.group(1).strip()[:90]))
    return out


def scan_yaml(src):
    out = []
    for n, line in enumerate(src.splitlines(), 1):
        value = line.split(" #", 1)[0] if not line.lstrip().startswith("#") else ""
        if ":" in value and hits_in(value.split(":", 1)[1]):
            out.append((n, value.strip()[:90]))
    return out


def scan_html(src):
    src = re.sub(r"<!--.*?-->", "", src, flags=re.S)
    out = []
    for n, line in enumerate(src.splitlines(), 1):
        if hits_in(re.sub(r"<[^>]+>", " ", line)):
            out.append((n, line.strip()[:90]))
    return out


def files(root):
    for dp, dn, fn in os.walk(root):
        dn[:] = [d for d in dn if d not in ("build", "node_modules", ".gradle")]
        for f in fn:
            yield os.path.join(dp, f)


def scan_repo(root="."):
    found = []
    for r in CODE_ROOTS:
        for p in files(os.path.join(root, r)):
            if "/test/" in p or "Test/" in p or "Tests/" in p or p.endswith(("Test.kt", "Tests.swift")):
                continue
            if p.endswith((".kt", ".swift", ".js")):
                found += [(p, n, t) for n, t in scan_code(p, open(p, encoding="utf-8").read())]
            elif p.endswith(".html"):
                found += [(p, n, t) for n, t in scan_html(open(p, encoding="utf-8").read())]
            elif p.endswith(".plist") or ("/res/values" in p and p.endswith(".xml")):
                found += [(p, n, t) for n, t in scan_xml_values(open(p, encoding="utf-8").read())]
    yml = os.path.join(root, "macos/project.yml")
    found += [(yml, n, t) for n, t in scan_yaml(open(yml, encoding="utf-8").read())]
    return found


def self_test():
    assert scan_code("a.kt", 'val a = "Talk to MEKA" // MEKA\n/* MEKA */ val b = "MEKA-APPROVAL1"\n')[0][0] == 1
    assert len(scan_code("a.kt", 'val a = "Talk to MEKA" // MEKA\n/* MEKA */ val b = "MEKA-APPROVAL1"\n')) == 1
    assert scan_code("a.kt", 'val a = "${x("MEKA")} ok"') != []
    assert scan_code("a.kt", 'val a = "${MEKA} ok"') == []          # an identifier inside a template
    assert scan_code("a.swift", 'Text("Hi \\(MEKA.x)")') == []
    assert scan_code("a.swift", 'Text("Hi MEKA")') != []
    assert scan_code("a.kt", 'val a = """\n  You are MEKA\n"""') != []
    assert scan_code("a.kt", 'const val W = "and \\"MEKA\\" still works"') == []
    assert scan_xml_values('<!-- MEKA --><string name="a">Talk to MEKA</string>') != []
    assert scan_xml_values('<!-- MEKA --><string name="a">Navilife</string>') == []
    assert scan_yaml("  # MEKA\n  a: Navilife # MEKA\n") == []
    assert scan_yaml("  a: Add to MEKA\n") != []
    assert scan_html("<!-- MEKA --><title>Navilife</title>") == []
    print("check-app-name self-test ok")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        sys.exit(0)
    hits = scan_repo()
    for p, n, t in hits:
        print(f"{p}:{n}: \"MEKA\" a person would read: {t}")
    if hits:
        print(f"{len(hits)} left; the app is called Navilife (build plan: the rename). Use Navilife, or allow a wire format in "
              "tools/check-app-name.py.", file=sys.stderr)
        sys.exit(1)
    print("No user-visible MEKA left.")
