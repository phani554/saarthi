# Local Gemma on the iQOO Neo 10R (SM8635)

## Default (works on any phone): generic LiteRT-LM build
- Model: `gemma-4-E2B-it.litertlm` (2,588,147,712 bytes) from
  https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
  (the in-app download now fetches this file).
- Backends tried in order: GPU, then CPU. Logcat: `EngineHolder ... engine ready ... with GPU backend`.
- Expect slow rounds on CPU (tens of seconds). Keep tasks simple: "open WhatsApp", "call <name>".

## Speed: MTP draft model (speculative decoding)
- Gemma 4 `.litertlm` files downloaded after 2026-05-05 include an MTP drafter. The app now turns on
  `ExperimentalFlags.enableSpeculativeDecoding` (GPU, then CPU) and falls back to plain decoding if the file has
  no drafter. Google reports up to 2.2x decode on mobile GPUs; one PowerVR phone regressed (Adreno untested).
- Needs litertlm >= 0.11; the project is now on 0.16.1 (0.17+ needs Kotlin 2.3). Re-download the model if yours
  is older than May 2026. Logcat: `engine ready ... with GPU backend (mtp=true)`.

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
2. Gradle (done, 2026-11-14): the APK now ships the NPU libraries from Maven; nothing goes in `jniLibs/` by hand.
   - `com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.2` (Google Maven): `libLiteRtDispatch_Qualcomm.so`,
     `libLiteRtCompilerPlugin_Qualcomm.so`, and a manifest entry for `libcdsprpc.so`. No Java/Kotlin code, no deps,
     minSdk 24. Built against QAIRT 2.50.0.260828. Its dispatch check accepts an older LiteRT runtime (litertlm
     0.16.1) with a warning only, so no litertlm/Kotlin bump is needed.
   - `com.qualcomm.qti:qnn-runtime:2.50.0` (Maven Central, was 2.45.0): `libQnnHtp.so`, `libQnnSystem.so`,
     `libQnnHtpV68/V69/V73/V75/V79/V81 Stub+Skel` (V73 = SM8635), plus `libQnnHtpPrepare.so` etc. Must be >= the
     QAIRT the dispatch lib was built with: 2.45 fails with "Qnn library version ... is mismatched".
   - `useLegacyPackaging = true` (already) so the libs are extracted to `nativeLibraryDir`; `ADSP_LIBRARY_PATH` is
     set to `nativeLibraryDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/vendor/dsp` before `Backend.NPU(nativeLibraryDir)`.
3. Push the model, then pick it in Settings → Local model. When the app tries the NPU (`NpuGate`, used by
   `EngineHolder` and the phone check screen):
   - the file is an NPU build: its name contains `_NPU_` (or `qualcomm` plus a chip id, e.g. `_Qualcomm_SM8650`),
     or "Use NPU" is on (local backend preference `NPU`; today set by the debug receiver / `LocalBackendHealth.debugForceNpuRetry()`);
   - the chip in the name (e.g. `SM8635`) matches `Build.SOC_MODEL` (Android 12+). If the name has no chip, or the
     phone does not report one, the NPU is tried anyway;
   - `libLiteRtDispatch_Qualcomm.so`, `libQnnHtp.so`, `libQnnSystem.so` and the chip's HTP stub/skel (any stub/skel
     for chips not in the table) are in `nativeLibraryDir`.
   There is no hard-coded list of allowed files or phones (Gallery's `model_allowlist.json` has no `sm8635` key,
   google-ai-edge/gallery#857; this app does not use it). Whatever happens, NPU failure falls back to GPU, then CPU,
   and logcat says why: `EngineHolder: NPU will be tried|skipped for <file>: <reason>` and
   `NPU (mtp=false) init failed ..., trying next option`.
4. Proof it is on the NPU: logcat `engine ready ... with NPU backend`, `Loaded qnn shared library`,
   no XNNPACK fallback. If you see `avc: denied ... sku` or `Failed to set up QNN manager`, the OEM blocks it;
   stay on GPU.
5. If NPU init fails, restart the app before testing GPU (NPU is "first or never" per process).

Verified 2026-11-14: the app builds with these libs and the gate has unit tests (NpuGateTest). Not verified: an NPU run inside this app on the phone.
