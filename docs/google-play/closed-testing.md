# First closed test

Speakeasy is being registered under an individual developer account. Google
requires at least 12 testers opted into a closed test continuously for 14 days
before the account can apply for production access. Meeting that minimum does
not itself grant production access or approve the app.

No test has started. The account is not registered, and no Play test link exists.

## Start the test

1. Finish account and identity verification, including Google's Android-device
   verification if requested in the Console.
2. Complete the internal upload and device acceptance checks in the
   [release guide](README.md). Capture real listing screenshots and the
   Accessibility and foreground-service review videos.
3. Create the closed track, select the supported countries and add real testers
   through Play Console. Keep their email addresses in the Console's tester
   list, rather than this public repository.
4. Submit the track for any required review. After it becomes available, copy
   the actual opt-in URL from the Console. An invitation or tester-list entry
   does not prove the person has opted in.
5. Record the time the Console shows at least 12 opted-in testers. Keep at least
   12 continuously opted in for the full required period and confirm eligibility
   in the Console before applying for production access. Ask testers to use the
   app during that period and collect their actual feedback.

Tester requirements: a Google account, a supported ARM64 Android phone running
Android 8 or later, and English speech. Include Android 15 and 16 devices in the
acceptance evidence. The public app is free; optional cloud providers have their
own terms and charges. The first test can be completed entirely on device.

## First real use

For each tested build, record the version, source SHA, phone model, Android
version, date and result. Preserve the tester's usual keyboard.

| Task | Evidence to collect |
| --- | --- |
| Open the app and its privacy policy | Both load; on-device mode is selected |
| Decline Accessibility, then return Home | No repeated disclosure or active text access |
| Accept the separate disclosure and enable floating dictation | Usual keyboard remains selected; mic appears for an editable field |
| Dictate a short note in the scratch editor and a real app | Start, Stop and insertion work; note accuracy and time taken |
| Cancel a recording | No text is inserted and no recording is sent |
| Move to another text field during recording | No stale transcript reaches the changed target; recovery is usable |
| Copy, insert and discard a recovered transcript | Each control works and retention is explained |
| Open an Android-identified password field | Floating mic is hidden |
| Pause, restart the app and restart the phone | Pause persists; recording never starts automatically |
| Decline text access and use the optional Speakeasy keyboard | On-device dictation and insertion still work |

Run the full release acceptance table as well. Cloud tests require a deliberately
configured HTTPS provider and separate consent; do not ask testers to share API
keys, recordings or private dictated text with the publisher.

## Feedback record

Collect these answers without requiring a recording or private message content:

- Phone model, Android version and Speakeasy version.
- Which keyboard and apps were used?
- Did the tester understand the permission choices and on-device default?
- Could they finish a real note or message using dictation?
- Were any words lost, inserted in the wrong place or difficult to recover?
- Did anything crash, hang or consume unexpected battery?
- What would prevent them from using the app again?

Record the issue, affected build, reproduction steps, fix and retest result.
Keep aggregate participation and feedback evidence for the production application.
Do not invent participation or infer successful use from installation counts.

## Apply for production access

After the Console confirms test eligibility, answer its production-access
questions from the real evidence: how testers were recruited, what they did,
what feedback they gave, what changed, the intended audience and why the app is
ready. If a question cannot be answered from the test record, resolve that gap
before submitting. Google reviews the application and may require more testing.

Production access, approval of the release and a publicly installable store
listing are separate steps. Verify the real Play URL on the public listing before
reporting the app as shareable.

Reference: [Google's personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).
