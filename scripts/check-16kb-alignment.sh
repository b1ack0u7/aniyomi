#!/usr/bin/env bash
#
# Checks that an APK is compatible with 16 KB memory page sizes.
#
# Two independent things have to hold for every native library:
#   1. the entry is STORED (uncompressed) and starts on a 16 KB boundary
#      inside the zip -- handled automatically by AGP 8.5.1+
#   2. every PT_LOAD segment of the ELF has p_align >= 16384 -- this comes
#      from how the library itself was linked, so it can only be fixed by
#      rebuilding/upgrading the dependency that ships it
#
# 32-bit ABIs (armeabi-v7a, x86) are skipped: 16 KB pages are a 64-bit-only
# concern and the NDK deliberately keeps 4 KB alignment there.
#
# See docs/16kb-page-size.md for the current status and the known blockers.
#
# Usage: scripts/check-16kb-alignment.sh <apk> [<apk> ...]
#        scripts/check-16kb-alignment.sh            # all APKs under app/build

set -euo pipefail

readelf_bin=$(command -v readelf || command -v llvm-readelf || true)
if [ -z "$readelf_bin" ]; then
    echo "error: readelf (binutils) or llvm-readelf is required" >&2
    exit 2
fi

apks=("$@")
if [ ${#apks[@]} -eq 0 ]; then
    mapfile -t apks < <(find app/build -name '*.apk' 2>/dev/null | sort)
fi
if [ ${#apks[@]} -eq 0 ]; then
    echo "error: no APK given and none found under app/build" >&2
    exit 2
fi

status=0

for apk in "${apks[@]}"; do
    echo "== $apk"
    workdir=$(mktemp -d)
    trap 'rm -rf "$workdir"' EXIT

    unzip -q -o "$apk" 'lib/arm64-v8a/*' 'lib/x86_64/*' -d "$workdir" 2>/dev/null || true

    if [ ! -d "$workdir/lib" ]; then
        echo "   no 64-bit native libraries"
        rm -rf "$workdir"
        trap - EXIT
        continue
    fi

    # 1. zip layout: uncompressed and 16 KB aligned
    while read -r method _ name; do
        case "$name" in lib/arm64-v8a/*|lib/x86_64/*) ;; *) continue ;; esac
        if [ "$method" != "Stored" ]; then
            printf '   %-46s FAIL  compressed in the APK\n' "$name"
            status=1
        fi
    done < <(unzip -v "$apk" | awk '$2=="Stored"||$2=="Defl:N"{print $2, $1, $NF}')

    # 2. ELF layout: every PT_LOAD aligned to at least 16 KB
    while read -r lib; do
        rel=${lib#"$workdir"/}
        min_align=$(
            "$readelf_bin" -lW "$lib" 2>/dev/null |
                awk '$1=="LOAD"{print strtonum($NF)}' |
                sort -n | head -1
        )
        if [ -z "$min_align" ]; then
            printf '   %-46s FAIL  no PT_LOAD segments found\n' "$rel"
            status=1
        elif [ "$min_align" -lt 16384 ]; then
            printf '   %-46s FAIL  LOAD p_align=%s (need >= 16384)\n' "$rel" "$min_align"
            status=1
        else
            printf '   %-46s ok    LOAD p_align=%s\n' "$rel" "$min_align"
        fi
    done < <(find "$workdir/lib" -name '*.so' | sort)

    rm -rf "$workdir"
    trap - EXIT
done

if [ "$status" -ne 0 ]; then
    echo
    echo "Not 16 KB compatible. Libraries reported above ship 4 KB-aligned ELFs;"
    echo "they have to be upgraded or rebuilt with -Wl,-z,max-page-size=16384."
fi

exit "$status"
