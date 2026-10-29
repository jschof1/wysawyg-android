# Prepare the first Google Play release

Speakeasy 1.7.0 uses Play package `net.aspectstudio.speakeasy`, version code 12,
minimum Android 8/API 26, and target Android 16/API 36. It supports ARM64 phones.
Debug builds retain `com.cragnet.wysawyg` for the existing sideloaded app.

Jack owns the developer account and has authorised publication. The individual
account is registered and its USD 25 payment is confirmed. The live Console shows
**Aspect Studio Software** as the publisher. Identity, real Android device and
phone verification, the required test track and review are still outstanding.
A build passing here does not mean Google has approved the app or granted
production access.

See the [verification record](verification.md) for the built commit, downloadable
artifacts, test results and remaining acceptance checks.

## Build and check the bundle

The **Verify Android release** GitHub workflow runs on pushes to `master` and
pull requests. It builds an unsigned release AAB, debug APK, and instrumentation
APK. It runs debug and release unit tests, lint, model verification, and checks
every native library in the actual AAB for 16 KB alignment. Bundletool validates
the AAB and reports the generated compressed download size.

Download the `speakeasy-release-unsigned` and `release-verification` artifacts
from the matching workflow run. An unsigned AAB is for verification and cannot
be uploaded to Play.

The same run provides `wysawyg-debug-apk` and `speakeasy-device-test-apk` for an
isolated test device. The device-test APK is built in CI; it has not been executed
there. `PrivacyConsentTest` uses synthetic audio and local HTTP servers, temporarily
changes preferences and restores them. Pause floating dictation before running it.
`RecoveryControlsTest` additionally requires an empty recovery store, microphone
and overlay permissions, and prior acceptance of the in-app Accessibility disclosure.

To build locally, use JDK 17 or 21, Android SDK 36, SDK Build Tools 35.0.0, and
the native/model setup in [the offline build guide](../offline-transcription.md).
Check the Mac resource guard and verified external storage before building on
Jack's Mac. Do not build when the guard refuses heavy work.

```sh
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease bundleRelease
python3 scripts/verify-play-bundle.py \
  app/build/outputs/bundle/release/app-release.aab --bundletool /path/to/bundletool.jar
```

## Set up upload signing

Use Play App Signing when creating the app. Keep the upload key separate from
Google's app signing key. Back up the keystore and its passwords in durable,
private storage before relying on them. Do not commit keys, passwords, or encoded
keys.

An RSA-4096 upload key was created on 28 October 2026 in password-protected PKCS12
format. Its [public certificate](upload-certificate.pem) has SHA-256 fingerprint
`e49acf43a6592bc45dc2aceb5679cc56fa3f74fc24eba13408af9b78db35873e`.
The bundle verifier requires this certificate for a signed build.

The private key and credential record are in
`/Users/jack/.local/share/speakeasy-signing`, with access restricted to Jack's user.
Credentials were stored and read back from macOS Keychain under service
`net.aspectstudio.speakeasy.upload`, account `Aspect Studio`. An encrypted backup
is on Jack Drive in `Mac-Offload/Release-Key-Backups`; decryption and PKCS12 loading
were verified. Its GPG passphrase is the keystore password recorded in Keychain.
The backup contains the keystore and credentials. Keep it encrypted and preserve
the current key rather than generating a replacement during a retry.

These GitHub Actions secrets are configured on `jschof1/wysawyg-android`:

| Secret | Value |
| --- | --- |
| `SPEAKEASY_KEYSTORE_BASE64` | Base64 content of the upload keystore |
| `SPEAKEASY_KEYSTORE_PASSWORD` | Keystore password |
| `SPEAKEASY_KEY_ALIAS` | Upload key alias |
| `SPEAKEASY_KEY_PASSWORD` | Key password |

Run **Verify Android release** manually from `master`, with **signed_release**
enabled. Missing credentials fail the build. The workflow never falls back to a
debug signer. Download `speakeasy-release-signed`. Keep its commit SHA and the
verification reports with the release record. This workflow builds; it does not
upload or publish anything to Google Play.

For a local signed build, provide the matching `SPEAKEASY_KEYSTORE_PATH`,
`SPEAKEASY_KEYSTORE_PASSWORD`, `SPEAKEASY_KEY_ALIAS`, and `SPEAKEASY_KEY_PASSWORD`
environment variables through your secret manager. Set
`SPEAKEASY_REQUIRE_SIGNING=true`, then build `bundleRelease` and run the verifier
with `--require-signed`.

The Play build uses a separate package ID and installs alongside the existing
sideloaded debug app. It has its own settings, consent and recovery storage.
Preserve recovered text and needed settings before a deliberate migration. Debug
builds continue to update the existing app under `com.cragnet.wysawyg`.

## Register the app and complete the listing

Jack confirmed on 28 October 2026 that he has the original author's permission to
redistribute this fork of `cragnet/wysawyg-android` on Google Play. Keep that
permission with the release record. The speech-model and font licences remain
with their respective assets.

The Play package ID is `net.aspectstudio.speakeasy`, under Aspect Studio's domain.
Confirm its availability at the first Play upload. The Kotlin/native namespace
and debug package remain `com.cragnet.wysawyg`.

1. Complete the registered individual account's verification. Google's receipt
   confirms one USD 25 Developer Registration Fee purchase on 29 October 2026 at
   12:29:29 GMT, and the live Console opens the **Aspect Studio Software** personal
   account. Do not repeat the payment or start another registration. The dashboard
   requires identity verification, access to a real Android device through the
   Play Console mobile app, and phone verification after identity approval.
   **Create app is disabled until the account verifications are complete.** Jack
   completes identity documents directly inside Google; leave browser automation
   disconnected while he does so if the embedded verification panel stalls.
   Payment and dashboard evidence are kept privately. The earlier approved support
   case remains available if recovery is needed; do not submit another report
   without a current approved draft. See the verification record for the history.
   Google also displays the verified legal name, country and developer email for
   individual accounts. Check the [identity information requirements](https://support.google.com/googleplay/android-developer/answer/13628312)
   and verify the public profile before publication.
2. Create a free productivity app named **Speakeasy**. Confirm package
   `net.aspectstudio.speakeasy` is available before the first upload. The package name
   becomes permanent when registered with Play.
3. Use `info@aspectstudio.net` for public support and privacy enquiries. Jack
   confirmed this address on 28 October 2026.
4. Paste [the listing copy](store-listing.md) and
   [the declaration drafts](declarations.md) into the matching Console forms.
5. Use the public
   [privacy policy](https://raw.githubusercontent.com/jschof1/wysawyg-android/master/app/src/main/assets/privacy-policy.txt).
   This is the same text bundled in the app. Check that the URL opens without
   sign-in and that the contact address is correct. A branded HTML privacy page
   can replace it later if the Console requires a different format.
6. Use the prepared [store icon](graphics/store-icon.png) and
   [feature graphic](graphics/feature-graphic.png), and supply at least two current
   phone screenshots. Capture screenshots from the tested release, including its
   real setup state. Earlier images in `docs/design/speakeasy` are design evidence,
   not acceptance evidence for this release.
7. Upload the signed AAB to **Internal testing** first. Check Play's size,
   device compatibility, permission declarations, and pre-launch report.

## Run the release acceptance checks

Record device model, Android version, build SHA, and result for every run.
Test Android 15 and 16, including a 16 KB device or emulator. A verified ELF/AAB
structure alone does not prove runtime compatibility.

| Check | Expected result |
| --- | --- |
| Fresh install | Home loads; on-device mode is the default; privacy opens from Home and More settings |
| Accessibility decline or Back | No consent saved; no editor reads, overlay, or insertion |
| Return to Home after declining | No repeated or stacked disclosure; the setup row remains available |
| Enable service from Android settings first | Service remains inert until the separate in-app disclosure is accepted |
| Accessibility accept | System settings open after acceptance; Gboard remains selected; floating mic appears only with an eligible field and keyboard |
| Real speech | Start, Stop, and insertion work in the scratch editor and a real app; Cancel sends and inserts nothing |
| Password field and target change | Mic is hidden for password fields; a changed field receives no stale transcript |
| Cloud without consent | On-device processing; zero cloud requests |
| Cloud accept | Disclosure names the provider; only an explicitly finished recording reaches that provider |
| Change provider, switch mode during capture, import old settings | No recording is redirected to a new provider; imported settings use on-device mode |
| Withdraw consent | Accessibility stops reading and inserting; future cloud requests are blocked |
| Pause, reboot, update, process restart | Pause persists; an enabled service may resume idle; microphone never starts automatically |
| Background service start on Android 15/16 | No crash or repeated startup failure; the mic becomes available through a permitted start path |
| First run on Android 13+ | Notification permission is offered once after service startup; allow shows Pause in the shade, decline leaves Pause available on Home |
| System bars, keyboard, rotation, large text | All controls remain visible and usable in light and dark themes |
| Recovery | Saved text survives process death; Copy and Insert work; discard/expiry and storage failure are handled visibly |
| Optional keyboard | On-device recording and insertion work without Accessibility consent |
| Smart cleanup | Download is explicit; cleanup runs offline after download; model failure preserves words |

Film the Accessibility disclosure, decline, acceptance, Android permission flow,
and actual dictation for Google's permission review. Provide the video URL in the
Console. Also record microphone/special-use foreground service behaviour for its
declarations. No current release video or fresh target-36 device run exists yet.

## Reach production access

For a personal developer account created after 13 November 2023, Google currently
requires at least 12 testers opted into a closed test continuously for 14 days,
then an application for production access. Recruit real target users, collect
feedback, fix issues, and keep the evidence. An internal test does not replace
this requirement. Check the Console for requirements for the chosen account.
Use the [closed-test checklist and feedback record](closed-testing.md) to start
the test and retain evidence for the production-access application. No test
period or production eligibility is claimed until the Console confirms it.

## References

- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Personal account testing](https://support.google.com/googleplay/android-developer/answer/14151465)
- [AccessibilityService disclosure](https://support.google.com/googleplay/android-developer/answer/10964491)
- [User data and privacy policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [App size limits](https://support.google.com/googleplay/android-developer/answer/9859372)
- [Repository licensing](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)
- [Listing artwork requirements](https://support.google.com/googleplay/android-developer/answer/9866151)
- [16 KB page size support](https://developer.android.com/guide/practices/page-sizes)
