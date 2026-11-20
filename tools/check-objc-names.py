#!/usr/bin/env python3
"""No public Kotlin name in the shared core may be a C preprocessor macro from the Mac's system headers.

The core is exported to the Mac as the MekaKit framework, whose Objective-C header is compiled with the system
headers in scope. A public property spelled like a system macro is rewritten by the preprocessor and the header
stops compiling: on 2026-11-20 `DeviceSpeed.NAME_MAX` became `int32_t 255` (NAME_MAX is in <limits.h>) and the
whole Mac build failed at the XCFramework step, which only the nightly macOS job runs. This check catches it on
Linux in seconds.

    python3 tools/check-objc-names.py              # scan core/, exit 1 on any clash
    python3 tools/check-objc-names.py --self-test
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Macros from <limits.h>, <sys/syslimits.h>, <stdio.h>, <stdlib.h>, <math.h>, <objc/objc.h> and <sys/param.h>
# that a Kotlin constant could plausibly be called. Function-like macros (MIN, MAX) and YES/NO are left out: the
# core uses them and the Mac build has passed with them (Kotlin/Native renames or they don't expand there).
MACROS = {
    "NAME_MAX", "PATH_MAX", "LINE_MAX", "ARG_MAX", "OPEN_MAX", "CHILD_MAX", "PIPE_BUF", "IOV_MAX", "LINK_MAX",
    "MAX_CANON", "MAX_INPUT", "NGROUPS_MAX", "BC_BASE_MAX", "BC_DIM_MAX", "BC_SCALE_MAX", "BC_STRING_MAX",
    "COLL_WEIGHTS_MAX", "EXPR_NEST_MAX", "RE_DUP_MAX", "CHARCLASS_NAME_MAX", "NZERO", "MAXPATHLEN", "MAXHOSTNAMELEN",
    "CHAR_BIT", "CHAR_MAX", "CHAR_MIN", "SCHAR_MAX", "SCHAR_MIN", "UCHAR_MAX", "SHRT_MAX", "SHRT_MIN", "USHRT_MAX",
    "INT_MAX", "INT_MIN", "UINT_MAX", "LONG_MAX", "LONG_MIN", "ULONG_MAX", "LLONG_MAX", "LLONG_MIN", "ULLONG_MAX",
    "SSIZE_MAX", "SIZE_MAX", "MB_LEN_MAX", "BUFSIZ", "EOF", "FILENAME_MAX", "FOPEN_MAX", "TMP_MAX", "L_tmpnam",
    "RAND_MAX", "EXIT_SUCCESS", "EXIT_FAILURE", "NULL", "NAN", "INFINITY", "HUGE_VAL",
    "DEV_BSIZE", "NBBY", "NOFILE", "HZ", "MAXSYMLINKS",
}
DECL = re.compile(r"^\s*(?:(?:public|override|actual|expect|open|final)\s+)*(?:const\s+)?va[lr]\s+([A-Za-z_][A-Za-z0-9_]*)")
HIDDEN = re.compile(r"^\s*(?:[a-z]+\s+)*(private|internal|protected)\s")


def clashes(text):
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        if HIDDEN.match(line):
            continue
        m = DECL.match(line)
        if m and m.group(1) in MACROS:
            out.append((n, m.group(1)))
    return out


def self_test():
    assert clashes("    const val NAME_MAX = 32") == [(1, "NAME_MAX")]
    assert clashes("    private const val NAME_MAX = 32") == []
    assert clashes("    internal val PATH_MAX = 1") == []
    assert clashes("    const val MAX_NAME_LENGTH = 32") == []
    assert clashes("    val INT_MAX: Int get() = 1") == [(1, "INT_MAX")]
    print("check-objc-names self-test: ok")


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    bad = []
    for path in glob.glob(os.path.join(ROOT, "core", "**", "src", "*Main", "**", "*.kt"), recursive=True):
        if "/build/" in path or "/jvmMain/" in path or "/androidMain/" in path:
            continue
        with open(path, encoding="utf-8") as f:
            for n, name in clashes(f.read()):
                bad.append(f"{os.path.relpath(path, ROOT)}:{n}: `{name}` is a C macro on the Mac; rename it")
    for b in bad:
        print(b)
    print(f"check-objc-names: {len(bad)} clash(es)")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
