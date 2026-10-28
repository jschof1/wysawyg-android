# Release preparation verification

The reviewed Android 16 preparation build is
[`f375c53`](https://github.com/jschof1/wysawyg-android/commit/f375c53cabccbf90ed21c1edca1eba418ba9061d),
verified in [GitHub Actions run 40727249944](https://github.com/jschof1/wysawyg-android/actions/runs/40727249944)
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
| Estimated compressed download | 202,222,956–202,273,968 bytes; below the current 500 MB base-module limit |
| Required model and policy assets | Present in the AAB |
| Release signing | Unsigned verification bundle; no upload key created |

Download the
[unsigned AAB](https://github.com/jschof1/wysawyg-android/actions/runs/40727249944/artifacts/12834340936),
[verification reports](https://github.com/jschof1/wysawyg-android/actions/runs/40727249944/artifacts/12833113296),
[device-test APK](https://github.com/jschof1/wysawyg-android/actions/runs/40727249944/artifacts/12833068172),
or [submission packet](https://github.com/jschof1/wysawyg-android/actions/runs/40727249944/artifacts/12833128167).
GitHub artifacts expire under the repository's retention settings; they are not
permanent release storage. Build the final signed bundle from the reviewed commit.

The public privacy policy returned HTTPS 200 and matched the source file exactly:
SHA-256 `d0fdc3873cbb7e4969dfafa26dcc6ed55a0fa84aeb38df99c7af43187e0ac33e`.
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
