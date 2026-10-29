# Release preparation verification

The signed Play build includes the confirmed public publisher name
**Aspect Studio Software** and passed the automated release checks.

The reviewed Android 16 preparation build is
[`0c9c623`](https://github.com/jschof1/wysawyg-android/commit/0c9c62337cf02ae86c2759224f4d0fbc4d5cef5c),
verified in [GitHub Actions run 40739284076](https://github.com/jschof1/wysawyg-android/actions/runs/40739284076)
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
| Estimated compressed download | 202,222,589–202,274,013 bytes; below the current 500 MB base-module limit |
| Required model and policy assets | Present in the AAB; policy exactly matches the built source |
| Release package | `net.aspectstudio.speakeasy` |
| Release signing | Signature verified; signed with the recorded upload certificate, not a debug certificate |

Download the
[signed AAB](https://github.com/jschof1/wysawyg-android/actions/runs/40739284076/artifacts/12837868981),
[verification reports](https://github.com/jschof1/wysawyg-android/actions/runs/40739284076/artifacts/12838841775),
[device-test APK](https://github.com/jschof1/wysawyg-android/actions/runs/40739284076/artifacts/12839591090),
or [submission packet](https://github.com/jschof1/wysawyg-android/actions/runs/40739284076/artifacts/12839840265).
GitHub artifacts expire under the repository's retention settings; they are not
permanent release storage. Preserve this signed build and its reports before
uploading to Play.

The signed AAB and both report/submission archives were preserved on Jack Drive
under `Mac-Offload/Play-Releases/speakeasy-1.7.0-0c9c623`. The upload file is
`speakeasy-1.7.0-upload.aab`, 202,862,386 bytes, SHA-256
`dbed8c6de48f50465b4e11d81677c6c366bb0a1b0a0b60f584f2af2de51c1f31`.
The downloaded artifact ZIP matched GitHub's SHA-256
`d86a7fc4aba93d87370dda46e6acfca63228287da270b605a118bc2e99db723d`.
Both ZIP CRCs, the AAB's policy and its embedded public certificate were checked
locally. Full signature verification ran in CI. A JSON receipt is beside the AAB.

The public privacy policy returned HTTPS 200 and matched the source file exactly:
SHA-256 `540e279ff151c8ed9396815a46869278d836cc2ad155066ee6afd79c994a83dc`.
The actual AAB and CI submission packet contain the same policy. The signature
uses the [recorded public upload certificate](upload-certificate.pem), SHA-256
`e49acf43a6592bc45dc2aceb5679cc56fa3f74fc24eba13408af9b78db35873e`.
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

The regular browser is signed into the intended owner's Google account. On
29 October 2026, Google showed both Payments profile and Public profile as
completed after Jack linked the profile directly. Jack confirmed no other Play
Console accounts in the past six months. About you, Apps and How Google contacts
you were completed in that registration attempt. The current app plan is one app,
with future monetisation undecided and none of Google's listed specialist app
categories selected.
Jack approved both registration agreements and the one-time USD 25 fee on
29 October 2026. The two required agreement boxes were checked and Create account
and pay was clicked; the optional feedback/announcement boxes remain unchecked.
Google's payment dialog stayed on a loading spinner. Closing it left Back and
Create account and pay disabled. A fresh Console tab returned to signup, and the
profile selector also stalled in that fresh session. Browser diagnostics were
unavailable while Browser Use was resolving a paused document response; the
underlying cause was not established. Jack subsequently reported that direct
checkout also stalled and signup returned to the beginning. The individual route
and approved developer name were restored, but the embedded payments-profile
selector again remained blank. The Google Payments homepage loaded normally for
the same account; opening the observed profile-selector URL separately displayed
the existing individual profile, but its controls did not complete the flow.
Captured cookie exclusions were only DomainMismatch and NotOnPath, which do not
establish third-party-cookie blocking. Native browser input did not resolve the
stalled widget. No browser protections or account data were changed.

Google's legacy registration-support link redirected to signup. The Help Centre's
general contact route reached the working preregistration form at
`https://play.google.com/console/u/0/help`. Jack approved the exact report and both
screenshots in the current chat on 29 October 2026. The message matched the approved
draft before submission; both `payments-profile-blank.png` and
`registration-checkout-stalled.png` finished uploading. Google then displayed
Ticket submitted and stated that it will reply by email within two working days,
with some tickets requiring longer. The confirmation showed the intended account
contact, but no case reference. The receipt screenshot and exact report are kept
privately with the release evidence, outside the repository. Its email
acknowledgement to the owner subsequently supplied a case reference, also stored
privately. No second report was submitted. Check for Google's reply by
2 November 2026 if recovery remains blocked. Ticket acceptance does not establish
a resolved registration.

Jack's later screenshot and the live page showed both profile steps completed
again, with About you in progress. The existing signup was resumed from that step;
it was not a second developer account. About you, Apps and contact details were
restored from the already-confirmed answers. The required agreements were applied
under the existing approval, and the same USD 25 checkout was resumed. Every
preceding step is complete in this form, but the payment iframe is blank again.
The form is retained at Terms. Native browser recovery was interrupted by changes
to the selected browser view; it did not establish that the automation connection
was detached. Jack then cancelled the tab's ChatGPT debugging banner and reported
that the payment form opens, but is not yet paid. Browser automation is being
kept disconnected while he completes checkout and any bank verification directly
in Google. This supports automation involvement in the stall; the exact technical
cause remains unproved. Payment, account creation and the Console dashboard still
need confirmation. Resume from that result without restarting signup.

Google Payments subsequently confirmed the Developer Registration Fee purchase:
USD 25 total, with purchase time 29 October 2026 at 12:29:29 GMT. The receipt was
read in the intended owner's mailbox and is recorded privately without card
details. Jack's later message that the purchase was already completed matches
that receipt. Do not pay again or start another registration.

A fresh Console tab opened the account chooser and then the live
**Aspect Studio Software** personal-account dashboard. Registration is complete.
The dashboard requires identity verification, verification of access to a real
Android device through the Play Console mobile app, and contact-phone verification
after Google's identity approval. **Create app is disabled until account
verifications are complete.** Identity verification was opened for Jack to complete
directly inside Google. The dashboard screenshot and payment receipt metadata are
private release evidence; no identity documents were collected by the agent.

Remaining actions are identity, Android device and phone verification, first upload
of the chosen public package ID, app device acceptance and media capture,
Console declarations, and the individual account's closed test and
production-access application. Follow the
[release guide](README.md) before submission.
