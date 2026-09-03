# Implementation Plan: Task-Scoped Exclusive TTS Engine Selection, Premature Cutoff Guard, Multi-Dialect Auto-Switching, OpenRouter & Mem0 UI Sync, Decoupled Models, and Voice Loop Termination

Comprehensive plan to implement rock-solid task-scoped exclusive TTS engine locking (Cloud vs Local Native for the entire task lifetime), prevent Premature Speech Cutoff during user recording pauses, fix speech collisions while providing full tool call & progress observability, mid-conversation language auto-switching in the Gemini Interaction system prompt, model decoupling, model config UI status updates (OpenRouter vs Gemini API), Mem0 initial state detection, and voice loop cancellation.

## User Review Required

> [!IMPORTANT]
> - **Task-Scoped Exclusive TTS Engine Locking**: When a task starts, `VoiceManager` evaluates network latency via `pingTtsLatencyMs()`.
>   - **If ping < 600ms AND Sarvam Key set**: `activeTaskTtsEngine` is locked to `SARVAM_CLOUD` **for the entire task run until `finish` / `cleanupAfterTask()`**. Native TTS is **completely disabled** for the entire task.
>   - **If ping >= 600ms OR ping fails OR key blank**: `activeTaskTtsEngine` is locked to `NATIVE_LOCAL` **for the entire task run until `finish` / `cleanupAfterTask()`**. Sarvam Cloud TTS is **completely disabled** for the entire task.
>   - **Task Lifetime Lock**: Guarantees ONLY the chosen engine executes for all intermediate progress, tool call summaries, and completion messages during that task, eliminating all mid-task engine switching and dual-speech race conditions.
> - **Premature Speech Cutoff Guard in VoiceRecorder**: Adjusts microphone silence detection threshold in `VoiceRecorder.kt` (requires 1.8s of quiet and RMS < 180.0) so natural mid-sentence pauses/breaths do NOT trigger early speech termination or cause TTS to speak before the user finishes speaking!
> - **Gemini Interaction System Prompt Language Matching**: System prompt in `PromptRewriter` will instruct Gemini / Interaction Model to dynamically detect and switch languages turn-by-turn (English, Telugu, Hindi, Tamil, Code-mix) based on latest user input while preserving conversation history context.
> - **Independent Decoupled Models**: Main Task Model selection (`z-ai/glm-5.3-flash`, `gpt-4.1`, `deepseek-r1`) will be 100% decoupled from the Conversation/Interaction Model (`gemini-3.5-flash-lite`, `gemini-3.8-flash`, `sarvam-105b`). Changing the Main Model will never alter the smooth interaction experience.
> - **Full Observability & Sequential Tool Speech**: Tool calls and progress steps will be rendered in real-time in the UI AND spoken sequentially using the locked TTS engine. Audio playback will be strictly queued/cancelled per step so speech never overlaps or mixes up.
> - **OpenRouter vs Gemini API UI Label**: Model Config screen (`LlmConfigActivity`) and Chat Header will explicitly display whether the model is routed via `Gemini API` or `OpenRouter` (e.g. `google/gemini-3.8-flash (OpenRouter)`).
> - **Mem0 Startup Source Resolution**: `HybridMemoryRepository` will evaluate `KVUtils.getMem0ApiKey()` on startup and setting changes so the UI badge shows `⚡ Mem0 Cloud` immediately when configured.
> - **Voice Loop Termination Guard**: When user utters cancellation or exit phrases (*"no"*, *"cancel"*, *"nothing else"*, *"stop"*, *"nahi"*, *"vaddu"*, *"nayi"*), `PromptRewriter` sets `isCancelled = true`. `TaskFlowController` speaks cancellation confirmation and terminates the voice loop completely.

## Proposed Changes

### Voice Engine, Task-Scoped TTS Locking & Speech Control

#### [MODIFY] [VoiceManager.kt](file:///C:/Users/phane/StudioProjects/saarthi/app/src/main/java/io/agents/pokeclaw/service/VoiceManager.kt)
- Implement `pingTtsLatencyMs(): Long` HEAD request function.
- Implement task-scoped TTS engine locking (`lockTtsEngineForTask()` and `unlockTtsEngineTask()`):
  - Lock `SARVAM_CLOUD` if latency < 600ms and key present; otherwise lock `NATIVE_LOCAL`.
  - Ensure all speech during task execution routes strictly through the locked engine.
  - Disable the non-selected engine for the entire task lifetime.
- Enforce strict mutual exclusion: stop any active `mediaPlayer` or `tts` instance before starting new speech.
- Dynamically update `lastDetectedLanguageCode` on every STT turn and pass it to Sarvam TTS (`target_language_code`) and Native TTS (`tts.setLanguage(locale)`).

#### [MODIFY] [VoiceRecorder.kt](file:///C:/Users/phane/StudioProjects/saarthi/app/src/main/java/io/agents/pokeclaw/service/VoiceRecorder.kt)
- Update silence detection threshold (`rms < 180.0`, silence timeout `1800ms`) so soft speech pauses or mid-sentence breaths do NOT cause premature speech cutoff.
- Require at least 1.5 seconds of recorded PCM audio before silence auto-stop can engage.

---

### Agent Interaction, System Prompt Alignment & Decoupled Models

#### [MODIFY] [PromptRewriter.kt](file:///C:/Users/phane/StudioProjects/saarthi/app/src/main/java/io/agents/pokeclaw/agent/PromptRewriter.kt)
- Update Gemini Interaction System Prompt to explicitly enforce turn-by-turn language auto-switching matching the user's latest input language/dialect (English, Telugu, Hindi, Tamil, Code-mix).
- Maintain conversation history context across turns without defaulting to Hindi.
- Add `isCancelled: Boolean` to `InteractionResult`.
- Detect cancellation and exit intents across languages ("no", "cancel", "stop", "nothing else", "nahi", "nayi", "vaddu", "exit").

#### [MODIFY] [LlmSessionManager.kt](file:///C:/Users/phane/StudioProjects/saarthi/app/src/main/java/io/agents/pokeclaw/agent/llm/LlmSessionManager.kt)
- Strictly decouple `createInteractionChatModel()` (Interaction Model) from `createCloudClient()` (Main Task Execution Model).
- Ensure Chat mode and Voice interaction turns always use `createInteractionChatModel()`.

#### [MODIFY] [TaskFlowController.kt](file:///C:/Users/phane/StudioProjects/saarthi/app/src/main/java/io/agents/pokeclaw/ui/chat/TaskFlowController.kt)
- Lock TTS engine at task start and unlock in `cleanupAfterTask()`.
- Render tool calls and progress steps in real-time in the UI message list.
- Speak progress and tool calls sequentially via the locked TTS engine, ensuring non-overlapping clean speech.
- If `result.isCancelled` is true, set `currentVoiceState = VoiceInteractionState.IDLE`, clear `VoiceManager.onPlaybackFinished = null`, and terminate the voice loop without re-triggering mic capture.

---

### Memory & Model Config UI State Sync

#### [MODIFY] [HybridMemoryRepository.kt](file:///C:/Users/phane/StudioProjects/saarthi/app/src/main/java/io/agents/pokeclaw/data/memory/HybridMemoryRepository.kt)
- Evaluate `KVUtils.getMem0ApiKey()` on initialization and key saves to set `_activeMemorySource` to `MEM0_CLOUD` immediately when key is configured.

#### [MODIFY] [LlmConfigActivity.kt](file:///C:/Users/phane/StudioProjects/saarthi/app/src/main/java/io/agents/pokeclaw/ui/settings/LlmConfigActivity.kt)
- Update active model card, interaction model card, and provider labels to explicitly display routing source (`Gemini API`, `OpenRouter`, `Sarvam AI`).
- Refresh all card status indicators dynamically whenever OpenRouter fallback toggle or model selections change.

#### [MODIFY] [ChatScreen.kt](file:///C:/Users/phane/StudioProjects/saarthi/app/src/main/java/io/agents/pokeclaw/ui/chat/ChatScreen.kt)
- Display active model routing label (`Gemini API` vs `OpenRouter`) and live memory source badge (`⚡ Mem0 Cloud` vs `💾 Local Vault`).

---

### Verification Plan

### Automated Tests
- Run unit test suite: `gradle_build(commandLine = "testDebugUnitTest")`.
- Compile Kotlin: `gradle_build(commandLine = "app:compileDebugKotlin")`.
- Assemble debug APK: `gradle_build(commandLine = "app:assembleDebug")`.

### Manual Verification
- Test latency ping evaluation: verify Sarvam Cloud TTS executes when ping < 600ms, and Native TTS exclusively executes when ping >= 600ms without dual-speech race condition.
- Test natural speech recording with mid-sentence pauses to verify TTS does NOT cut off speech prematurely.
- Test mid-conversation language switching (English $\rightarrow$ Telugu $\rightarrow$ Hindi) and verify Gemini Interaction prompt and TTS switch languages turn-by-turn.
- Verify changing Main Task Model does NOT affect Interaction Model behavior.
- Verify tool calls and progress updates are rendered in UI and spoken sequentially without speech collision.
- Verify Model Config screen displays `OpenRouter` vs `Gemini API` routing tags accurately.
- Verify Mem0 badge displays `⚡ Mem0 Cloud` on launch when Mem0 key is configured.
- Test voice interaction when user says "nothing else" or "no thanks", verifying the mic does not re-open.
