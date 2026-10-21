#!/usr/bin/env python3
"""Verify an installed debug build recovers from process death without opening an activity."""

import argparse
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]

    def run(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=10).strip()

    def overlay():
        services = run("shell", "dumpsys", "activity", "services", "com.cragnet.wysawyg")
        records = re.split(r"(?=  \* ServiceRecord)", services)
        return next((record for record in records if "/.OverlayService " in record), "")

    before = overlay()
    assert "isForeground=true" in before, "Enable floating dictation before running this check"
    types = int(re.search(r"types=0x([0-9a-fA-F]+)", before).group(1), 16)
    assert types & 0x80 == 0, "Finish recording before running this check"
    pid = run("shell", "pidof", "com.cragnet.wysawyg")
    assert pid.isdigit(), "Expected one WYSAWYG process"
    print(f"Terminating WYSAWYG process {pid}", flush=True)
    run("shell", "run-as", "com.cragnet.wysawyg", "kill", "-9", pid)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        process = subprocess.run(adb + ["shell", "pidof", "com.cragnet.wysawyg"],
                                 capture_output=True, text=True, timeout=10)
        recovered_pid = process.stdout.strip()
        current = overlay()
        attached = recovered_pid.isdigit() and recovered_pid != pid and re.search(
            rf"app=ProcessRecord\{{[^\n]*\b{recovered_pid}:com\.cragnet\.wysawyg/", current)
        if attached and "isForeground=true" in current and "startCommandResult=1" in current:
            types = int(re.search(r"types=0x([0-9a-fA-F]+)", current).group(1), 16)
            assert types & 0x80 == 0, "Recovery must not resume microphone access"
            print(f"PASS: foreground dictation recovered in new process {recovered_pid} without opening an activity; microphone remains idle")
            return
        time.sleep(1)
    raise AssertionError("Floating dictation did not recover within 45 seconds")


if __name__ == "__main__":
    main()
