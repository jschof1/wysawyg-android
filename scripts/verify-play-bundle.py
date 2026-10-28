#!/usr/bin/env python3
"""Check the actual release bundle, including every packaged ELF library."""
import argparse
import csv
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"


def check(condition, message):
    if not condition:
        raise ValueError(message)
    print(f"PASS {message}")


def verify_elf(data, name):
    check(data[:6] == b"\x7fELF\x02\x01", f"{name} is a 64-bit little-endian ELF")
    check(struct.unpack_from("<H", data, 18)[0] == 183, f"{name} contains ARM64 machine code")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    loads = [struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
             for index in range(count)
             if struct.unpack_from("<I", data, offset + index * size)[0] == 1]
    check(bool(loads) and all(segment[-1] >= 16384 and
          segment[2] % 16384 == segment[3] % 16384 for segment in loads),
          f"{name} supports 16 KB ELF alignment")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle")
    parser.add_argument("--bundletool", required=True)
    parser.add_argument("--require-signed", action="store_true")
    parser.add_argument("--download-size")
    args = parser.parse_args()

    def bundletool(*command):
        return subprocess.check_output(["java", "-jar", args.bundletool, *command,
                                        f"--bundle={args.bundle}"], text=True)

    bundletool("validate")
    print("PASS bundletool validates the release structure")
    manifest = ET.fromstring(bundletool("dump", "manifest", "--module=base"))
    sdk = manifest.find("uses-sdk")
    app = manifest.find("application")
    check(manifest.get("package") == "com.cragnet.wysawyg", "package identity is preserved")
    check(sdk is not None and int(sdk.get(ANDROID + "targetSdkVersion", "0")) >= 36,
          "release targets Android 16 or higher")
    check(app is not None and app.get(ANDROID + "debuggable", "false") == "false",
          "release is not debuggable")
    check(app.get(ANDROID + "allowBackup") == "false", "automatic backup is disabled")
    check(app.get(ANDROID + "usesCleartextTraffic") == "false", "cleartext traffic is disabled")
    permissions = {node.get(ANDROID + "name") for node in manifest.findall("uses-permission")}
    check("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in permissions,
          "no direct battery exemption request")
    config = bundletool("dump", "config")
    check("PAGE_ALIGNMENT_16K" in config, "Play-generated APKs request 16 KB zip alignment")
    if args.download_size:
        with open(args.download_size, newline="") as stream:
            sizes = list(csv.DictReader(stream))
        check(bool(sizes) and all(0 < int(row["MAX"]) <= 500_000_000 for row in sizes),
              "bundletool estimated download fits the 500 MB base module limit")

    with zipfile.ZipFile(args.bundle) as bundle:
        names = bundle.namelist()
        libraries = [name for name in names if name.startswith("base/lib/") and name.endswith(".so")]
        check(bool(libraries), "native speech libraries are packaged")
        check(all(name.startswith("base/lib/arm64-v8a/") for name in libraries), "only supported ARM64 ABI is packaged")
        for name in libraries:
            verify_elf(bundle.read(name), name)
        for asset in ("phonon/weights.bin", "phonon/weights.json", "phonon/tokens.txt", "privacy-policy.txt"):
            check(f"base/assets/{asset}" in names, f"{asset} is packaged")
        signed = any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", name) for name in names)

    if signed:
        verification = subprocess.check_output(["jarsigner", "-verify", args.bundle], text=True)
        check("jar verified" in verification.lower(), "bundle signature verifies")
        cert = subprocess.check_output(["keytool", "-printcert", "-jarfile", args.bundle], text=True)
        check("android debug" not in cert.lower() and "androiddebugkey" not in cert.lower(),
              "bundle is not signed with an Android debug certificate")
    elif args.require_signed:
        raise ValueError("Upload requires a signed AAB. Configure the upload key and rebuild.")
    else:
        print("UNSIGNED: verification build only; configure upload signing before Play submission.")
    print("Bundle structure verified. Device testing and Play policy approval remain separate gates.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError, struct.error, subprocess.CalledProcessError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
