// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.tool.ToolResult
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * LlmClient implementation using Google LiteRT-LM SDK for on-device inference.
 *
 * Bridges the stateless LangChain4j chat interface (full message list per call)
 * to LiteRT-LM's stateful Conversation API (incremental messages).
 *
 * config.baseUrl is repurposed to hold the local model file path.
 */
class LocalLlmClient(private val config: AgentConfig) : LlmClient {

    private val GSON = Gson()

    private data class ToolParseResult(
        val requests: List<ToolExecutionRequest>,
        val hasToolCalls: Boolean,
    )

    // Engine is owned by the shared local runtime.
    // We keep a local reference only for null-check convenience.
    private var engine: Engine? = null
    @Volatile private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var processedMessageCount = 0
    private var activeBackendLabel: String = "unknown"

    // Guards native calls on a Conversation: close() must never free a conversation while
    // sendMessage() is still decoding on another thread (use-after-free -> SIGSEGV on Stop).
    private val convLock = ReentrantLock()
    @Volatile private var inFlight: com.google.ai.edge.litertlm.Conversation? = null
    private val pendingClose = CopyOnWriteArraySet<com.google.ai.edge.litertlm.Conversation>()

    /**
     * Close [conv] safely: cancel a running decode, wait briefly for it to return, then close.
     * If the native call is still running after the wait, the sending thread closes it when it returns.
     */
    private fun releaseConversation(conv: com.google.ai.edge.litertlm.Conversation?) {
        if (conv == null) return
        pendingClose.add(conv)
        if (inFlight === conv) {
            try { conv.cancelProcess() } catch (e: Exception) { XLog.w(TAG, "cancelProcess error", e) }
        }
        var locked = false
        try {
            locked = convLock.tryLock(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (locked) {
            try {
                if (pendingClose.remove(conv)) conv.close()
            } catch (e: Exception) {
                XLog.w(TAG, "close conversation error", e)
            } finally {
                LocalModelRuntime.releaseSession(conv)
                convLock.unlock()
            }
        } else {
            XLog.w(TAG, "releaseConversation: native call still running, conversation will close when it returns")
        }
    }

    private fun ensureEngine() {
        val modelPath = config.baseUrl
        val context = ClawApplication.instance
        try {
            val shared = LocalModelRuntime.acquireSharedEngine(
                context = context,
                modelPath = modelPath,
                preferCpu = false,
            ).engine
            if (engine !== shared) {
                XLog.i(TAG, "ensureEngine: obtained shared engine for $modelPath")
                engine = shared
            }
            activeBackendLabel = LocalModelRuntime.currentBackendLabel(modelPath) ?: "unknown"
            XLog.d(TAG, "ensureEngine: backend=$activeBackendLabel")
            io.agents.pokeclaw.benchmark.BenchmarkRecorder.noteLocalBackend(activeBackendLabel)
        } catch (e: Exception) {
            XLog.e(TAG, "ensureEngine: failed to get engine from shared runtime", e)
            throw e
        }
    }

    /**
     * Create a new conversation with system prompt and tool declarations.
     */
    private fun createConversation(systemPrompt: String, toolSpecs: List<ToolSpecification>) {
        // LiteRT-LM only supports one session at a time — close existing first
        val old = conversation
        conversation = null
        releaseConversation(old)

        val promptWithManualTools = buildManualToolPrompt(systemPrompt, toolSpecs)
        XLog.i(TAG, "createConversation: sdk tools hidden, manual tool schemas injected (${toolSpecs.size})")

        // NPU bundles reject a custom sampler (NPU research guard rail G3), so leave it default there.
        val onNpu = LocalModelRuntime.currentBackendLabel(config.baseUrl) == "NPU"
        val convConfig = if (onNpu) ConversationConfig(
            systemInstruction = Contents.of(promptWithManualTools),
            tools = emptyList(),
            automaticToolCalling = false
        ) else ConversationConfig(
            systemInstruction = Contents.of(promptWithManualTools),
            tools = emptyList(),
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.9,
                temperature = config.temperature.coerceAtMost(0.3), // tool calling: keep it near-greedy
            ),
            automaticToolCalling = false
        )

        val lease = LocalModelRuntime.openConversation(
            context = ClawApplication.instance,
            modelPath = config.baseUrl,
            conversationConfig = convConfig,
            preferCpu = false,
            owner = LocalSessionOwner.TASK,
        )
        engine = lease.engine
        conversation = lease.conversation
        activeBackendLabel = lease.backendLabel
        XLog.i(TAG, "createConversation: backend=$activeBackendLabel model=${config.baseUrl}")
        // processedMessageCount is managed by chatInternal (resetting it here made a mid-task
        // rebuild replay the whole history into the new session).
    }

    private fun buildManualToolPrompt(basePrompt: String, toolSpecs: List<ToolSpecification>): String {
        if (toolSpecs.isEmpty()) return basePrompt
        // Gemma E2B has a small context window. Sending all ~50 tool schemas as JSON filled
        // most of it before the task even started, so the model lost the screen and guessed.
        // Only the core phone tools go to the local model, one compact line each.
        val coreSpecs = toolSpecs.filter { it.name() in LOCAL_CORE_TOOLS }
            .sortedBy { LOCAL_CORE_TOOLS.indexOf(it.name()) }
        val toolSchemas = coreSpecs.mapNotNull { spec ->
            try {
                compactToolLine(spec)
            } catch (e: Exception) {
                XLog.w(TAG, "buildManualToolPrompt: failed to describe ${spec.name()}", e)
                null
            }
        }
        if (toolSchemas.isEmpty()) return basePrompt

        return buildString {
            append(basePrompt.trimEnd())
            append("\n\n## TOOL CALLING RULES (MANUAL MODE)\n")
            append("- DO NOT use native Gemma tool tokens (No <|tool_call|>).\n")
            append("- To execute a tool, you MUST output exactly this plain-text format:\n")
            append("Action: tool_name\n")
            append("{\"arg1\": \"value1\"}\n")
            append("- The arguments MUST be a valid JSON object. Always use standard double quotes.\n")
            append("- Exactly ONE action per reply. Never claim something is done before the tool result says so.\n")
            append("- When the task is done or impossible, use finish with an honest summary.\n")
            append("\n## Available Tools (name(args): purpose)\n")
            toolSchemas.forEach { schema ->
                append(schema)
                append('\n')
            }
        }
    }

    private fun compactToolLine(spec: ToolSpecification): String {
        val schema = DynamicOpenApiTool(spec).getToolDescriptionJsonString()
        val obj = com.google.gson.JsonParser.parseString(schema).asJsonObject
        val props = obj.getAsJsonObject("parameters")?.getAsJsonObject("properties")
        val required = obj.getAsJsonObject("parameters")?.getAsJsonArray("required")?.map { it.asString }?.toSet().orEmpty()
        val args = props?.entrySet()?.joinToString(", ") { (k, v) ->
            val t = v.asJsonObject.get("type")?.asString ?: "string"
            if (k in required) "$k:$t" else "$k?:$t"
        }.orEmpty()
        val desc = (spec.description() ?: "").replace(Regex("\\s+"), " ").take(110)
        return "- ${spec.name()}($args): $desc"
    }

    private var sendCount = 0
    private var pendingRecap = false // recap still owed to a rebuilt session (survives a failed send)
    private var originalTask: String = ""
    private val recentActions = ArrayDeque<String>()

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        return try {
            runBlocking {
                chatInternal(messages, toolSpecs)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "chat: local model execution failed", e)
            throw e
        }
    }

    private suspend fun chatInternal(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        currentCoroutineContext().ensureActive()
        ensureEngine()

        // New task, history rewound, or conversation getting long -> (re)create the session.
        val isNewTask = processedMessageCount == 0 || messages.size < processedMessageCount
        val needsRebuild = !isNewTask && sendCount >= MAX_SENDS_PER_SESSION
        if (isNewTask || needsRebuild) {
            val systemPrompt = messages.filterIsInstance<SystemMessage>().firstOrNull()?.text()
                ?: config.systemPrompt.ifEmpty { LOCAL_SYSTEM_PROMPT }
            createConversation(systemPrompt, toolSpecs)
            sendCount = 0
            pendingRecap = needsRebuild
            if (isNewTask) {
                processedMessageCount = 0
                recentActions.clear()
                originalTask = messages.filterIsInstance<UserMessage>().firstOrNull()?.let { userText(it) }?.take(600).orEmpty()
            }
        }

        val newMessages = messages.subList(
            processedMessageCount.coerceAtMost(messages.size),
            messages.size
        )

        // Batch everything new into ONE sendMessage. Previously each message was sent separately,
        // and every sendMessage makes the model generate a reply: the discarded in-between replies
        // stayed in the conversation and the model then "remembered" actions it never took.
        val parts = mutableListOf<String>()
        if (pendingRecap) {
            // Old code replayed every user/tool message (without the model's own actions) into the
            // fresh session. Now: one compact recap.
            parts += "Task: $originalTask"
            if (recentActions.isNotEmpty()) {
                parts += "Actions so far (oldest first):\n" + recentActions.joinToString("\n")
            }
        }
        for (msg in newMessages) {
            when (msg) {
                is SystemMessage -> { /* handled in createConversation */ }
                is UserMessage -> {
                    val text = userText(msg)
                    parts += text.take(LOCAL_MAX_USER_CHARS)
                }
                is AiMessage -> {
                    msg.toolExecutionRequests()?.forEach { req ->
                        recentActions.addLast("${req.name()} ${req.arguments().take(120)}")
                        while (recentActions.size > 6) recentActions.removeFirst()
                    }
                }
                is ToolExecutionResultMessage -> {
                    parts += "[Tool ${msg.toolName()} result]: ${msg.text().take(LOCAL_MAX_TOOL_RESULT_CHARS)}"
                    if (recentActions.isNotEmpty()) {
                        val last = recentActions.removeLast()
                        recentActions.addLast("$last -> ${msg.text().take(80).replace('\n', ' ')}")
                    }
                }
            }
        }

        val allowedTools = toolSpecs.map { it.name() }.toSet()
        var lastResponse: Any? = null
        val payload = parts.filter { it.isNotBlank() }.joinToString("\n\n")
        if (payload.isNotBlank()) {
            lastResponse = send(payload)
            pendingRecap = false
            // One correction round if the model wrote "Action:" but the JSON was unusable,
            // instead of treating the broken text as the final answer.
            val rawText = (lastResponse as? Message)?.contents?.toString()?.trim().orEmpty()
            val parsed = extractToolCalls(rawText, allowedTools)
            if (parsed.hasToolCalls && parsed.requests.isEmpty()) {
                XLog.w(TAG, "chat: malformed tool call, asking model to repeat it once")
                lastResponse = send("Your last action could not be parsed. Reply again with exactly:\nAction: tool_name\n{\"arg\": \"value\"}")
                val retryText = (lastResponse as? Message)?.contents?.toString()?.trim().orEmpty()
                val retryParsed = extractToolCalls(retryText, allowedTools)
                if (retryParsed.hasToolCalls && retryParsed.requests.isEmpty()) {
                    // Never hand the pre-action "I'll send it now" text back as a final answer.
                    XLog.w(TAG, "chat: tool call still unparseable after retry, reporting failure")
                    processedMessageCount = messages.size
                    return LlmResponse(text = UNPARSEABLE_ACTION_TEXT, toolExecutionRequests = emptyList())
                }
            }
        }

        processedMessageCount = messages.size
        return parseResponse(lastResponse, allowedTools)
    }

    /** Text of a user message; never throws on multi-part (e.g. image) messages. */
    private fun userText(msg: UserMessage): String =
        if (msg.hasSingleText()) msg.singleText()
        else msg.contents().filterIsInstance<TextContent>().joinToString("\n") { it.text() }

    private suspend fun send(text: String): Any? {
        val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
        XLog.i(TAG, "chat: roundSend backend=$activeBackendLabel sendCount=$sendCount chars=${text.length}")
        val benchStart = System.currentTimeMillis()
        val response = withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val job = currentCoroutineContext()[Job]
            convLock.lock()
            try {
                if (conversation !== conv || pendingClose.contains(conv)) {
                    throw CancellationException("LiteRT-LM conversation was closed")
                }
                inFlight = conv
                sendCancellable(conv, text) { job?.isActive == false }
            } finally {
                inFlight = null
                // close() gave up waiting for this call: close the conversation now that native returned.
                if (pendingClose.remove(conv)) {
                    try { conv.close() } catch (e: Exception) { XLog.w(TAG, "deferred close error", e) }
                    LocalModelRuntime.releaseSession(conv)
                }
                convLock.unlock()
            }
        }
        sendCount++
        io.agents.pokeclaw.benchmark.BenchmarkRecorder.noteLocalSend(
            System.currentTimeMillis() - benchStart, text.length,
            ((response as? com.google.ai.edge.litertlm.Message)?.contents?.toString()?.length) ?: 0,
        )
        return response
    }

    /**
     * Streams one reply with Conversation.sendMessageAsync(String, MessageCallback) (litertlm 0.16.1) and
     * blocks this thread until the native side reports onDone/onError. While waiting it watches
     * [isCancelled] (the caller's coroutine was cancelled, e.g. Stop) and thread interrupts, and then
     * calls Conversation.cancelProcess() so the decode actually stops instead of running on.
     * The caller holds convLock for the whole call, so close() can never free the conversation mid-decode.
     */
    private fun sendCancellable(
        conv: com.google.ai.edge.litertlm.Conversation,
        text: String,
        isCancelled: () -> Boolean,
    ): Message {
        val done = CountDownLatch(1)
        val reply = StringBuilder()
        val failure = AtomicReference<Throwable?>(null)
        conv.sendMessageAsync(text, object : MessageCallback {
            override fun onMessage(message: Message) {
                val chunk = message.contents.toString()
                synchronized(reply) { reply.append(chunk) }
            }

            override fun onDone() {
                done.countDown()
            }

            override fun onError(throwable: Throwable) {
                failure.set(throwable)
                done.countDown()
            }
        })
        var cancelRequested = false
        while (true) {
            val finished = try {
                done.await(STREAM_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // Keep waiting (native must return before the lock is released), but stop the decode.
                Thread.currentThread().interrupt()
                false
            }
            if (finished) break
            if (!cancelRequested && (isCancelled() || Thread.currentThread().isInterrupted)) {
                cancelRequested = true
                XLog.i(TAG, "send: caller cancelled, stopping local generation (cancelProcess)")
                try { conv.cancelProcess() } catch (e: Exception) { XLog.w(TAG, "cancelProcess error", e) }
            }
            if (Thread.currentThread().isInterrupted) {
                // Clear the flag so await() blocks again instead of spinning; cancel was already sent.
                Thread.interrupted()
            }
        }
        if (cancelRequested) throw CancellationException("Local generation cancelled")
        failure.get()?.let { err ->
            throw (err as? Exception) ?: RuntimeException(err.message ?: "Local generation failed", err)
        }
        return Message.model(synchronized(reply) { reply.toString() })
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        // For now, delegate to blocking chat and simulate streaming
        // LiteRT-LM streaming requires Flow or MessageCallback which needs more integration
        val response = runBlocking { chat(messages, toolSpecs) }
        if (!response.text.isNullOrEmpty()) {
            listener.onPartialText(response.text)
        }
        listener.onComplete(response)
        return response
    }

    /** Parse LiteRT-LM response into LlmResponse using raw-string tool call parsing. */
    private fun parseResponse(response: Any?, allowedTools: Set<String>): LlmResponse {
        if (response is Message) {
            val rawText = response.contents.toString().trim()
            val parseResult = extractToolCalls(rawText, allowedTools)
            if (parseResult.hasToolCalls) {
                // Text before the action only (anything after it is usually a made-up result).
                val actionStart = ACTION_NAME_PATTERN.findAll(rawText).firstOrNull {
                    it.value.startsWith("Action:") || allowedTools.isEmpty() || it.groupValues[1].trim() in allowedTools
                }?.range?.first ?: rawText.length
                val thinkingText = rawText.substring(0, actionStart).trim().ifEmpty { null }
                return LlmResponse(text = thinkingText, toolExecutionRequests = parseResult.requests)
            }
            return LlmResponse(text = rawText.ifEmpty { null }, toolExecutionRequests = emptyList())
        }

        return LlmResponse(
            text = response?.toString()?.trim()?.ifEmpty { null },
            toolExecutionRequests = emptyList()
        )
    }

    private fun extractToolCalls(text: String, allowedTools: Set<String>): ToolParseResult {
        val calls = mutableListOf<ToolExecutionRequest>()
        var hasToolCalls = false
        val mapType = object : TypeToken<Map<String, Any?>>() {}.type

        ACTION_NAME_PATTERN.findAll(text).forEachIndexed { idx, match ->
            val name = match.groupValues[1].trim()
            val known = allowedTools.isEmpty() || name in allowedTools
            // Lower-case prose like "next action: I will..." is not a tool call.
            if (!known && !match.value.startsWith("Action:")) return@forEachIndexed
            hasToolCalls = true
            if (!known) {
                XLog.w(TAG, "Unknown tool in action: $name")
                return@forEachIndexed
            }
            // Balanced-brace scan (old lazy regex cut JSON at the first "}" and broke nested args).
            val argsJson = balancedJsonAfter(text, match.range.last + 1) ?: if (name in ZERO_ARG_TOOLS) "{}" else null
            if (argsJson == null) {
                XLog.w(TAG, "No JSON args found for action $name")
                return@forEachIndexed
            }
            try {
                GSON.fromJson<Map<String, Any?>>(argsJson, mapType)
                calls.add(
                    ToolExecutionRequest.builder()
                        .id("local_${System.currentTimeMillis()}_$idx")
                        .name(name)
                        .arguments(argsJson)
                        .build()
                )
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to parse tool JSON: $argsJson", e)
            }
        }
        // The loop runs one action per round; extra actions in the same reply were guesses.
        return ToolParseResult(requests = calls.take(1), hasToolCalls = hasToolCalls)
    }

    private fun balancedJsonAfter(text: String, from: Int): String? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '{') return null
        var depth = 0
        var inString = false
        var escape = false
        for (j in i until text.length) {
            val c = text[j]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(i, j + 1) }
            }
        }
        return null
    }

    override fun close() {
        XLog.i(TAG, "close() — closing conversation only (engine stays in EngineHolder)")
        val conv = conversation
        conversation = null
        releaseConversation(conv)
        engine = null
        processedMessageCount = 0
        pendingRecap = false
        XLog.i(TAG, "close() — done")
    }

    companion object {
        private const val TAG = "LocalLlmClient"

        private val ACTION_NAME_PATTERN = Regex(
            pattern = """Action:\s*`?([a-zA-Z0-9_]+)`?""",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        private const val STREAM_POLL_MS = 100L // how often a running local reply checks for Stop
        private const val CLOSE_WAIT_MS = 1500L // close() may run on the main thread (Stop): keep it short
        private const val UNPARSEABLE_ACTION_TEXT = "I couldn't complete that step on the phone."
        private const val MAX_SENDS_PER_SESSION = 4 // keeps a session well inside the KV cache; recap carries state
        private const val LOCAL_MAX_TOOL_RESULT_CHARS = 2000 // was 400: the model could not see the screen
        private const val LOCAL_MAX_USER_CHARS = 3500

        /** Tools the on-device model may use (order = order shown to the model). */
        private val LOCAL_CORE_TOOLS = listOf(
            "get_screen_info", "open_app", "tap_node", "input_text", "system_key",
            "scroll_to_find", "send_message", "place_call", "message_care_contact", "send_distress_signal", "wait", "finish"
        )
        private val ZERO_ARG_TOOLS = setOf("get_screen_info")

        private const val LOCAL_SYSTEM_PROMPT = """You control an Android phone via tools. Screen shows elements as: [n1] "text" [flags] (x,y) where n1 is the node ID and (x,y) is the tap target.

TOOL CALLING RULES:
- open_app opens an app. Example:
  Action: open_app
  {"package_name": "com.whatsapp"}
- tap_node taps an element by node ID. Example:
  Action: tap_node
  {"node_id": "n3"}
- Use input_text(text) to type into focused editable fields
- Use system_key(key) with key="back","home","enter" for navigation
- Use finish(summary) when task is complete
- One tool per turn. Read screen after each action.
- To message someone: use send_message(contact="Name", message="text", app="WhatsApp"). This handles everything automatically.
- CRITICAL: You must use the EXACT contact names and strings provided in the user prompt. Do not shorten, summarize, or simplify names (e.g., if the user says "Kamya Gupta Personal", do not use "Kamya Gupta").
- DO NOT use native Gemma tool tokens (No <|tool_call|>).
- To execute a tool, you MUST output exactly this plain-text format:
- Action: tool_name
- {"arg1": "value1"}
- The arguments MUST be a valid JSON object. Always use standard double quotes.
- Do NOT try to navigate messaging apps manually — always use send_message tool instead."""
    }
}

/**
 * Wraps a LangChain4j ToolSpecification as a LiteRT-LM OpenApiTool.
 * Execution is manual: the model emits raw tool call text, and LocalLlmClient
 * parses and routes it to ToolRegistry outside of the native parser path.
 */
private class DynamicOpenApiTool(private val spec: ToolSpecification) : OpenApiTool {

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String {
        val json = buildMap {
            put("name", spec.name())
            put("description", spec.description() ?: "")
            spec.parameters()?.let { params ->
                put("parameters", schemaToMap(params))
            }
        }
        return gson.toJson(json)
    }

    override fun execute(paramsJsonString: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val parsed: Map<String, Any?> = gson.fromJson(paramsJsonString, mapType) ?: emptyMap()
            val params = LinkedHashMap<String, Any>()
            parsed.forEach { (key, value) ->
                if (value != null) params[key] = value
            }
            val result = ToolRegistry.getInstance().executeTool(spec.name(), params)
            gson.toJson(result)
        } catch (e: Exception) {
            gson.toJson(ToolResult.error("Tool execution failed in native bridge: ${e.message}"))
        }
    }

    private fun schemaToMap(schema: Any?): Map<String, Any> {
        val output = mutableMapOf<String, Any>()
        if (schema == null) {
            output["type"] = "string"
            return output
        }

        val type = when (schema.javaClass.simpleName) {
            "JsonIntegerSchema" -> "integer"
            "JsonNumberSchema" -> "number"
            "JsonBooleanSchema" -> "boolean"
            "JsonObjectSchema" -> "object"
            "JsonArraySchema" -> "array"
            else -> "string"
        }
        output["type"] = type

        invokeMethod(schema, "description")?.toString()?.takeIf { it.isNotBlank() }?.let {
            output["description"] = it
        }

        if (type == "object") {
            val rawProps = invokeMethod(schema, "properties") as? Map<*, *>
            if (!rawProps.isNullOrEmpty()) {
                val mapped = LinkedHashMap<String, Any>()
                rawProps.forEach { (key, value) ->
                    if (key != null) mapped[key.toString()] = schemaToMap(value)
                }
                output["properties"] = mapped
            }

            val required = (invokeMethod(schema, "required") as? Collection<*>)
                ?.mapNotNull { it?.toString() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (required.isNotEmpty()) {
                output["required"] = required
            }
        }

        if (type == "array") {
            val items = invokeMethod(schema, "items")
                ?: invokeMethod(schema, "item")
                ?: invokeMethod(schema, "element")
            if (items != null) {
                output["items"] = schemaToMap(items)
            }
        }

        return output
    }

    private fun invokeMethod(target: Any, methodName: String): Any? {
        return try {
            target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?.invoke(target)
        } catch (_: Exception) {
            null
        }
    }
}
