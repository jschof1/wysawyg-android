#!/usr/bin/env python3
import contextlib
import io
import runpy
import struct
import unittest
from pathlib import Path

verify_elf = runpy.run_path(str(Path(__file__).with_name("verify-play-bundle.py")))["verify_elf"]


def elf(alignment=16384, address=16384, machine=183):
    data = bytearray(128)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, address, address, 128, 128, alignment)
    return data


class NativeArtifactChecks(unittest.TestCase):
    def verify(self, data):
        with contextlib.redirect_stdout(io.StringIO()):
            verify_elf(data, "fixture.so")

    def test_accepts_16k_and_64k_segments(self):
        self.verify(elf())
        self.verify(elf(alignment=65536, address=65536))

    def test_rejects_4k_only_library(self):
        with self.assertRaises(ValueError):
            self.verify(elf(alignment=4096))

    def test_rejects_incompatible_segment_offset(self):
        with self.assertRaises(ValueError):
            self.verify(elf(address=4096))

    def test_rejects_wrong_architecture(self):
        with self.assertRaises(ValueError):
            self.verify(elf(machine=62))

    def test_rejects_truncated_library(self):
        with self.assertRaises((ValueError, struct.error)):
            self.verify(elf()[:80])


if __name__ == "__main__":
    unittest.main()
