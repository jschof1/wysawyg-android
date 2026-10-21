#!/usr/bin/env python3
"""Tie externally built JNI libraries to this checkout, not an unchecked binary folder."""
import hashlib
import json
import pathlib
import sys
ROOT = pathlib.Path(__file__).resolve().parent.parent
LIBS = ('libphonon_probe.so', 'libphonon_baseline.so', 'libphonon_dotprod.so')
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def fingerprint():
    files = sorted((ROOT / 'vendor/phonon2/csrc').glob('*.[ch]'))
    files += sorted((ROOT / 'app/src/main/cpp').glob('*'))
    files += [ROOT / 'scripts/build-phonon-native.sh', ROOT / 'scripts/native-manifest.py']
    digest = hashlib.sha256()
    for path in files:
        digest.update(str(path.relative_to(ROOT)).encode() + b'\0' + path.read_bytes())
    return digest.hexdigest()
def main():
    action, folder = sys.argv[1:]
    folder = pathlib.Path(folder)
    expected = dict(ndk='27.2.12479018', api=26, source=fingerprint(),
                    libraries={name: sha(folder / 'arm64-v8a' / name) for name in LIBS})
    target = folder / 'phonon-native.json'
    if action == 'write':
        target.write_text(json.dumps(expected, indent=2) + '\n')
    elif action == 'verify':
        if json.loads(target.read_text()) != expected:
            raise SystemExit('JNI provenance mismatch: rebuild with scripts/build-phonon-native.sh')
    else:
        raise SystemExit('usage: native-manifest.py write|verify JNI_DIRECTORY')
if __name__ == '__main__':
    main()
