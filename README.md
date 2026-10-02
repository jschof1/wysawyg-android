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
Groq cloud transcription. Version 1.1 replaces the waveform panel with a small
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
