# Background dictation lifecycle

WYSAWYG must be ready when Jack opens a text field with Gboard, without opening WYSAWYG settings first.
The first live use is typing in the existing phone workflow after the app process has restarted.

## Reproduction

On the Samsung A55, version 1.1.4 returned `START_NOT_STICKY` for its overlay service.
After terminating only the app's process with `run-as`, `dumpsys activity services com.cragnet.wysawyg` reported `(nothing)` ten seconds later.
The enabled preference remained set, but only `MainActivity.onResume` used it to restart the service.

## State and ownership

`overlay_enabled` is the durable user choice. Startup and recovery read that choice. Only Enable and Pause change it.
`OverlayService.State` owns the transient idle, recording, and transcribing state. A restarted service begins idle and never resumes an interrupted recording.
Android owns the accessibility-service binding and foreground-service restart. Accessibility connection and editor events reconcile the overlay with the saved choice.

Two designs were considered. Moving the entire bubble into `TextInjectorService` would remove the idle foreground service, but would also move window and audio ownership and change existing integration paths.
The chosen design retains `OverlayService`, makes it sticky, and reconciles it on accessibility connection, boot, and package replacement.
On Android 14 and later, standby uses the declared `specialUse` type. Only an explicit microphone tap adds the `microphone` type.
This keeps boot recovery from trying to start a microphone foreground service.

## Throughput checkpoint

- Blocking first steps. Reproduce process death on the installed app and confirm Android's service-start restrictions before changing lifecycle code.
- Independent workstreams. A read-only lifecycle and history review runs alongside local runtime verification. Builds run while documentation and the verification script are prepared.
- Shared mutable state. One writer owns the implementation worktree. Device tests run sequentially because they share one physical phone and its accessibility settings.
- Smallest safe decomposition. Keep the lifecycle changes together because the preference, service, receiver, and accessibility binding must agree. Tests use the app's scratch editor.

The configured Claude and Grok design roles were unavailable in the live provider catalog. A reduced Codex review compared both designs. No cross-provider consensus is claimed.
The design reviewer preferred accessibility ownership over a permanently microphone-typed service. The implemented standby uses `specialUse` instead, and the A55 verified microphone promotion from another app and immediate release after cancellation.

## Verification boundaries

The regression test reconnects only the already-enabled WYSAWYG accessibility component and retains all other enabled components.
Host verification terminates only WYSAWYG's process and checks the actual foreground-service record.
Device tests cover recovery, Pause, keyboard visibility, insertion guards, and microphone recording.

Android Force stop, revoking permissions, or disabling Accessibility overrides automatic recovery. The app does not bypass those controls.
Samsung battery restrictions can also limit background execution. The settings screen links to Android's background-running exemption and reports its current state.
A real reboot is separate from a simulated boot broadcast and must be reported separately.

## A55 verification on 20 October 2026

- Version 1.1.4 failed the regression with `Enabled dictation must recover without opening WYSAWYG settings`.
- Version 1.2.0 passed all three lifecycle checks. They cover accessibility reconnect, retained Pause, and microphone promotion after a tap with release after cancellation.
- Eight JVM tests passed. Build and Android lint passed with zero errors and 68 warnings.
- The full 14-scenario device run had one existing editor-timing failure. That unchanged scenario passed on a focused rerun. The two external-app opt-in scenarios were not enabled.
- The host process-death check observed the service attached to a different live PID without opening any activity. Standby did not claim microphone access.
- Reinstalling the APK with `adb install -r` restored the foreground service through `MY_PACKAGE_REPLACED`, with a new live PID and every saved setting retained exactly.
- From Samsung Settings search with Gboard open, the recovered bubble started a real recording. The service changed from type `0x40000000` to `0x40000080`, then returned to `0x40000000` after a long-press cancellation. No audio was uploaded.
- An independent review found one verification-script issue. Recovery now requires a different live PID attached to the service. Stale mocked responses were rejected, and the check passed on the real phone.

Android instrumentation deliberately disconnects accessibility when replacing its target process. After tests, the already-enabled WYSAWYG component was rebound while retaining every other enabled service. Ordinary process-death verification ran separately from instrumentation.
Samsung rejected the shell's simulated `BOOT_COMPLETED` broadcast with a permission denial. A real reboot and first unlock were not tested because the phone is shared with an active setup task.

Android references:

- [Foreground-service background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
