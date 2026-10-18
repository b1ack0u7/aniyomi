import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "check-16kb-alignment.sh"


def elf(alignments=(16384,)):
    ident = b"\x7fELF" + bytes([2, 1, 1]) + bytes(9)
    size = 64 + 56 * len(alignments)
    header = struct.pack(
        "<16sHHIQQQIHHHHHH", ident, 3, 183, 1, 0, 64, 0, 0,
        64, 56, len(alignments), 0, 0, 0,
    )
    return header + b"".join(
        struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, size, size, alignment)
        for alignment in alignments
    )


def write_apk(path, *, name="lib/arm64-v8a/libtest.so", data=None,
              alignment=16384, compressed=False, central_extra=True):
    with zipfile.ZipFile(path, "w") as archive:
        entry = zipfile.ZipInfo(name)
        entry.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
        padding = -(30 + len(name.encode("utf-8"))) % alignment
        if 0 < padding < 4:
            padding += alignment
        if padding:
            entry.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
        archive.writestr(entry, elf() if data is None else data)
        if not central_extra:
            entry.extra = b""


class CheckAlignmentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.apk = self.root / "test app.apk"

    def check(self, *apks, cwd=None, env=None):
        return subprocess.run(
            [str(SCRIPT), *map(str, apks)], cwd=cwd or self.root, env=env,
            capture_output=True, text=True,
        )

    def test_aligned_library_passes_for_both_64_bit_abis(self):
        for abi in ("arm64-v8a", "x86_64"):
            with self.subTest(abi=abi):
                write_apk(self.apk, name=f"lib/{abi}/libtest.so")
                result = self.check(self.apk)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("ZIP data offset=16384", result.stdout)
                self.assertIn("LOAD p_align=16384", result.stdout)

    def test_four_kb_zip_offset_fails_with_aligned_elf(self):
        write_apk(self.apk, alignment=4096)
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL  ZIP data offset=4096", result.stdout)
        self.assertIn("ok    LOAD p_align=16384", result.stdout)

    def test_local_extra_length_is_used_when_central_extra_is_empty(self):
        write_apk(self.apk, central_extra=False)
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ZIP data offset=16384", result.stdout)

    def test_library_name_with_spaces_and_unicode_uses_encoded_length(self):
        write_apk(self.apk, name="lib/arm64-v8a/lib test-ñ.so", central_extra=False)
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ZIP data offset=16384", result.stdout)

    def test_compressed_library_fails_even_when_offset_and_elf_are_aligned(self):
        write_apk(self.apk, compressed=True)
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL  compressed in the APK", result.stdout)

    def test_any_four_kb_load_segment_fails_with_aligned_zip(self):
        write_apk(self.apk, data=elf((16384, 4096)))
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn("ok    ZIP data offset=16384", result.stdout)
        self.assertIn("FAIL  LOAD p_align=4096", result.stdout)

    def test_invalid_elf_fails(self):
        write_apk(self.apk, data=b"not an ELF")
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL  cannot read ELF program headers", result.stdout)

    def test_no_load_segments_fails(self):
        write_apk(self.apk, data=elf(()))
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL  no PT_LOAD segments found", result.stdout)

    def test_32_bit_libraries_are_ignored(self):
        write_apk(self.apk, name="lib/armeabi-v7a/libtest.so", data=b"ignored", alignment=4096)
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 0)
        self.assertIn("no 64-bit native libraries", result.stdout)

    def test_invalid_and_missing_apks_fail_instead_of_passing_as_empty(self):
        for data in (b"not a ZIP", None):
            with self.subTest(data=data):
                if data is not None:
                    self.apk.write_bytes(data)
                else:
                    self.apk.unlink()
                result = self.check(self.apk)
                self.assertEqual(result.returncode, 1)
                self.assertIn("FAIL  cannot inspect APK", result.stdout)

    def test_invalid_local_header_fails_even_when_central_directory_is_valid(self):
        write_apk(self.apk)
        with self.apk.open("r+b") as archive:
            archive.write(b"BAD!")
        result = self.check(self.apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn("invalid local header", result.stdout)

    def test_failure_is_preserved_across_multiple_apks(self):
        write_apk(self.apk, alignment=4096)
        good_apk = self.root / "good.apk"
        write_apk(good_apk)
        result = self.check(self.apk, good_apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn(f"== {good_apk}", result.stdout)
        self.assertIn("ok    LOAD p_align=16384", result.stdout)

    def test_default_discovery_handles_spaces_in_apk_names(self):
        build = self.root / "app/build/outputs/apk/debug"
        build.mkdir(parents=True)
        write_apk(build / "test app.apk")
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("test app.apk", result.stdout)

    def test_no_apks_exits_with_usage_error(self):
        result = self.check()
        self.assertEqual(result.returncode, 2)
        self.assertIn("no APK given", result.stderr)

    def test_readelf_failure_is_reported(self):
        write_apk(self.apk)
        fake_readelf = self.root / "readelf"
        fake_readelf.write_text("#!/bin/sh\necho 'readelf failed' >&2\nexit 1\n")
        fake_readelf.chmod(0o755)
        result = self.check(self.apk, env={**os.environ, "PATH": f"{self.root}:{os.environ['PATH']}"})
        self.assertEqual(result.returncode, 1)
        self.assertIn("cannot read ELF program headers: readelf failed", result.stdout)


if __name__ == "__main__":
    unittest.main()
