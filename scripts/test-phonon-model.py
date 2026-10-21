#!/usr/bin/env python3
"""Fast fail-closed cache tests; never downloads or opens the real model."""
import hashlib
import importlib.util
import pathlib
import tempfile
import unittest
spec = importlib.util.spec_from_file_location('model', pathlib.Path(__file__).with_name('prepare-phonon-model.py'))
model = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model)
class ModelCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = pathlib.Path(self.temp.name) / 'c_weights'
        self.directory.mkdir()
        old = model.FILES
        self.addCleanup(setattr, model, 'FILES', old)
        model.FILES = {'weights.bin': (4, hashlib.sha256(b'test').hexdigest())}
    def test_absent_fails_without_download(self):
        with self.assertRaises(ValueError): model.prepare(self.directory)
    def test_cached_wrong_hash_fails(self):
        (self.directory / 'weights.bin').write_bytes(b'bad!')
        with self.assertRaises(ValueError): model.prepare(self.directory, download=True)
    def test_cached_right_size_and_hash_pass(self):
        (self.directory / 'weights.bin').write_bytes(b'test')
        model.prepare(self.directory)
    def test_cached_archive_verified_even_when_assets_already_exist(self):
        (self.directory / 'weights.bin').write_bytes(b'test')
        (self.directory.parent / model.ARCHIVE).write_bytes(b'bad archive')
        with self.assertRaises(ValueError): model.prepare(self.directory)
if __name__ == '__main__': unittest.main()
