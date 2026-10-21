# On-device transcription (1.4.0 / code 9)

The default is bundled Phonon 2, English, ARM64, Android 8/API 26 or later.
It needs microphone permission, not a server URL or key. The model adds about
255 MB to the APK. Recordings finish normally at five minutes; Stop finishes
sooner and Cancel discards captured audio. There is no handset model download.

Advanced contains an explicit cloud choice and the existing provider fields.
Cloud uploads audio to that provider. Missing, legacy or unknown mode values
select OFFLINE even when credentials exist. A mode change preserves credentials;
JSON formatVersion 2 includes the mode, while legacy imports select OFFLINE.
Import reloads the visible fields before onPause can save them.

## Runtime contract

- `Transcriptions` is a main-process application-context coordinator shared by
  overlay and IME. One capture reservation is acquired before using the microphone
  and held through complete-text staging or failure. This prevents two captures
  competing for the fifth recovery slot. Cancel before submission frees it.
- `transcribe(wavBytes, reservation, onTranscribed)` retains the callback API.
  Acceptance transfers work to an application-owned coroutine. Cancelling the
  caller suppresses insertion only. The callback runs once before the active
  waiter resumes, regardless of caller cancellation. Existing RecoveryStore
  durability, unsaved-memory retention and explicit insertion safeguards remain.
- OFFLINE selects `PhononClient` without constructing HTTP. CLOUD alone constructs
  the existing Ollama client. There is no automatic cloud fallback or retry.
- `PhononService` is `exported=false`, in private process `:phonon`. It never opens
  Recoveries or preferences. WAV and complete UTF-8 output cross Binder as PFDs
  backed by private cache files unlinked before writing. There are no audio arrays
  or unbounded transcript strings in Binder parcels.
- One process-wide handler thread opens, decodes and closes the engine and its
  explicit four-thread pool. A replacement service instance shares that owner,
  including while an old timed-out decode finishes. Cleanup runs serially after
  accepted requests; it joins the pool, frees owner TLS scratch, closes the engine
  and finally unmaps weights. Idle cleanup releases native memory after 30 seconds;
  normal completion releases the binding and service destruction queues cleanup.
  The lightweight owner thread can remain until Android reclaims the process.
- The main request-ID completion gate accepts full bounded UTF-8 before resolving
  success. That accepted result wins a subsequent worker death. A death before a
  complete result is an explicit failure; a deliberate next attempt may rebind.
  Bindings, death recipients and all PFDs close on success/failure. A ten-minute
  request timeout reports failure without retry. Force-stop/main-process death
  before staging can still lose an in-flight recording; only staged recovery is
  durable.

## Native source and assets

The full MIT C engine is vendored at commit
`c4b860f6ad745369d6f21599d6e23d381c1fcc7a` from
https://github.com/eschmidbauer/phonon-2-c . Its frontend, segmenter, decoder and
parsers are included. The official binary kernel route would require custom
frontend/projector/detokenizer work, so it is not used. The source engine has
abort/OOM paths: the private worker process is the crash boundary, not a promise
that every failure is recoverable. `vendor/phonon2/README.md` lists modifications.

A generic ARMv8-A probe checks HWCAP_ASIMDDP before loading either generic ARMv8-A
or `armv8.2-a+dotprod` engine code. No i8mm assumption or `-march=native` is used.
NDK r27c (`27.2.12479018`) targets API 26; a checked posix_memalign shim avoids the
API-28 aligned_alloc symbol. Both LOAD and GNU_RELRO alignment are 16 KiB.
AGP 8.5.0 uses legacy/compressed JNI packaging to avoid its uncompressed-JNI ZIP
alignment limitation. Only the small .so files are extracted on install.

The three uncompressed APK assets are mapped through AAsset file offsets and
lengths. `p2_engine_open_mem` borrows the weights mapping until engine close.
P2_CACHE_CODES is disabled. There is no model copy/extraction on the phone.

Converted assets are pinned to Hugging Face `eschmidbauer/phonon-2-c` revision
`1c91a6a7a26809fecedb35d847bef69663dde8bb`. The archive SHA-256 is
`48137a58011c4b0bc0a97087ea7e868daabf1525ca8447414be230b21faf99e6`.
`scripts/prepare-phonon-model.py` pins each asset size and SHA-256 too, verifies
cached archives when present and verifies every cached asset. Missing or corrupt
inputs fail closed. Large binaries are not committed. Attribution to Fermion
Research/NVIDIA, conversion changes, MIT and CC-BY-4.0 notices ship in assets and
are readable from Settings → Speech model licences.

## Builds

JDK 17/21, Android SDK 34, Python 3.9+. JVM tests and Kotlin compilation need no
NDK or weights:

```sh
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin --offline --no-daemon --max-workers=2
python3 scripts/test-phonon-model.py
```

Explicit build-time model preparation (network permitted only by this command):

```sh
python3 scripts/prepare-phonon-model.py --download --model-dir .phonon/c_weights
```

Build native libraries on a host that already has NDK r27c:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r27c scripts/build-phonon-native.sh /path/to/jni
```

Copy the resulting `arm64-v8a/` and `phonon-native.json` together. The APK build
verifies binary SHA-256 and the native-source fingerprint. Changes to native
sources or the build/manifest scripts require a rebuild. With prepared inputs:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest \
  -PphononModelDir=/path/to/c_weights -PphononJniDir=/path/to/jni \
  --offline --no-daemon --max-workers=2
```

Without `phononJniDir`, Gradle runs the direct NDK-clang script using
ANDROID_NDK_HOME. It never installs an SDK/NDK. Without `phononModelDir`, it uses
`.phonon/c_weights` but does not download implicitly. CI explicitly prepares the
pinned model and NDK, verifies the cache, and builds PRs against any base branch.

## Device verification entry points

Native CLI probes are not APK integration proof. The installed-app results below
are separate from those probes. `PhononIntegrationTest` uses the real JNI service through the
router, stages into an isolated test RecoveryStore, checks caller cancellation,
UTF-8/PFD ownership, and optionally kills only the private worker then rebinds.
It does not change real preferences, recoveries or the clipboard. Supply a known
16 kHz WAV (the parent has an 11-second JFK fixture; it is not committed):

```sh
adb -s SERIAL push jfk.wav /data/local/tmp/wysawyg-jfk.wav
adb -s SERIAL shell run-as com.cragnet.wysawyg mkdir -p files
adb -s SERIAL shell run-as com.cragnet.wysawyg cp /data/local/tmp/wysawyg-jfk.wav files/phonon-fixture.wav
adb -s SERIAL shell "am instrument --user 0 -w -r \
  -e class com.cragnet.wysawyg.PhononIntegrationTest \
  -e phononFixture /data/user/0/com.cragnet.wysawyg/files/phonon-fixture.wav \
  -e phononExpected 'And so my fellow Americans ask not what your country can do for you ask what you can do for your country' \
  -e phononCrashWorker true \
  com.cragnet.wysawyg.test/androidx.test.runner.AndroidJUnitRunner"
```

No fixture argument means the speech tests skip, not pass. The crash test is
opt-in and expects a sufficiently long known speech fixture to kill the worker
before its result arrives. `RecoveryControlsTest` now selects CLOUD explicitly
for its loopback server and restores the old mode and credentials afterward.
Existing recovery/clipboard preservation fixtures remain in place. Check the
five-minute automatic finish and new settings states on the phone, alongside
existing overlay/IME insertion, Pause, recovery and restart checks. Screenshots,
actual offline handset transcription, battery/memory behavior, API-26 and 16-KiB
runtime checks are separate acceptance evidence, not implied by JVM tests.

## Child implementation checks (21 October 2026)

33 JVM tests passed, including default/migration routing, shared capture admission,
late callback after cancellation, staging-before-resume, stale reservation reuse,
worker death/result arbitration and existing recovery/editor checks. Four Python
cache verification tests passed. Kotlin and Android instrumentation test sources
compiled. `lintDebug` passed with 76 warnings and zero errors. XML/Python parsing,
shell syntax and `git diff --check` passed. The persistent prepared model hashes
and external JNI/source manifest were verified after temporary inputs were lost.
This child did not package/install the final APK or run handset instrumentation;
those checks, rendered screenshots and independent review remain parent-owned.

## Installed APK verification (21 October 2026)

The final application code is `7bcde5f`. The debug APK is 262,196,843 bytes
(about 262 MB / 250 MiB), SHA-256
`215633b575a8f3f88fd54b50dfbdd8fe17cef65f7eaa84cc6ced50e8be48f936`.
Its signing certificate matches the previously installed 1.3.0 debug APK.
All three model asset hashes match the pins; the model entries are uncompressed.
The three native libraries are compressed, match the verified source manifest,
and retain their 16 KiB ELF alignment. No model extraction/download is needed.

On Samsung A55 / Android 15 / 4 KiB pages:

- Upgraded 1.3.0 to 1.4.0 without clearing app data. Existing cloud URL, key, model,
  prompt and floating-dictation preference remained unchanged. Offline became
  the default; Advanced remained collapsed.
- Four real JNI/Binder instrumentation tests passed in airplane mode with Wi-Fi
  and mobile data disabled: known speech and disk staging, exact-once staging
  after caller cancellation, private-worker death followed by deliberate rebind,
  and unlinked-file Unicode transport. No HTTP backend was constructed by the
  offline test router.
- Five recovery-control instrumentation tests passed, including changed target,
  Pause during completion, password hiding, explicit insertion, clipboard
  preservation and Cancel. These use an explicit loopback cloud fixture.
- The final APK transcribed a 72-second fixture and persisted all 132 expected
  words in 6.483 seconds including the test's setup and assertions.
- Actual microphone capture of a spoken playback inserted into the scratch
  editor through both the floating control with Gboard and the optional IME.
  A keyboard change during completion kept the transcript for explicit recovery
  insertion. The acoustic test misheard "API key" as "APIP"; this is functional
  evidence, not a general accuracy benchmark.
- Real screenshots of offline settings, expanded Advanced, recording, recovery
  and inserted text were inspected. The expanded panel fits the display after
  its resize animation settles.
- Restored Gboard, the exact enabled-IME list and the original network settings.
  Only test fixtures were removed. The recovery store ended empty, as it began.

The parent build passed 37 JVM tests, four Python model-cache tests and lint
(zero errors, 76 warnings). Both APKs built successfully. Independent review
found one P2: settings export converted absent cloud defaults into blanks.
The fix omits absent values while retaining explicit blanks; four regressions
pass, and follow-up review found no remaining concrete P1/P2 issues.

Runtime limits: this was an upgrade test, not a clean-install handset test.
Missing-setting defaults are covered by JVM tests. Physical Android 8 and 16 KiB
devices, a timed five-minute automatic finish and sustained thermal testing were
not exercised. The five-minute bound is enforced in capture, IPC and native
decode; the native engine can still terminate its private process on OOM.
The APK is a signed debug preview, not a Play Store production release.
