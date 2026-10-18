#!/usr/bin/env bash
# Checks ZIP data offsets, compression, and ELF LOAD alignment for 64-bit libraries.
# See docs/16kb-page-size.md for dependencies and known blockers.
# Usage: scripts/check-16kb-alignment.sh <apk> [<apk> ...]
#        scripts/check-16kb-alignment.sh            # all APKs under app/build

set -euo pipefail

readelf_bin=$(command -v readelf || command -v llvm-readelf || true)
if [ -z "$readelf_bin" ]; then
    echo "error: readelf (binutils) or llvm-readelf is required" >&2
    exit 2
fi

if ! command -v python3 >/dev/null; then
    echo "error: python3 is required" >&2
    exit 2
fi

exec python3 - "$readelf_bin" "$@" <<'PY'
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

PAGE_SIZE = 16384
readelf = sys.argv[1]
apks = sys.argv[2:] or sorted(Path("app/build").rglob("*.apk"))
if not apks:
    print("error: no APK given and none found under app/build", file=sys.stderr)
    sys.exit(2)

status = 0
for apk in apks:
    print(f"== {apk}")
    try:
        with zipfile.ZipFile(apk) as archive, open(apk, "rb") as raw:
            libraries = [
                entry for entry in archive.infolist()
                if entry.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/"))
                and entry.filename.endswith(".so") and not entry.is_dir()
            ]
            if not libraries:
                print("   no 64-bit native libraries")
            for entry in libraries:
                name = entry.filename
                if entry.compress_type != zipfile.ZIP_STORED:
                    print(f"   {name:<46} FAIL  compressed in the APK")
                    status = 1

                raw.seek(entry.header_offset)
                header = raw.read(30)
                if len(header) != 30 or header[:4] != b"PK\x03\x04":
                    raise zipfile.BadZipFile(f"invalid local header for {name}")
                # Local and central-directory extra fields can have different lengths.
                filename_size, extra_size = struct.unpack_from("<HH", header, 26)
                offset = entry.header_offset + 30 + filename_size + extra_size
                if offset % PAGE_SIZE:
                    print(f"   {name:<46} FAIL  ZIP data offset={offset} (need a multiple of {PAGE_SIZE})")
                    status = 1
                else:
                    print(f"   {name:<46} ok    ZIP data offset={offset}")

                with tempfile.TemporaryDirectory() as workdir:
                    library = Path(workdir) / "library.so"
                    with archive.open(entry) as source, library.open("wb") as target:
                        shutil.copyfileobj(source, target)
                    result = subprocess.run(
                        [readelf, "-lW", str(library)], capture_output=True, text=True,
                        env={**os.environ, "LC_ALL": "C"},
                    )
                if result.returncode:
                    print(f"   {name:<46} FAIL  cannot read ELF program headers: {result.stderr.strip()}")
                    status = 1
                    continue
                alignments = [
                    int(fields[-1], 0) for line in result.stdout.splitlines()
                    if (fields := line.split()) and fields[0] == "LOAD"
                ]
                if not alignments:
                    print(f"   {name:<46} FAIL  no PT_LOAD segments found")
                    status = 1
                elif min(alignments) < PAGE_SIZE:
                    print(f"   {name:<46} FAIL  LOAD p_align={min(alignments)} (need >= {PAGE_SIZE})")
                    status = 1
                else:
                    print(f"   {name:<46} ok    LOAD p_align={min(alignments)}")
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, EOFError) as error:
        print(f"   FAIL  cannot inspect APK: {error}")
        status = 1

if status:
    print("\nNot 16 KB compatible or could not be verified. Fix ZIP compression/offsets by repackaging")
    print("and aligning the APK before signing; fix ELF LOAD alignment by upgrading or rebuilding libraries.")
sys.exit(status)
PY
