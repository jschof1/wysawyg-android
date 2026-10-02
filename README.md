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
editable field, text is copied to the clipboard. Settings export currently includes the
API key; keep exported files private. The installed debug APK permits ADB inspection
from an authorised computer.

Build verification: `./gradlew assembleDebug lintDebug` (JDK 17 or 21, Android SDK 34).
Samsung A55 / Android 15: installation and settings launch verified. Fixed waveform
view inflation, cursor insertion, saving, recording state, cloud request format, and
scrollable settings. Provider transcription verified with a generated speech sample;
real microphone dictation still requires a phone test.
