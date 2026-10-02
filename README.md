# WYSAWYG — Voice-to-Text for Android

WYSAWYG is a private, local voice-to-text app for Android. It provides a microphone button via a custom keyboard IME and an optional floating overlay. Recorded audio is sent to a configurable local endpoint (e.g. Ollama running `gemma4:12b`) for transcription, and the resulting text is inserted at the cursor.

## Model
- Default: `gemma4:12b` on Ollama (tested working for audio with `think: false`)
- Audio sent as base64-encoded 16kHz mono WAV in the `images` array
- Endpoint: configurable in settings, default `http://localhost:11434/api/chat`
- Optional API key: sent as `Authorization: Bearer <key>`

## Architecture
- `WysawygKeyboardService` — custom keyboard IME with a record button
- `OverlayService` — optional floating record button
- `AudioRecorder` — captures 16kHz mono PCM and writes WAV
- `OllamaClient` — POSTs base64 audio to the configured endpoint, extracts transcribed text
- `TextInjectorService` — AccessibilityService fallback for text insertion
- `MainActivity` — settings, logging, and import/export

## Settings
- Alarma URL
- API key
- Model name
- System prompt
- Export/import settings as JSON

## Permissions needed
- `SYSTEM_ALERT_WINDOW`
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`
- `INTERNET` / `ACCESS_NETWORK_STATE`
- `BIND_INPUT_METHOD`

## Build
Android Studio / Gradle. Minimum SDK 26 (Android 8), target SDK 34.

## Cloud speech transcription (fork)

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
The clipboard is never changed. Apps that do not expose editable fields or permit
SET_TEXT need the optional WYSAWYG keyboard, which uses InputConnection.commitText.
This is a platform compatibility limit; the app reports failed insertion.

Closing the keyboard hides the button and cancels an active recording. If the field,
text or cursor changes while transcription runs, the result is not inserted into
another location. Password fields do not show the button. The notification allows
pausing the service; after an app/process restart, open settings to resume it.

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
