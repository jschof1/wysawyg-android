# Vendored Phonon 2 C runtime

Source: https://github.com/eschmidbauer/phonon-2-c
Pinned commit: `c4b860f6ad745369d6f21599d6e23d381c1fcc7a` (MIT).

`csrc` contains the library sources and headers, including the complete frontend,
segmentation, encoder, decoder, WAV/resampling, token and JSON parsers. CLI and
benchmark entry points are excluded. Original LICENSE and NOTICE are retained.

Only upstream source modification: `blas.c` adds
`p2_release_thread_scratch()` to free the native owner thread's TLS buffers at
engine close. Pool-thread buffers already have pthread-key destructors.
Android-specific adapters/build flags live outside this directory under
`app/src/main/cpp` and `scripts/build-phonon-native.sh`.

The official binary kernel API is not used: it requires a separate frontend,
projector and detokenizer. This full C source engine supplies those parts but
contains fatal allocation/abort paths, so it runs only in the private `:phonon`
process. See `docs/offline-transcription.md` for lifetime and build contracts.
