# Bundled offline dictation

WYSAWYG users should install the APK, grant microphone and insertion permissions, and dictate without entering a server URL or API key. Phonon 2 is the default. Cloud transcription remains an explicit option under Advanced; an offline failure never uploads audio.

## Design

Keep the existing `transcribe(wav, onTranscribed)` contract. An application-owned coordinator retains accepted work until completion, even if the overlay or keyboard caller is cancelled. It invokes the recovery callback before returning to a caller that may insert text. A shared admission token spans capture through transcription and staging, preventing overlay and IME requests from competing for the last recovery slot.

Run native inference in a private `:phonon` service with one owner thread. Pass audio and transcript file descriptors across Binder. Keep recovery storage and editor insertion in the main process. Completion and worker death resolve one request once; worker death reports a failure and releases resources. A subsequent deliberate request can reconnect.

Use the complete MIT C engine at `eschmidbauer/phonon-2-c@c4b860f6ad745369d6f21599d6e23d381c1fcc7a`. The official Android 0.2.11 kernels require substantial additional frontend, projector, tokenizer and segmentation code. In-process JNI is smaller but exposes the UI and memory-only recoveries to native aborts, so it was rejected.

Two independent Codex designs and a separate cross-review converged on the private service. The callback-preserving design was selected over replacing recovery with a new outcome API. Shared capture admission and explicit completion/death arbitration were incorporated from the alternative. The attempted third-provider review failed authentication and contributed no evidence.

## Packaging

Pin the converted model to Hugging Face revision `1c91a6a7a26809fecedb35d847bef69663dde8bb`, archive SHA-256 `48137a58011c4b0bc0a97087ea7e868daabf1525ca8447414be230b21faf99e6`. Fetch and verify it on the build host. Package verified weights uncompressed and map the APK asset region directly. There is no runtime model download or conversion. Include engine and model attribution.

Build baseline ARM64 and dot-product variants for API 26, choose the optimized variant only on supported CPUs, and use 16 KiB-compatible ELF alignment. Keep native model/pool ownership on one thread. The source model bundle is about 255 MB before application overhead.

## Work ownership

- Blocking prerequisites: runtime and license inspection, pinned model hashes, build tools and physical handset access. These are established; the standalone C engine has passed initial A55 transcription tests.
- Independent streams: one isolated implementation owner handles the coupled native/IPC/router/settings change; the parent verifies native compatibility, packaging, device behavior and the resulting diff.
- Shared state: separate worktrees for source changes; one admission owner for capture and completion; recovery writes remain in the application process.
- Smallest safe decomposition: one implementation owner avoids mismatched contracts across Binder, cancellation, UI settings and recovery. Native tests and evidence collection can proceed independently.

## Acceptance

1. Existing JVM checks and focused routing, settings, admission and cancellation tests pass.
2. The APK contains the expected model hashes and both native CPU tiers; API 26 linkage and ELF/package alignment are checked.
3. An upgrade preserves cloud credentials and recoveries while selecting offline by default. Legacy imports remain offline.
4. Known speech, silence and longer audio work through the installed app without network access or credentials.
5. Pause, caller destruction and worker death preserve completed text, suppress stale insertion, release admission, and allow a subsequent request.
6. The main screen exposes offline status and permission/setup controls; cloud configuration is inside Advanced.
7. Handset screenshots, runtime measurements and limitations are recorded before the commit, PR and APK are delivered.

The standalone engine probe is feasibility evidence. It does not establish installed-app behavior, Android 8 execution, or a 16 KiB device test.
