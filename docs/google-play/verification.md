# Release preparation verification

The reviewed Android 16 preparation build is
[`e79b4ae`](https://github.com/jschof1/wysawyg-android/commit/e79b4ae3612f3496810ba55d097b37de655590cc),
verified in [GitHub Actions run 40731035375](https://github.com/jschof1/wysawyg-android/actions/runs/40731035375)
on 28 October 2026.

| Check | Result |
| --- | --- |
| Debug unit tests | 65 passed; no failures, errors or skips |
| Release unit tests | 65 passed; no failures, errors or skips |
| Lint, both variants | No errors; 108 non-blocking warnings per variant remain in the reports |
| Model tests | 4 passed |
| Native bundle verifier tests | 5 passed |
| APK, device-test APK and release AAB compilation | Passed |
| Bundletool validation | Passed |
| Target, backup and network flags in actual AAB | API 36, backup disabled, cleartext disabled, not debuggable |
| Packaged native libraries | All four ARM64 ELF libraries have 16 KB alignment; bundle requests 16 KB APK zip alignment |
| Estimated compressed download | 202,222,960–202,273,989 bytes; below the current 500 MB base-module limit |
| Required model and policy assets | Present in the AAB |
| Release signing | Unsigned verification bundle; no upload key created |

Download the
[unsigned AAB](https://github.com/jschof1/wysawyg-android/actions/runs/40731035375/artifacts/12834737673),
[verification reports](https://github.com/jschof1/wysawyg-android/actions/runs/40731035375/artifacts/12835940431),
[device-test APK](https://github.com/jschof1/wysawyg-android/actions/runs/40731035375/artifacts/12835845191),
or [submission packet](https://github.com/jschof1/wysawyg-android/actions/runs/40731035375/artifacts/12834729451).
GitHub artifacts expire under the repository's retention settings; they are not
permanent release storage. Build the final signed bundle from the reviewed commit.

The public privacy policy returned HTTPS 200 and matched the source file exactly:
SHA-256 `85d8ea2adaa92054290772f1c6913f42e197c778994f1a68e3adb7ea1ffccee8`.
The CI submission packet contains this same policy and the confirmed publisher,
support address, and redistribution permission record.
The prepared store icon and feature graphic were inspected visually and checked
for the required dimensions and colour formats.

The independent Claude review found no consent bypass. Its code findings were
addressed in this build: no automatic redisplay of an unaccepted Accessibility
disclosure, one-time notification permission setup after service startup, generic
import errors, private destination-copy visibility, and a mandatory capture-time
backend. The router remains in production only to choose the backend at capture.

These checks did not run Android UI or microphone tests. T3's Android device
integration was unavailable, so no fresh Android 15/16 handset or 16 KB runtime
test, screenshot capture, permission-review video, signed upload, or Play Console
review has been completed. CI compiles the device-test suite; it does not execute it.

Jack confirmed **Aspect Studio** as publisher and `info@aspectstudio.net` as the
public support and privacy address on 28 October 2026. He also confirmed that he
has the original author's permission to redistribute this fork of
`cragnet/wysawyg-android` on Google Play. These confirmations are recorded in
[the decision trail](decisions.tsv).

Remaining actions are account verification, final public package ID,
upload-key creation and backup, device acceptance and media capture, Console
declarations, and the test track required for the chosen account. Follow the
[release guide](README.md) before submission.
