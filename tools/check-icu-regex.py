#!/usr/bin/env python3
"""Every Kotlin regex literal must compile under ICU, the regex engine Android uses.

The JVM's java.util.regex (which runs the core tests) accepts patterns that Android's ICU-based engine rejects at
runtime with PatternSyntaxException, crashing the screen that first touches the pattern. The 2026-11-10 Day list
crash was one: in `(?<![\\w:])(\\d)[:.](\\d)(?![\\w:])` ICU reads `[:.](...)(?![\\w:]` as one POSIX property
expression (`[:` ... `:]`) and fails with U_REGEX_PROPERTY_SYNTAX. The usual fix: never open a bracket class with
a colon (write `[.:]`, not `[:.]`).

This check finds `Regex(...)` / `Pattern.compile(...)` calls in core, android and wear, rebuilds the pattern from
the Kotlin string literal(s) (raw and escaped strings, `+` concatenation, `$NAME` from a string `val` in the same
file; other interpolations become a plain `1`) and compiles it with the system ICU library (libicui18n) through
ctypes. Patterns built at runtime from non-literal values are skipped.

    python3 tools/check-icu-regex.py              # scan the repo, exit 1 on any pattern ICU rejects
    python3 tools/check-icu-regex.py --self-test  # the extractor and ICU binding behave as expected
"""
import ctypes
import ctypes.util
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCAN = ["core", "android", "wear"]


# ---------------------------------------------------------------- ICU binding

def _load_icu():
    names = []
    for d in ["/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib", "/usr/local/lib"]:
        names += sorted(glob.glob(os.path.join(d, "libicui18n.so.*")))
    found = ctypes.util.find_library("icui18n")
    if found:
        names.append(found)
    for name in names:
        m = re.search(r"libicui18n\.so\.(\d+)", name)
        try:
            i18n = ctypes.CDLL(name)
        except OSError:
            continue
        ver = m.group(1) if m else None
        uc = ctypes.CDLL(name.replace("i18n", "uc"))
        for suffix in ([f"_{ver}"] if ver else []) + [""]:
            try:
                op = getattr(i18n, "uregex_open" + suffix)
                cl = getattr(i18n, "uregex_close" + suffix)
                en = getattr(uc, "u_errorName" + suffix)
            except AttributeError:
                continue
            op.restype = ctypes.c_void_p
            op.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_uint32, ctypes.c_void_p,
                           ctypes.POINTER(ctypes.c_int)]
            cl.argtypes = [ctypes.c_void_p]
            en.restype = ctypes.c_char_p
            return op, cl, en
    sys.exit("check-icu-regex: libicui18n not found (apt-get install libicu-dev or libicu74)")


_ICU = None
UREGEX_CASE_INSENSITIVE, UREGEX_COMMENTS, UREGEX_MULTILINE, UREGEX_DOTALL = 2, 4, 8, 32


def icu_error(pattern, flags=0):
    """None when ICU compiles the pattern, else ICU's error name (e.g. U_REGEX_PROPERTY_SYNTAX)."""
    global _ICU
    if _ICU is None:
        _ICU = _load_icu()
    op, cl, en = _ICU
    raw = pattern.encode("utf-16-le", "surrogatepass")
    buf = ctypes.create_string_buffer(raw, len(raw) + 2)
    err = ctypes.c_int(0)
    parse_err = ctypes.create_string_buffer(512)
    handle = op(buf, len(raw) // 2, flags, parse_err, ctypes.byref(err))
    if handle:
        cl(handle)
    return None if err.value <= 0 else en(err.value).decode()


# ---------------------------------------------------------------- Kotlin literal extraction

class Skip(Exception):
    pass


def _read_template(src, i, consts, depth):
    """Read one string literal starting at src[i] (a quote). Returns (text, index after it)."""
    raw = src.startswith('"""', i)
    j = i + (3 if raw else 1)
    out = []
    while True:
        if j >= len(src):
            raise Skip()
        if raw and src.startswith('"""', j):
            j += 3
            while j < len(src) and src[j] == '"':  # """...""""" : extra quotes belong to the content
                out.append('"')
                j += 1
            return "".join(out), j
        c = src[j]
        if not raw and c == '"':
            return "".join(out), j + 1
        if not raw and c == "\n":
            raise Skip()
        if not raw and c == "\\":
            n = src[j + 1]
            if n == "u":
                out.append(chr(int(src[j + 2:j + 6], 16)))
                j += 6
                continue
            out.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b", "0": "\0"}.get(n, n))
            j += 2
            continue
        if c == "$":
            if src.startswith("${", j):
                k, level = j + 2, 1
                while k < len(src) and level:
                    level += {"{": 1, "}": -1}.get(src[k], 0)
                    k += 1
                inner = src[j + 2:k - 1].strip()
                if inner == "'$'":
                    out.append("$")
                elif inner in consts and depth < 4:
                    out.append(_resolve(inner, consts, depth + 1))
                else:
                    out.append("1")
                j = k
                continue
            m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*)", src[j:])
            if m:
                name = m.group(1)
                out.append(_resolve(name, consts, depth + 1) if name in consts and depth < 4 else "1")
                j += len(m.group(0))
                continue
        out.append(c)
        j += 1


def _resolve(name, consts, depth):
    src, i = consts[name]
    try:
        text, _ = _read_concat(src, i, consts, depth)
        return text
    except Skip:
        return "1"


def _skip_space(src, i):
    while i < len(src):
        if src[i] in " \t\r\n":
            i += 1
        elif src.startswith("//", i):
            i = src.index("\n", i) if "\n" in src[i:] else len(src)
        else:
            break
    return i


def _read_concat(src, i, consts, depth=0):
    """Read `"a" + "b" + NAME ...` starting at src[i]. Raises Skip on anything that isn't a literal or a known val."""
    parts = []
    while True:
        i = _skip_space(src, i)
        if src.startswith('"', i):
            text, i = _read_template(src, i, consts, depth)
        else:
            m = re.match(r"[A-Za-z_][A-Za-z0-9_]*", src[i:])
            if not m or m.group(0) not in consts or depth >= 4:
                raise Skip()
            text = _resolve(m.group(0), consts, depth + 1)
            i += len(m.group(0))
        parts.append(text)
        k = _skip_space(src, i)
        if src.startswith(".trimIndent()", k) or src.startswith(".trimMargin()", k):
            raise Skip()
        if src.startswith("+", k) and not src.startswith("+=", k):
            i = k + 1
            continue
        return "".join(parts), i


CONST = re.compile(r"\b(?:const\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*(?=\")")
CALL = re.compile(r"(?<![A-Za-z0-9_.])(?:kotlin\.text\.)?Regex\s*\(|\bPattern\.compile\s*\(")


def patterns_in(src):
    """Yield (line, pattern, flags) for each regex literal in a Kotlin source."""
    consts = {m.group(1): (src, m.end()) for m in CONST.finditer(src)}
    for m in CALL.finditer(src):
        line = src.count("\n", 0, m.start()) + 1
        try:
            text, end = _read_concat(src, m.end(), consts)
        except Skip:
            continue
        tail = src[end:end + 200]
        flags = 0
        if "IGNORE_CASE" in tail.split(")")[0] or "CASE_INSENSITIVE" in tail.split(")")[0]:
            flags |= UREGEX_CASE_INSENSITIVE
        if "MULTILINE" in tail.split(")")[0]:
            flags |= UREGEX_MULTILINE
        if "COMMENTS" in tail.split(")")[0]:
            flags |= UREGEX_COMMENTS
        yield line, text, flags


def scan():
    bad, seen = [], 0
    for top in SCAN:
        for path in glob.glob(os.path.join(ROOT, top, "**", "*.kt"), recursive=True):
            if f"{os.sep}build{os.sep}" in path:
                continue
            with open(path, encoding="utf-8") as f:
                src = f.read()
            for line, pattern, flags in patterns_in(src):
                seen += 1
                err = icu_error(pattern, flags)
                if err:
                    bad.append((os.path.relpath(path, ROOT), line, err, pattern))
    return seen, bad


# ---------------------------------------------------------------- self-test

def self_test():
    # ICU itself: the pattern behind the 2026-11-10 crash fails, the reordered one compiles.
    assert icu_error(r"(?<![A-Za-z0-9_:])([01]?\d|2[0-3])[:.]([0-5]\d)(?![A-Za-z0-9_:])") == "U_REGEX_PROPERTY_SYNTAX"
    assert icu_error(r"(?<![A-Za-z0-9_:])([01]?\d|2[0-3])[.:]([0-5]\d)(?![A-Za-z0-9_:])") is None
    assert icu_error(r"(?<![\w:])\d") is None
    assert icu_error(r"(unclosed") is not None
    # Extraction: raw, escaped, concatenated, interpolated.
    src = '''
        private const val H = "(\\\\d{1,2})"
        val a = Regex("""(?<![\\w:])$H[:.](\\d\\d)(?![\\w:])""")
        val b = Regex("[\\\\s,]+" +
            "x")
        val c = Regex(someRuntimeValue)
        val d = Regex("""a${'$'}""", RegexOption.IGNORE_CASE)
        val e = Regex("tab\\tq\\"")
    '''
    got = list(patterns_in(src))
    assert [p for _, p, _ in got] == [r"(?<![\w:])(\d{1,2})[:.](\d\d)(?![\w:])", r"[\s,]+x", "a$", 'tab\tq"'], got
    assert got[0][0] == 3 and got[2][2] == UREGEX_CASE_INSENSITIVE, got
    bad = [p for _, p, f in got if icu_error(p, f)]
    assert bad == [got[0][1]], bad
    print("check-icu-regex self-test: ok")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        sys.exit(0)
    seen, bad = scan()
    for path, line, err, pattern in bad:
        print(f"{path}:{line}: {err}: {pattern}")
    print(f"check-icu-regex: {seen} regex literals compiled with ICU, {len(bad)} rejected")
    if bad:
        print("Android's regex engine (ICU) rejects these at runtime. A bracket class opening with ':' (e.g. [:.]) "
              "pairs with a later ':]' as a POSIX property: write [.:] instead.")
    sys.exit(1 if bad else 0)
