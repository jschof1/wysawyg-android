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
