# Release preparation verification

The first successful Android 16 preparation build is
[`cf65f856`](https://github.com/jschof1/wysawyg-android/commit/cf65f85692b86b2313f92008c0c5ec1566f301bb),
verified in [GitHub Actions run 40723903606](https://github.com/jschof1/wysawyg-android/actions/runs/40723903606)
on 28 October 2026.

| Check | Result |
| --- | --- |
| Debug unit tests | 65 passed; no failures, errors or skips |
| Release unit tests | 65 passed; no failures, errors or skips |
| Lint, both variants | No errors; 107 non-blocking warnings per variant remain in the reports |
| Model tests | 4 passed |
| Native bundle verifier tests | 5 passed |
| APK, device-test APK and release AAB compilation | Passed |
| Bundletool validation | Passed |
| Target, backup and network flags in actual AAB | API 36, backup disabled, cleartext disabled, not debuggable |
| Packaged native libraries | All four ARM64 ELF libraries have 16 KB alignment; bundle requests 16 KB APK zip alignment |
| Estimated compressed download | 202,223,091–202,274,404 bytes; below the current 500 MB base-module limit |
| Required model and policy assets | Present in the AAB |
| Release signing | Unsigned verification bundle; no upload key created |

Download the
[unsigned AAB](https://github.com/jschof1/wysawyg-android/actions/runs/40723903606/artifacts/12830969197),
[verification reports](https://github.com/jschof1/wysawyg-android/actions/runs/40723903606/artifacts/12832590910),
or [submission packet](https://github.com/jschof1/wysawyg-android/actions/runs/40723903606/artifacts/12832965959).
GitHub artifacts expire under the repository's retention settings; they are not
permanent release storage. Build the final signed bundle from the reviewed commit.

The public privacy policy returned HTTPS 200 and matched the source file exactly:
SHA-256 `0653f63d595b10721a652167147d3c8baeb41d73fa710c88444f24f7d5f31e0c`.
The prepared store icon and feature graphic were inspected visually and checked
for the required dimensions and colour formats.

These checks did not run Android UI or microphone tests. T3's Android device
integration was unavailable, so no fresh Android 15/16 handset or 16 KB runtime
test, screenshot capture, permission-review video, signed upload, or Play Console
review has been completed. CI compiles the device-test suite; it does not execute it.

Jack's remaining publication actions are account verification, final publisher
details, upload-key creation and backup, device acceptance and media capture,
Console declarations, and the test track required for the chosen account. Follow
the [release guide](README.md) before submission.
