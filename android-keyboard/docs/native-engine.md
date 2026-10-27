# Native engine (`libbark_asr.so`)

The Kotlin side observes a narrow seam: `BarkAsrNative.load()`, then three calls in
`SherpaBarkEngine`. This document covers the C side of that seam, how it is built and packaged,
and how it was verified without a phone.

Built against **sherpa-onnx v2.0.1** (C API + ONNX Runtime prebuilts, `arm64-v8a`).

## Why JNI rather than the Java AAR

The upstream AAR ships a fixed Java object model and a JNI library built for it. We need two
things it does not give us: our own model directory with our own SHA-256 verification in Kotlin
(already in `core/.../model/BarkModel.kt`), and no second copy of the knobs that decide how the
model behaves. Calling the C API directly keeps the dependency surface at one `.so` we compile
and one library we link.

## Files

```
app/src/main/cpp/bark_asr_jni.c        the shim (the only hand-written C)
app/src/main/cpp/CMakeLists.txt        builds libbark_asr.so
app/src/main/cpp/sherpa-onnx/include/  c-api.h          ┐ fetched by tools/download-sherpa-onnx.sh,
app/src/main/jniLibs/arm64-v8a/        libsherpa-onnx-c-api.so, libonnxruntime.so  ┘ both git-ignored
tools/download-sherpa-onnx.sh          pinned fetcher for the three files above
```

## The three entry points

JNI symbols for `dev.barkboard.ime.asr.BarkAsr` (the facade class generated for
`asr/BarkAsrNative.kt`, `@file:JvmName("BarkAsr")`), verified against `javap` on the compiled
class:

```c
JNIEXPORT jlong   JNICALL Java_dev_barkboard_ime_asr_BarkAsr_barkAsrCreate
                    (JNIEnv *, jclass, jstring model_dir, jint threads);
JNIEXPORT jstring JNICALL Java_dev_barkboard_ime_asr_BarkAsr_barkAsrTranscribe
                    (JNIEnv *, jclass, jlong handle, jfloatArray samples);
JNIEXPORT void    JNICALL Java_dev_barkboard_ime_asr_BarkAsr_barkAsrDestroy
                    (JNIEnv *, jclass, jlong handle);
```

Behaviour the Kotlin side depends on:

| situation | native returns | Kotlin sees |
| --- | --- | --- |
| model directory missing or unusable | `0` | "model unavailable", Settings opens |
| `handle == 0` | `""` | never a null `String` |
| empty sample array | `""` | silence, not an error |
| decode fails | `""` | empty transcript, logged |
| library absent | — | `load()` false, mic key reports not ready |

Input is 16 kHz mono floats in `[-1, 1]`, exactly as captured. **No padding, no post-filter**: Bark
3.0 Clean was evaluated on unpadded audio and removes fillers itself. Nothing is verified or
downloaded in native code — that already happened in Kotlin, and doing it twice would double the
launch cost of a 630 MB file.

## Configuration used

```c
config.feat_config.sample_rate  = 16000;
config.feat_config.feature_dim  = 80;
config.model_config.transducer  = { encoder, decoder, joiner };   // *.int8.onnx in the model dir
config.model_config.tokens      = "<model_dir>/tokens.txt";
config.model_config.num_threads = threads;                        // Kotlin clamps to 1..4
config.model_config.provider    = "cpu";
config.model_config.model_type  = "nemo_transducer";              // Parakeet family
config.decoding_method          = "greedy_search";                // TDT needs no beam
```

The model dir must contain exactly `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`,
`tokens.txt`.

## Getting the prebuilts

```bash
./tools/download-sherpa-onnx.sh
```

Downloads the pinned release tarball and `c-api.h` at tag v2.0.1, checks both against the SHA-256
values in the script, and installs the `arm64-v8a` libraries plus the header. Re-running is a
no-op once they are in place. `arm64-v8a` only: no 32-bit address space hosts a 1.2 GB model.

## Building

`app/build.gradle.kts` enables `externalNativeBuild` **only when the prebuilt library is
present**, and pins `ndkVersion`/CMake to the versions `tools/setup-toolchain.sh` installs:

- prebuilts present → `libbark_asr.so` is compiled for `arm64-v8a` and packaged with the two
  dependency libraries (APK ≈ 28 MB).
- prebuilts absent → the project still builds and installs; the mic key reports the model as
  unavailable, which is the honest state rather than a build failure.

## Verifying without a phone

The C file is not Android-specific, so the same translation unit can be built for the host and
exercised through real JNI. That is how this was checked:

1. Build it as a host library against the desktop sherpa-onnx prebuilts and the JDK's `jni.h`.
2. Compile a Java twin of the facade — same package, class and native signatures, so it binds to
   the same symbols.
3. Run it against the pinned model with a clip the Python harness had already decoded.

Latest run (2026-10-27, x86-64, 4 threads, model verified by `tools/verify-model.py`):

```
create(missing dir) -> 0   ok (0 = refused)
transcribe(handle 0) -> ""   ok
clip: 93680 samples (5.855 s)
create(model) -> 140135456897904
transcribed in 549 ms -> "Mr. Quilter is the apostle of the middle classes, and we are glad to welcome his gospel."
python says: "Mr. Quilter is the apostle of the middle classes, and we are glad to welcome his gospel."
same text as the Python run   ok
PASS
```

Both clips matched the Python result word for word, at ~0.5 s per clip and 1451 MB peak RSS. So a
failure on a device can be blamed on the phone's memory, thermal headroom or the `:asr` process
lifecycle — not on the JNI layer, the config, or the model.

## What is still unproven

Everything above ran on a desktop CPU. On a phone, still unmeasured: first-load time from
flash for a 630 MB encoder, decode latency on a mid-range chip, RSS against Android's per-process
limits, and whether the `:asr` process survives a long idle gap. The `:asr` process exists so an
out-of-memory kill takes down dictation instead of the keyboard; `AsrClient` reconnects on the next
utterance, but that path has not been exercised under real memory pressure.
