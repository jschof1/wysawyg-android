# WYSAWYG 1.3.0 verification

Checked on 20 October 2026 using a Samsung A55 running Android 15 with Gboard.
Handset screenshots and raw logs are local artifacts outside the repository.

## Build and automated checks

With JDK 21, Android SDK 34 and the existing Gradle cache:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline
```

The build and both APKs passed. All 20 JVM tests passed. Lint reported no errors
and 71 warnings.

| Device suite | Result |
| --- | --- |
| `RecoveryControlsTest` | Five scenarios passed with a loopback transcription server. |
| `RecoverySettingsTest` | Unsaved text was made durable through Retry saving while floating dictation remained paused. |
| `BackgroundLifecycleTest` | Three scenarios passed, including accessibility reconnect, Pause and microphone foreground state. |
| `DictationIntegrationTest` | Nine local scenarios passed in the final targeted run. ChatGPT and WhatsApp opt-in scenarios were skipped. |

The combined 19-scenario run exposed a pre-existing readiness failure in
`excludesUnmarkedHintUsingActualInputBuffer` after the fixture changed editor text.
It passed alone and again in the final 12-scenario Settings/editor run, which had
no failures and two opt-in skips. The test now waits for a safe target instead of
requiring immediate input-connection availability. This does not establish that
the combined-suite ordering issue is eliminated.

## Handset behavior

The new recording tests used the real microphone and a local HTTP server. The
timer advanced, the PCM level widget appeared, and Cancel produced no request.
Stop released microphone foreground state before HTTP. Keyboard hide and Pause
also cancelled capture without a request.

Changed-target insertion retained the exact response without altering either
editor or the clipboard. Copy retained the transcript; fresh Insert here inserted
at the new destination and removed it only after success. Later collapsed the
panel, kept the transcript and allowed it to be reopened. Password fields hid the
panel. A response completed during Pause was saved without insertion.

The Settings retry test used an isolated store whose destination initially could
not be written. After storage became available, Retry saving persisted the full
text. The test restored the original repository singleton and overlay preference.

Portrait screenshots were inspected for recording, expanded/collapsed recovery,
recovery after process death and the Settings retry dialog. Labels and controls
fit above Gboard at the handset's existing font and display settings.

## Actual process-death check

The loopback recovery scenario left one known synthetic transcript using:

```sh
adb -s SERIAL shell am instrument -w -r \
  -e retainRecoveryForRestart true \
  -e class com.cragnet.wysawyg.RecoveryControlsTest#passwordHidesRecoveryAndPauseRestartRestoresSavedTranscript \
  com.cragnet.wysawyg.test/androidx.test.runner.AndroidJUnitRunner
```

After instrumentation ended, WYSAWYG's existing accessibility binding was restored
without changing the other enabled services. With Samsung Settings search and
Gboard open, the normal app process was then terminated using:

```sh
python3 scripts/verify-background.py --serial SERIAL
```

The script observed a different live PID with an attached foreground overlay
service. Microphone foreground state remained idle. The recovery file hash was
unchanged, and the complete synthetic transcript reappeared beside the Settings
search field without opening a WYSAWYG activity or inserting automatically. The
synthetic item was then removed through Discard.

Normal tests remove their synthetic recovery text. Use the retain argument only
for this individual host-controlled probe, then explicitly discard the fixture.

## Review and boundaries

Independent reviews identified four P2 issues. Cancel now skips WAV encoding,
recovery can collapse/reopen, the optional IME warns about failed removal after
insertion, and Settings can retry saving nonempty unsaved recoveries. Follow-up
review found no remaining P1/P2 in those fixes. One narrating test comment was
removed; no comment constraints remain open.

The optional IME's failed-removal warning was reviewed statically; the selected
keyboard stayed Gboard. Large-font/landscape layouts, real provider transcription,
reboot/unlock and prolonged OEM battery management were not
tested in this pass. Force-stop still requires an explicit launch. Disk failures
retain text in memory and cannot promise survival across process death until a
save succeeds. Saved items expire after 24 hours.

## Reconnection and installed-build check, 21 October 2026

The A55 reconnected with v1.3.0 running as an idle foreground service. Preferences
matched the backup taken before the UX update. Gboard remained selected, and all
six enabled accessibility services were unchanged.

`assembleDebug --offline` passed in the canonical GitHub checkout at `2853065`.
The exact resulting APK was installed with `adb install -r`, retaining app data.
Its SHA-256 matched the installed package:

```text
8dbc243d3dd8d3ea3c5e4fcea7fd6634c8bcccbab2efe5ed4ec8f806c8bba21f
```

After this same-version package replacement, the accessibility and foreground
overlay services returned without launching WYSAWYG or resetting their binding.
The process-death script then terminated PID 27210 and observed the overlay
attached to the new live PID 27547. The service was foreground and sticky, with
microphone foreground state idle.

Final readback confirmed unchanged preferences, Gboard and accessibility services.
Microphone and notification permissions, overlay access and the battery exemption
remained enabled. No new instrumentation run was needed.

After the phone was unlocked, the bubble appeared beside Samsung Settings search
with Gboard open. A recording screenshot showed the timer at `00:02`, the level
widget and separate Stop and Cancel buttons. All controls fit above Gboard.
Cancel returned to the idle bubble and removed microphone foreground state.

A second short ambient recording checked Stop. Processing completed, punctuation
appeared in the test field, and the service returned to idle with microphone
foreground state removed. Android's microphone operation had a completed duration.
The test input was cleared, recovery storage contained zero items, and preferences,
Gboard and accessibility services remained unchanged. Speech accuracy with a
deliberately spoken test phrase remains unverified.
