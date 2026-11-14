# Local Gemma on the iQOO Neo 10R (SM8635)

## Default (works on any phone): generic LiteRT-LM build
- Model: `gemma-4-E2B-it.litertlm` (2,588,147,712 bytes) from
  https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
  (the in-app download now fetches this file).
- Backends tried in order: GPU, then CPU. Logcat: `EngineHolder ... engine ready ... with GPU backend`.
- Expect slow rounds on CPU (tens of seconds). Keep tasks simple: "open WhatsApp", "call <name>".

## Why it was broken before
1. The download URL was `gemma-4-E2B-it_qualcomm_qcs8275.litertlm`; the real file is
   `gemma-4-E2B-it_Qualcomm_QCS8275.litertlm` (case), so it 404'd. That file is also an NPU build for the
   QCS8275 IoT chip, not SM8635.
2. A startup "migration" renamed a working `gemma-4-E2B-it.litertlm` path to that missing file.
3. GPU-only engine with no CPU fallback; `maxNumTokens = 8172` typo.
4. The agent loop sent ~50 tool schemas, cut tool results to 400 chars, and replayed history in a way that
   made the model "remember" actions it never took. See commit messages.

## Optional: NPU on this phone (from the Snapdragon Hexagon NPU Research project)
Measured there: Gemma 4 E2B AOT-compiled for SM8635 ran on the Hexagon NPU via LiteRT-LM,
sustained decode 20.6 -> 13.9 tok/s over 181 s (~64 C). That run was from adb shell, not inside this app.
No public SM8635 file exists; you compile it yourself.

1. Compile on WSL2/x86-64: NPU project `code/09-gemma-npu-test/tools/compile_gemma4_sm8635_wsl.sh`
   (input `gemma-4-E2B-it_NPU_Uncompiled.litertlm`, output `gemma-4-E2B-it_NPU_SM8635.litertlm`).
   Do NOT JIT-compile E2B on the phone (it ran out of memory).
2. Gradle (app/build.gradle.kts / libs.versions.toml):
   - `litertlm = "0.18.1"` (0.10.0 has no `Backend.NPU`; the app uses reflection so it still builds on 0.10.0).
   - add `com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.2` (dispatch lib).
   - put the V73 QNN libs in `app/src/main/jniLibs/arm64-v8a/`: `libQnnHtp.so`, `libQnnSystem.so`,
     `libQnnHtpV73Stub.so`, `libQnnHtpV73Skel.so` (+ `libLiteRtDispatch_Qualcomm.so`). Same QAIRT version for all
     (2.50.0.260828 matches the compiler); NPU project `code/22/tools/get_v73_runtime_libs.sh` fetches them.
   - `android { packaging { jniLibs { useLegacyPackaging = true } } }`.
3. Push the model, then pick it in Settings → Local model. The app uses NPU only when the file name contains
   `_NPU_SM8635` AND all five libs are in nativeLibraryDir; otherwise GPU -> CPU.
4. Proof it is on the NPU: logcat `engine ready ... with NPU backend`, `Loaded qnn shared library`,
   no XNNPACK fallback. If you see `avc: denied ... sku` or `Failed to set up QNN manager`, the OEM blocks it;
   stay on GPU.
5. If NPU init fails, restart the app before testing GPU (NPU is "first or never" per process).

Not verified: nothing here was compiled or run in this app yet.
