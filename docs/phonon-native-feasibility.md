# Phonon native feasibility — 21 October 2026

These results are from a standalone native executable on the physical Samsung A55 (SM-A556E), Android 15/API 35, ARM64, 4 KiB pages. They establish engine feasibility, not installed-app behavior.

The engine source is `eschmidbauer/phonon-2-c@c4b860f6ad745369d6f21599d6e23d381c1fcc7a`. The converted model archive and all three extracted files matched their published SHA-256 values at Hugging Face revision `1c91a6a7a26809fecedb35d847bef69663dde8bb`.

| Fixture | Audio | Result | Native compute |
| --- | ---: | --- | ---: |
| JFK sample, baseline ARM64, API 26 build | 11 s | Expected spoken words | 2.775 s, fastest of 2 |
| JFK sample, dot-product ARM64, API 26 build | 11 s | Expected spoken words | 1.038 s, fastest of 2 |
| Six JFK repetitions with one-second pauses | 72 s | All six phrases retained | 6.26 s |
| Digital silence | 3 s | Empty transcript | 0.850 s |
| Very short digital silence | 0.1 s | Empty transcript | 0.261 s |
| Synthetic microphone-test phrase | 7.8 s | Exact expected text | 0.825 s |
| LibriSpeech 5105-28240-0004 | 6.015 s | Exact normalized word match | 0.953 s |
| LibriSpeech 5639-40744-0013 | 6.865 s | Exact normalized word match | 0.753 s |
| LibriSpeech 4077-13754-0014 | 10.970 s | Exact normalized word match | 1.115 s |

The synthetic phrase was: “This dictation stays on my phone. I do not need an API key. Please add milk and coffee to my shopping list.” The WAV was generated with macOS speech synthesis and passed directly to the engine; this was not a microphone-capture test. LibriSpeech fixtures and expected words came from the pinned upstream test data. Normalization ignores punctuation and case.

During a separate repeated 72-second decode, 32 samples from `/proc/PID/smaps_rollup` showed a peak sampled PSS of 359.6 MiB, RSS of 361.7 MiB and private dirty memory of 116.1 MiB. Sampling may miss a higher transient peak. This is not the memory footprint of the complete app.

## Build checks

The native probes used NDK r27c, explicit `armv8-a` or `armv8.2-a+dotprod`, four worker threads and no optional code prepacking. API 26 builds replaced `aligned_alloc` with a checked `posix_memalign` adapter; dynamic symbols contained no `aligned_alloc` import.

A preliminary build of the JNI integration produced baseline, dot-product and CPU-probe libraries. All LOAD segments had alignment `0x4000`; each GNU_RELRO end was divisible by `0x4000`. The build uses both `max-page-size=16384` and `common-page-size=16384`. The native manifest matched the source snapshot used to compile it. Recheck against the final committed source and packaged APK.

## Remaining verification

The APK must still prove model mapping and integrity, offline routing, clean setup and upgrade behavior, actual microphone-to-editor dictation, cancellation/recovery ordering, worker death and resource cleanup. Android 8 and a physical 16 KiB-page device were unavailable; API targeting and binary inspection do not substitute for execution on those configurations.
