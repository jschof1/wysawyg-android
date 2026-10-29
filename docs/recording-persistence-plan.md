# Persistent dictation

Requested on 29 October 2026. A recording belongs to the dictation session,
independently of the keyboard, editor, or current screen.

## Behaviour

- Closing the keyboard or changing screens keeps recording and its indicator.
- Returning to an editor shows the same recording session.
- Stop finishes capture and starts transcription. Repeated Stop taps do nothing.
- Pausing floating dictation prevents new starts. An existing recording continues
  until an explicit recording control stops it.
- An interruption preserves captured audio. Recovery never restarts the
  microphone automatically or sends retained audio to a newly configured cloud
  provider.
- A changed editor cannot receive a stale automatic insertion. Completed text
  remains available for deliberate insertion or copying.
- Saved text stays available in the floating card when the keyboard closes. Insert
  here uses the current input at the moment of the tap; it never writes to the
  system clipboard. Copy remains an explicitly separate action.

## Reopening an editor

On Android 13 and later, explicit insertion uses the accessibility input
connection after checking its current editor type, foreground app, input epoch
and selection revision. It does not require the old accessibility node or an
open keyboard. Spacing comes from the current cursor surroundings, and only the
new text is committed. The focused-node path remains a compatibility fallback
when a current input connection cannot read text, including older Android.

The optional Speakeasy keyboard also offers Insert saved text without opening
Home. Both controls share a per-transcript insertion guard. A successful command
alone does not delete saved text: readback must confirm the full insertion. A
truncated or unconfirmed write retains the copy and asks the user to check the
field before trying again. There is no automatic second write after dispatch.
Automatic completion also distinguishes confirmed, unconfirmed and unsent text,
so a possible insertion is never presented as an invitation to insert afresh.

The old focused-window lookup plus keyboard visibility check could reject an
otherwise valid current input. Fixing only that lookup was considered, but would
still depend on apps exposing editable accessibility nodes. The explicit input
connection path removes that dependency while keeping automatic insertion tied
to the original recording destination.

## Ownership and durability

The existing foreground service owns capture for both the floating control and
the optional keyboard. A shared session exposes its phase to those controls.
Keeping one recorder owner avoids competing microphone lifetimes and uses the
existing notification and overlay infrastructure.

Audio goes to private storage while capture runs. Transcription reads bounded
segments, so recording duration does not set the in-memory audio size. Retained
audio is removed only after durable text staging or explicit discard. Segment
results are saved so a failed later segment does not lose earlier results.

A separate recording service was considered. It would separate presentation from
capture further, but adds another service connection and notification owner. The
existing service can serve both entry points without requiring overlay permission
for the optional keyboard.

## Verification

The 1.7.1 input update at `05fd59bb7dc0155fe64b02e89aca816e739fb0a7`
passed [Android CI](https://github.com/jschof1/wysawyg-android/actions/runs/40906816042):
98 tests in each of debug and release, no lint errors or fatal findings, and
successful APK, device-test APK and release-bundle compilation. The existing
109 lint warnings remain in each variant. The cached-only local runner also
passed 98 tests. Added device regressions cover closing and reopening the
keyboard, a missing editable accessibility node, password rejection, and
unconfirmed manual and automatic insertion. They are compiled, not executed;
the T3 Android device service still fails during setup.

Version 1.7.1 (13) was installed in place on the Samsung SM-A556E over wireless
debugging on 29 October 2026. The installed version and original install time
were verified; every file under `shared_prefs` and `no_backup` matched its
pre-update checksum. The installation receipt is on Jack Drive at
`Mac-Offload/Play-Releases/speakeasy-1.7.1-05fd59b/phone-install-20261029.json`.
Installation and data preservation are verified; the handset interaction tests
above remain unexecuted.

The base revision passed 65 JVM tests. The implementation passes 79 JVM tests
through the installed Kotlin compiler and JUnit, including durable audio and
line-break transcript recovery. The local Android SDK has API 34 while this project requires API 36. The
Mac resource guard also refuses heavy builds, so full Android verification uses
the existing GitHub Actions workflow.

Required regression checks cover durable audio reopen, interrupted writes,
recordings beyond the former five-minute limit, ordered segment recovery,
duplicate Stop, keyboard dismissal, editor navigation, and IME destruction.
Instrumented checks must exercise the real services and controls.

Review changes include a two-second microphone buffer, periodic power-loss
syncs with immediate file writes, and a size limit for optional model cleanup.
Long text keeps the normal formatter when it exceeds the cleanup model's budget.
Session creation and microphone startup run without a cancellable gap, so service
destruction cannot leave an unreported audio file from a queued creation step.

The T3 Android device inventory is unavailable in this session. Unit tests and
Android build success do not prove handset microphone or foreground-service
behaviour. Verify those paths on Android 15 and 16 before a Play release.

Android can stop the app without a lifecycle callback. Captured audio must already
be saved before that happens. See [Android's user-stop behaviour](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping).
