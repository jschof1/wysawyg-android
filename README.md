# Speakeasy — Voice-to-Text for Android

Speakeasy (formerly WYSAWYG) is a voice-to-text app for Android. Its floating microphone works with your existing keyboard; a custom keyboard IME is optional. English speech is transcribed on the phone using bundled Phonon 2 by default, then inserted at the cursor. No API setup or model download is needed. Optional cloud transcription lives in More settings. Failed insertion can be recovered explicitly.

## Current recording behaviour (29 October 2026)

- Closing the keyboard, changing editors/screens, or destroying the optional IME keeps the same recording running.
- The existing foreground service owns one session for both controls. Its notification offers **Stop**; the floating indicator stays visible when overlay permission is available. IME-only recording requires notifications when overlays are unavailable.
- **Stop** ends capture and transcribes. **Stop & keep** (also holding Stop) ends capture without transcription and retains the audio. **Pause** prevents future floating starts; an active session continues until Stop.
- Capture streams 16 kHz mono PCM to private `noBackupFilesDir/recordings` storage, without a total duration cutoff. One-minute transcription segments bound audio memory and stay below the native engine's per-request limit.
- OS/read/storage interruption preserves the written prefix. No automatic microphone restart or recovery upload occurs. Home → **Recovered transcripts** exposes saved audio, **Retry on device**, and confirmed **Discard audio**, including without overlay permission.
- Completed segment text is checkpointed. Audio is removed only after the complete transcript is durably staged or explicitly discarded. New recording is blocked while audio remains pending.
- A changed or hidden editor invalidates automatic insertion. Text stays available for deliberate insertion/copy. Retry always uses on-device transcription, regardless of current cloud settings; a normal Stop retains the provider frozen when capture began and its consent checks.
- Android may stop or force-stop the app. Recording cannot continue through process death; reopening exposes the audio already written, without restarting the microphone. Uninstalling/clearing app data removes recovery.

Implementation and verification scope: [persistent dictation](docs/recording-persistence-plan.md). Historical release claims below about cancellation and five-minute capture limits are superseded by this section. Handset checks for the changed lifecycle remain pending.

## Version 1.7.0 (code 12)

Prepares an Android 16 release bundle for Google Play. Floating dictation now
requires a separate Accessibility disclosure and consent, including when the
service was enabled outside the app. Optional cloud uploads require consent for
the configured provider and HTTPS in release builds. Without consent, dictation
uses the on-device engine. A recording keeps the provider configuration selected
before capture; settings changes cannot redirect it. Cloud redirects are refused.

Privacy and permissions are available from Home and More settings. Automatic
backup and device transfer of app data are disabled. Settings exports omit API
keys, and imports always select on-device transcription. Existing credentials and
recoveries stay on the phone. Release logcat output is reduced and copied
recoveries are marked sensitive.

See [Google Play release preparation](docs/google-play/README.md) for the
unsigned/signed build workflow, privacy policy, listing, declaration drafts, and
the account, signing, device, graphics, video, and testing gates before submission.

## Version 1.6.0 (code 11)

The Speakeasy redesign: a forest-and-mint theme in light and
dark, the Manrope typeface, a home screen showing dictation status and setup at a glance, a
Writing style screen with live previews, More settings for keyboard, transcription, backup and
logs, and a restyled floating microphone. More settings → Recording indicator chooses how much
the microphone shows while recording: Detailed (panel with timer, level, Stop and Cancel),
Compact (a small pill with a red dot, timer and stop) or Minimal (the bubble itself becomes a
stop button with a voice-level ring and red dot; tap to stop, hold to cancel). The internal application ID is unchanged, so
updating keeps settings, saved transcripts and permissions. Design record:
[docs/design/speakeasy](docs/design/speakeasy/spec.md).

## Version 1.5.0 (code 10)

Adds per-app writing style; see below.

## Version 1.4.0 (code 9)

- **On-device transcription · Works offline · English** is the default, including upgrades with old cloud credentials.
- ARM64 Android 8+; the bundled model adds about 255 MB to the APK.
- Recordings finish automatically after five minutes. Cancel still discards audio.
- Advanced offers explicit cloud transcription, labelled as uploading recordings. There is no automatic fallback.
- Existing saved-before-insert recovery, Gboard overlay and optional IME behavior remain.

See [offline implementation, builds and device checks](docs/offline-transcription.md).

## Writing style (1.5.0)

- Dictation now matches the app you are typing in: casual in WhatsApp and other messengers (no stray commas or final full stop), formal in email, bare words in search boxes and subject lines.
- Filler words (um, uh, erm) are removed; "new line", "new paragraph" and "scratch that" work as commands; "at 5, no wait, 6" becomes "at 6".
- Text fits the sentence around the cursor.
- Optional Smart cleanup: a 345 MB on-device model (Qwen3 0.6B), downloaded once, tidies punctuation and is never allowed to change your words.

See [interpretation](docs/interpretation.md).
Model and runtime notices are bundled and available in Settings.

## Architecture
- `WysawygKeyboardService` — optional IME observing and controlling the shared foreground session
- `OverlayService` — foreground recording/session owner, notification and optional floating controls
- `AudioRecorder` / `DurablePcmStore` — stream durable 16 kHz PCM and expose bounded WAV segments
- `TranscriptionClient` — shared capture admission, explicit offline/cloud routing and completion staging
- `Interpretation` / `DictationFormatter` — per-app writing style, fillers, voice commands and cursor fit
- `SmartCleanup` — optional on-device punctuation model (LiteRT-LM), guarded to keep every word
- `PhononService` — serial native inference in a private process using PFD IPC
- `OllamaClient` — optional cloud transcription, created only for explicit cloud mode
- `TextInjectorService` — AccessibilityService fallback for text insertion
- `MainActivity` — settings, logging, and import/export

## Settings
- On-device transcription by default
- Collapsed Advanced: cloud choice, provider URL, API key, model and prompt
- Versioned JSON import/export; exports omit API keys and consent, imports use on-device transcription

## Permissions needed
- `SYSTEM_ALERT_WINDOW`
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`
- `INTERNET` / `ACCESS_NETWORK_STATE`
- `BIND_INPUT_METHOD`

## Build
Android Studio / Gradle. Minimum SDK 26 (Android 8), target SDK 36 (Android 16). Builds require verified model assets and native libraries; follow the [build instructions](docs/offline-transcription.md#builds) and [Play release guide](docs/google-play/README.md).

## Earlier releases and optional cloud behavior

The following verification notes describe earlier versions; their handset results do not prove the new Phonon APK integration.

### Cloud speech transcription (fork)

This fork supports OpenAI-compatible multipart speech transcription APIs, including Groq.
For Groq Free use URL `https://api.groq.com/openai/v1` and model `whisper-large-v3-turbo`,
with your own Groq API key. Leaving settings saves them automatically. Remain on the
provider's Free plan to avoid paid usage. Audio is uploaded to the configured provider.

Microphone permission is required. Enable the keyboard in Android input settings and
select it from the keyboard switcher. The optional overlay additionally needs Display
over other apps permission, and Accessibility for automatic insertion. Without an
editable field and an open keyboard, the floating button stays hidden. Settings export currently includes the
API key; keep exported files private. The installed debug APK permits ADB inspection
from an authorised computer.

Build verification: `./gradlew assembleDebug lintDebug` (JDK 17 or 21, Android SDK 34).
Version 1.0 was installed and used successfully on Samsung A55 / Android 15, with
Groq cloud transcription. Version 1.1.1 replaces the waveform panel with a small
microphone button, shown only for an editable, non-password field with a visible
software keyboard. Tap to record, tap again to finish, hold to cancel, drag to move.
The button turns red while recording and shows a spinner while transcribing.

The floating mode works with your existing keyboard. Enable Android Accessibility
and floating dictation in WYSAWYG settings. Dictation uses Android Accessibility's
SET_TEXT and SET_SELECTION actions directly, preserving surrounding text, replacing
only the selection, adding word spacing, and moving the cursor after the insertion.
Direct insertion leaves the clipboard unchanged. Recovery changes it only when you tap Copy. Apps that do not expose editable fields or permit
SET_TEXT need the optional WYSAWYG keyboard, which uses InputConnection.commitText.
This is a platform compatibility limit; the app reports failed insertion.

Closing the keyboard hides an idle button, while an active recording and its indicator continue.
Changes to the field, text, cursor, or keyboard invalidate automatic insertion without
stopping capture. Password fields cannot start capture; existing capture retains Stop.
The notification offers Stop while recording and Pause new recordings while idle.
Enabled floating standby can resume after accessibility reconnects, phone startup and
app updates. Recording itself never resumes automatically. Pause persists.

Try dictation opens a local scratch screen without sending messages or changing
provider settings. JVM tests cover cursor edits and selected ranges. Device tests
verify keyboard visibility, direct insertion, clipboard preservation and changed-field
and password handling. Run them on an Android device with microphone, overlay and
accessibility access enabled: `./gradlew connectedDebugAndroidTest`.


Samsung A55 / Android 15 verification (2 October 2026): 8 JVM cursor-edit tests
and 3 on-device integration tests passed using the existing Gboard keyboard.
Verified show/hide with the software keyboard, direct cursor insertion without
changing the clipboard, field-switch and password guards, and two microphone
recordings with valid 16 kHz mono WAV output. Android build and lint passed.
The Accessibility service declares generic feedback to receive keyboard/window
and focus events. Tests rebind only this already-enabled service after Android
replaces the application process for instrumentation. No cloud provider credentials
are included in the APK; existing phone settings are preserved on update.

Version 1.1.2 fixes placeholders being included in dictation. Android may report an
empty editor's hint (for example, "message") as its accessibility text. Both target
capture and insertion validation now use `isShowingHintText` to distinguish that
hint from entered content. Actual typed text is preserved, including words identical
to the placeholder. Changed text or cursor positions still prevent stale insertion.

The regression reproduced "message Hello there" on the A55 before the fix. With
1.1.2, all 8 JVM tests and 6 on-device tests passed, including empty hinted fields,
appending another dictation, preserving entered text that matches a hint, and
retaining text typed while transcription is pending. Build and lint passed. The
device-test service rebind now passes component names without literal shell quotes
and checks that all existing accessibility settings are retained.

Version 1.1.3 adds support for virtual editors whose input-focus lookup returns a
surrounding container. If that lookup does not identify a usable editor, WYSAWYG
searches the focused application window for the visible, focused, editable child.
An open software keyboard is still required, and password fields stay excluded.

Verified on the A55 with 8 JVM checks, 7 core device checks and an opt-in check in
the installed ChatGPT app. The virtual-editor regression failed before this fix
and passed afterward, including cursor insertion and password hiding. In ChatGPT,
the WYSAWYG button appeared with Gboard, inserted an unsent test sentence directly,
cleared the test draft, and disappeared when the keyboard closed. Build and lint
passed. Provider settings, the selected keyboard and other accessibility services
were retained.

The ChatGPT device check requires an installed app and an empty composer. Opt in
with the instrumentation argument `-e verifyChatGpt true`; ordinary device runs
skip this external-app check. It never taps Send, and clears only its own test
sentence. All other device checks run in the local scratch activity.

Version 1.1.4 handles apps such as WhatsApp that expose an empty composer's
"Message" label as text without setting `isShowingHintText` or providing a hint
or valid selection positions. On Android 13 and later, floating dictation also
uses the accessibility input connection to confirm whether the actual editor
buffer is empty. This observes the existing keyboard's input session; it does
not select or replace Gboard.

The read-only check requests one character on either side of the selection,
runs off the UI thread, and is repeated before an insertion based on an empty
buffer. A changed editor, input session, cursor or entered text prevents stale
insertion. Typed words identical to a placeholder are retained. Earlier Android
versions and editors without this API retain the marked-hint fallback; ambiguous
unmarked text without selection information is rejected rather than inserted.
The API contract is documented in [Android's accessibility input connection
reference](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection#getSurroundingText(int,int,int)).

The opt-in WhatsApp device check requires an empty, user-prepared conversation
with the keyboard open (`-e verifyWhatsApp true`). If starting the scratch
activity changes the foreground task, pass its existing task ID with
`-e whatsAppTask <task-id>` to return to that same conversation. The check enters
only known unsent test text, verifies that actual "Message" text is retained,
then clears only its own draft. It never taps Send.

Version 1.1.4 verification on the A55: 8 JVM checks, 9 core device checks and
the ChatGPT direct-insertion check passed. Build and lint passed. The installed
WhatsApp self-chat check also passed, verifying insertion without the unmarked
placeholder, preservation of a deliberately entered "Message", and clearing
only its own unsent draft. Across the runs, 11 distinct device checks passed.

## Version 1.3.0 (code 8): recovery and recording controls

Floating dictation keeps Gboard (or your selected keyboard). The idle microphone
stays small. During recording it expands to a roughly 288dp panel with elapsed
`mm:ss`, a live linear RMS microphone meter computed from the actual PCM16 samples,
and 48dp Stop and Cancel controls. Only the grip moves the expanded panel. Stop
releases the recorder and microphone foreground-service type before making the
transcription request. Cancel, hiding the keyboard, or Pause discards an active
recording without requesting transcription. Hold-to-cancel on Stop is retained.

A successful nonblank transcript is saved **before** attempting the original safe
insertion. Changed fields, cursor/text changes, unsupported editors or interrupted
completion leave a recovery card. Select a non-password text box, then explicitly
tap **Insert here** to capture and validate that current destination. Focus changes
never trigger insertion. **Copy** changes the clipboard only when tapped and keeps
the item; **Discard** removes the selected item. Previous/next controls navigate
multiple items. The card hides on ineligible editors and returns with an eligible
field and visible keyboard. **Later** collapses the card without deleting text;
**Saved text (n)** opens it again beside the microphone. It never takes keyboard
focus. The complete panel is
measured and clamped above the keyboard after resizing; in very short windows the
panel itself scrolls. Main Settings also has a Recovered transcripts access point
for reading, copying or discarding while floating dictation is paused.

Recoveries contain only `RecoveredTranscript(id, text, createdAt)`, never editor
contents or accessibility nodes. The checksummed atomic collection lives in
`noBackupFilesDir/recovered-transcripts.bin`, outside Android backups. Up to five
saved transcripts are kept for 24 hours from completion, with a 1 MiB total disk
budget. At capacity, new capture pauses until insertion, explicit discard or
expiry frees space. No saved item is overwritten to make room. An already-completed
concurrent result that exceeds capacity/storage limits is kept whole in memory
and marked **Not saved**; capture pauses. Android can reject copying very large
text; a failed Copy reports the error and keeps the whole item. Write failures also retain the complete
in-memory text and offer retry/Copy. Memory-only text survives service Pause in the
same process, but cannot survive process death; Copy it first. Unsaved memory-only
items do not expire automatically. Unreadable files block capture until explicitly
discarded, rather than being silently replaced. Interrupted temporary writes are
cleaned up on store initialization. Saved text is private app data, not a separate
encrypted vault. Explicit insertion is not proof that an external app sent anything.

The optional WYSAWYG IME shares recorder cleanup and staged recovery, rejects
password sessions, and validates its input generation before committing text. Its
layout is unchanged. Provider URL, credentials, model and existing settings are
unchanged by this release.

Local verification for 1.3.0: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and
`assembleDebugAndroidTest` run offline with JDK 21 / Android SDK 34. JVM coverage
includes PCM silence/known levels, full-text restart durability, capacity, expiry,
checksum/truncated-file corruption, interrupted writes, oversized results and disk
failure retention. Existing cursor/selection tests are retained (20 JVM tests pass in total). Lint reports
71 warnings and no errors; passing lint is not a device or visual acceptance result.

`RecoveryControlsTest` is a new targeted device suite. It temporarily uses a
loopback-only HTTP server and an empty test API key, restores provider preferences,
and never contacts a real provider. Resolve existing recoveries first. It covers
visible elapsed time/Cancel with zero requests, Stop releasing microphone access
before HTTP, changed-target recovery, explicit Copy and fresh insertion, password
hiding, service restart/Pause and disk readback, a response arriving during Pause,
and keyboard-hide persistence (the older cancellation expectation is superseded). All editor writes use the local scratch activity.
It restores the clipboard after each scenario and keeps the selected IME unchanged.
Run with the existing microphone/overlay/accessibility permissions and Gboard:

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r \
  -e class com.cragnet.wysawyg.RecoveryControlsTest \
  com.cragnet.wysawyg.test/androidx.test.runner.AndroidJUnitRunner
```

Version 1.3.0 was checked on the Samsung A55 with Android 15 and Gboard. All six
new device scenarios passed, as did the existing background and editor checks in
targeted runs. A saved transcript survived an actual process kill and reappeared
without opening WYSAWYG settings. See [verification details](docs/verification-1.3.0.md)
for the combined-suite readiness flake, screenshot coverage and remaining runtime
boundaries.
