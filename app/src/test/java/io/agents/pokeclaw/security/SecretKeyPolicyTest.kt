// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretKeyPolicyTest {
    @Test fun apiKeysAndTokensAreSecret() {
        listOf(
            "KEY_LLM_API_KEY", "KEY_LLM_API_KEY_OPENROUTER", "KEY_LLM_API_KEY_GOOGLE",
            "KEY_SARVAM_API_KEY", "KEY_MEM0_API_KEY",
            "DEFAULT_TELEGRAM_BOT_TOKEN", "DEFAULT_DISCORD_BOT_TOKEN", "DEFAULT_WECHAT_BOT_TOKEN",
            "WECHAT_CONTEXT_TOKENS_abc",
        ).forEach { assertTrue(it, SecretKeyPolicy.isSecret(it)) }
    }

    @Test fun personalDataIsSecret() {
        assertTrue(SecretKeyPolicy.isSecret("KEY_CARE_PROFILE_V1"))
        assertTrue(SecretKeyPolicy.isSecret("KEY_MEMORY_FACTS_V1"))
        assertTrue(SecretKeyPolicy.isSecret("KEY_MEMORY_FACTS_JOURNAL_V1"))
    }

    @Test fun settingsStayPlain() {
        listOf(
            "THEME_ID", "KEY_LLM_BASE_URL", "KEY_LLM_MODEL_NAME", "KEY_LLM_PROVIDER", "KEY_SARVAM_LANGUAGE",
            "KEY_LLM_API_KEY_", "KEY_TELEGRAM_OWNER_USER_ID", "KEY_LOCAL_MODEL_PATH",
        ).forEach { assertFalse(it, SecretKeyPolicy.isSecret(it)) }
    }

    @Test fun secretsAmong() {
        assertEquals(
            listOf("KEY_LLM_API_KEY", "KEY_CARE_PROFILE_V1"),
            SecretKeyPolicy.secretsAmong(arrayOf("THEME_ID", "KEY_LLM_API_KEY", "KEY_CARE_PROFILE_V1"))
        )
        assertEquals(emptyList<String>(), SecretKeyPolicy.secretsAmong(null))
    }
}
