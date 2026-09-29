#!/usr/bin/env python3
"""Apply the documented static C++ runtime profile to the pinned wrapper.

The build helper only calls this after confirming the source checkout is clean,
and restores these tracked wrapper scripts when the build exits.
"""
from pathlib import Path
import sys


REPLACEMENTS = {
    "scripts/function-android.sh": (
        ("echo \"c++_shared\"", "echo \"c++_static\"", 1),
        ("-lc++_shared", "-lc++_static -lc++abi -lunwind", 5),
    ),
    "scripts/android/libsvtav1.sh": (("ANDROID_STL=c++_shared", "ANDROID_STL=c++_static", 1),),
    "scripts/android/x265.sh": ((
        'ASM_OPTIONS="-DENABLE_ASSEMBLY=1 -DCROSS_COMPILE_ARM64=1 -DENABLE_SVE=0 -DENABLE_SVE2=0 -DENABLE_SVE2_BITPERM=0"',
        'ASM_OPTIONS="-DENABLE_ASSEMBLY=1 -DCROSS_COMPILE_ARM64=1 -DENABLE_SVE=0 -DENABLE_SVE2=0 -DENABLE_SVE2_BITPERM=0 -DENABLE_NEON_DOTPROD=0 -DENABLE_NEON_I8MM=0"',
        1,
    ),),
}


def patch(source: Path) -> None:
    revised = {}
    for relative, replacements in REPLACEMENTS.items():
        path = source / relative
        content = path.read_text()
        for before, after, count in replacements:
            actual = content.count(before)
            if actual != count:
                raise ValueError(f"{relative}: expected {count} {before!r} occurrences, found {actual}")
            content = content.replace(before, after)
        revised[path] = content
    for path, content in revised.items():
        path.write_text(content)


if __name__ == "__main__":
    patch(Path(sys.argv[1]))
