// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.security

/**
 * Which KVUtils keys hold secrets or personal data and therefore live in the encrypted store
 * ([SecureKeyStore]) instead of the plain default MMKV. Pure Kotlin (JVM-testable).
 *
 * Keep the names in sync with KVUtils / CareProfileStore / MemoryFactStore / WeChatInbound:
 * a key renamed there without updating this list would silently go back to plain storage.
 */
object SecretKeyPolicy {
    /** Exact key names. */
    private val EXACT = setOf(
        // API keys and bot tokens (KVUtils)
        "KEY_LLM_API_KEY",
        "KEY_SARVAM_API_KEY",
        "KEY_MEM0_API_KEY",
        "DEFAULT_DISCORD_BOT_TOKEN",
        "DEFAULT_TELEGRAM_BOT_TOKEN",
        "DEFAULT_WECHAT_BOT_TOKEN",
        // Care profile: medical history, medicines, family phone numbers (CareProfileStore)
        "KEY_CARE_PROFILE_V1",
        // Remembered facts about the user and their journal (MemoryFactStore)
        "KEY_MEMORY_FACTS_V1",
        "KEY_MEMORY_FACTS_JOURNAL_V1",
    )

    /** Key prefixes: per-provider LLM keys ("KEY_LLM_API_KEY_OPENROUTER", ...) and WeChat context tokens. */
    private val PREFIXES = listOf(
        "KEY_LLM_API_KEY_",
        "WECHAT_CONTEXT_TOKENS_",
    )

    @JvmStatic
    fun isSecret(key: String): Boolean = key in EXACT || PREFIXES.any { key.startsWith(it) && key.length > it.length }

    /** The keys among [keys] that must move to the encrypted store. */
    @JvmStatic
    fun secretsAmong(keys: Array<String>?): List<String> = keys.orEmpty().filter { isSecret(it) }
}
