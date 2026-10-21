#!/usr/bin/env python3
"""Explicit build-time fetch/verification; never used on the handset. Cached assets are always hashed."""
import argparse
import hashlib
import pathlib
import shutil
import tarfile
import tempfile
import urllib.request

REVISION = '1c91a6a7a26809fecedb35d847bef69663dde8bb'
ARCHIVE = 'phonon-2-c-weights-v1.tar.gz'
ARCHIVE_SHA = '48137a58011c4b0bc0a97087ea7e868daabf1525ca8447414be230b21faf99e6'
FILES = {
    'weights.bin': (255253016, '7dbcfd8ce258972cc5604f141ac7d64bbf5251c49bbc79c3e0c09b536e23fea5'),
    'weights.json': (93161, '37b0ee8bd7745b30b3c2298d5c7f40ec88f82c7e4995016d21d1b4db37a25397'),
    'tokens.txt': (54086, '04d756a9b6601bde84944f64609fb1d379f86731289ff083ecd9d68ddf5cdc2f'),
}
def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else _digest(stream)
def _digest(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()
def verify(folder):
    for name, (size, sha) in FILES.items():
        path = folder / name
        if not path.is_file() or path.stat().st_size != size or digest(path) != sha:
            raise ValueError(f'{path}: absent or mismatched pinned model asset')
def prepare(folder, download=False, archive=None):
    if archive is None: archive = folder.parent / ARCHIVE
    if archive.exists() and digest(archive) != ARCHIVE_SHA:
        raise ValueError(f'{archive}: pinned archive checksum mismatch')
    if all((folder / name).exists() for name in FILES):
        verify(folder)
        return
    if not download:
        verify(folder)
    folder.parent.mkdir(parents=True, exist_ok=True)
    if archive is None: archive = folder.parent / ARCHIVE
    if not archive.exists():
        part = archive.with_suffix('.part')
        try:
            urllib.request.urlretrieve(f'https://huggingface.co/eschmidbauer/phonon-2-c/resolve/{REVISION}/{ARCHIVE}', part)
            if digest(part) != ARCHIVE_SHA: raise ValueError('Downloaded archive checksum mismatch')
            part.replace(archive)
        finally:
            part.unlink(missing_ok=True)
    if digest(archive) != ARCHIVE_SHA: raise ValueError('Archive checksum mismatch')
    with tempfile.TemporaryDirectory(dir=folder.parent, prefix='phonon-verified-') as temp:
        staging = pathlib.Path(temp)
        with tarfile.open(archive, 'r:gz') as tar:
            for name in FILES:
                member = tar.getmember(f'c_weights/{name}')
                if not member.isfile() or member.size != FILES[name][0]: raise ValueError('Invalid model archive member')
                with tar.extractfile(member) as src, (staging / name).open('wb') as dst:
                    shutil.copyfileobj(src, dst)
        verify(staging)
        folder.mkdir(parents=True, exist_ok=True)
        for name in FILES: shutil.copyfile(staging / name, folder / name)
    verify(folder)
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=pathlib.Path, required=True)
    parser.add_argument('--archive', type=pathlib.Path)
    parser.add_argument('--download', action='store_true', help='Explicitly permit pinned build-time download')
    args = parser.parse_args()
    prepare(args.model_dir, args.download, args.archive)
    print(f'Verified three Phonon model assets: {args.model_dir}')
