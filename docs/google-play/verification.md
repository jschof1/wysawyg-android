# Release preparation verification

The first signed Play build passed. A replacement is required to bundle the new
public publisher name **Aspect Studio Software**, confirmed after that build.
The results below describe the signed build before this policy-only update.

The reviewed Android 16 preparation build is
[`7bca495`](https://github.com/jschof1/wysawyg-android/commit/7bca4951136e50c7af1f2aa1781324d8c034457b),
verified in [GitHub Actions run 40736863224](https://github.com/jschof1/wysawyg-android/actions/runs/40736863224)
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
| Estimated compressed download | 202,222,584–202,274,018 bytes; below the current 500 MB base-module limit |
| Required model and policy assets | Present in the AAB; policy exactly matches the built source |
| Release package | `net.aspectstudio.speakeasy` |
| Release signing | Signature verified; signed with the recorded upload certificate, not a debug certificate |

Download the
[signed AAB](https://github.com/jschof1/wysawyg-android/actions/runs/40736863224/artifacts/12838245752),
[verification reports](https://github.com/jschof1/wysawyg-android/actions/runs/40736863224/artifacts/12837353146),
[device-test APK](https://github.com/jschof1/wysawyg-android/actions/runs/40736863224/artifacts/12838595914),
or [submission packet](https://github.com/jschof1/wysawyg-android/actions/runs/40736863224/artifacts/12838011475).
GitHub artifacts expire under the repository's retention settings; they are not
permanent release storage. Use the replacement signed build with the confirmed
publisher before uploading to Play.

The public privacy policy returned HTTPS 200 and matched the source file exactly:
SHA-256 `af7bd6e9584f29a4fea4009e2b8bac1c5ed43e360cd4713988179e9e34d3c849`.
That policy predates the new public developer name. Recheck the public URL and
replacement AAB after the publisher update is pushed.
The prepared store icon and feature graphic were inspected visually and checked
for the required dimensions and colour formats.

The independent Claude review found no consent bypass. Its code findings were
addressed in this build: no automatic redisplay of an unaccepted Accessibility
disclosure, one-time notification permission setup after service startup, generic
import errors, private destination-copy visibility, and a mandatory capture-time
backend. The router remains in production only to choose the backend at capture.

These checks did not run Android UI or microphone tests. T3's Android device
integration was unavailable, so no fresh Android 15/16 handset or 16 KB runtime
test, screenshot capture, permission-review video, Play upload, or Play Console
review has been completed. CI compiles the device-test suite; it does not execute it.

Jack confirmed **Aspect Studio Software** as the public developer name after
Google rejected **Aspect Studio** as already registered. He confirmed that he is
a sole trader; the unsubmitted registration was changed to the individual route.
`info@aspectstudio.net` remains the public support and privacy address. He also
confirmed that he has the original author's permission to redistribute this fork of
`cragnet/wysawyg-android` on Google Play. These confirmations are recorded in
[the decision trail](decisions.tsv).

The regular browser is signed into the intended owner's Google account.
Remaining actions are account registration and identity verification, verification
of the replacement signed bundle, first upload of the chosen public package ID,
device acceptance and media capture, Console declarations, and the individual
account's closed test and production-access application. Follow the
[release guide](README.md) before submission.
