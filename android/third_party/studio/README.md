# Local audio studio: sources and redistribution

All network downloads are fixed, user initiated, and optional. No lecture, prompt,
trip coordinate or financial record is uploaded for inference.

## Inference

- `com.google.ai.edge.litertlm:litertlm-android:0.18.0` (Google, Apache-2.0),
  [source](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.18.0).
  CPU backend, four threads, 4096-token context, 768-token maximum answer.
- [Qwen2.5-1.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/tree/5e6e7a58d03777898b31330a15d2e0a5a9107de3),
  Apache-2.0, CPU q8 LiteRT-LM conversion, 1,597,922,856 bytes.
  SHA-256 `c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e`.
  Downloaded weights stay outside Git and outside the APK.

## Separation

- [Sherpa ONNX 1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8),
  Apache-2.0; ONNX Runtime MIT, Kaldi decoder/native fbank Apache-2.0,
  KISS FFT BSD-3-Clause, [Eigen sources](https://gitlab.com/libeigen/eigen)
  under MPL-2.0 (unmodified static use).
- Official [ARM64 Android static CLI archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android-aarch64-termux-static.tar.bz2),
  SHA-256 `e212d6c41a55b3d0ef0a7926796b4fa48d42ea173b759fafff60ced024dd0883`.
  Only `bin/sherpa-onnx-offline-source-separation` is redistributed, renamed
  `libmashwari_separate.so` for Android's read-only installed native library folder.
  Original ELF SHA-256 `3043e4fd48c19faf38756decc7839a440f450324527b8b9ee8665940bd845c99`.
  This is an ARM64 PIE executable, not a JNI library; no `System.loadLibrary` call
  is used. `ProcessBuilder` uses fixed arguments without a shell. Model paths and
  temporary output paths are app controlled. `LD_LIBRARY_PATH` resolves packaged STL.
  Executable PT_LOAD segments use 16 KiB alignment. JNI extraction is enabled.
- Android NDK **28.2.13676358** `libc++_shared.so`, Apache-2.0 WITH LLVM-exception.
  Input SHA-256 `ab4e6c71b96b851de45a8a9bd86369e7dbc2130a44b3b4520564be94847910f2`.
  Gradle strips debug symbols in packaged releases. Restore both binary inputs
  with `python3 tools/fetch_studio_native.py --ndk /path/to/28.2.13676358`.
- [Deezer Spleeter](https://github.com/deezer/spleeter), MIT, two stems,
  [fixed fp16 ONNX conversion](https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems-fp16/tree/93ba771920ade509f8cbd6825b1a90856c797e08).
  `vocals.fp16.onnx`: 19,681,017 bytes, SHA-256
  `24cef84aedcd1fe87c0b743ef3370ad34dc1fabf6c9014d6128a75a538c7b668`.
  `accompaniment.fp16.onnx`: 19,681,024 bytes, SHA-256
  `d14cea55793cc531a5875f5f4da08207d1c5ab9292e8e0099a104eecb014fcc0`.
  Both are optional downloads; SHA verification precedes native processing.

License texts are provided here and in the app's Models page. The upstream
executables contain other Sherpa components because they are statically linked;
no speech recognition, cloud inference, or microphone capture is enabled by this feature.
No local model or native audio engine has been validated on the user's physical
RedMagic handset in this workspace. CPU host inference is a distinct check.
