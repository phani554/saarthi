// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import io.agents.pokeclaw.utils.XLog

/**
 * Tier 1: Deterministic task parser.
 * Matches user input against regex patterns to resolve tasks that can be
 * handled with a direct Android intent or direct tool — zero LLM calls, < 100ms response time.
 */
object TaskParser {

    private const val TAG = "TaskParser"

    data class ParseResult(
        val action: String,
        val intent: Intent?,
        val toolName: String? = null,
        val toolParams: Map<String, Any>? = null,
        val description: String = ""
    )

    /**
     * Try to parse a task into a direct action.
     * Returns null if no pattern matches (falls through to Tier 2).
     */
    fun parse(task: String, installedPackages: List<String> = emptyList()): ParseResult? {
        val lower = task.lowercase().trim()

        return matchMemoryStatement(lower, task)
            ?: matchMessageToCart(lower, task)
            ?: matchWhatsAppCall(lower, task)
            ?: matchSendMessage(lower, task)
            ?: matchCall(lower, task)
            ?: matchSms(lower, task)
            ?: matchAlarm(lower, task)
            ?: matchTimer(lower, task)
            ?: matchScreenshot(lower)
            ?: matchBackHome(lower)
            ?: matchOpenUrl(lower, task)
            ?: matchOpenSettings(lower)
            ?: matchOpenApp(lower, task, installedPackages)
    }

    // ==================== Pattern Matchers ====================

    private val MESSAGE_TO_CART_PATTERN = Regex(
        """(?:read\s+(.+?)['’]?s\s+message|read\s+.*message\s+from\s+(.+?))\s+(?:and\s+|to\s+)?(?:order|buy|add)\s+.*?(?:on|from|using)\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchMessageToCart(lower: String, original: String): ParseResult? {
        val match = MESSAGE_TO_CART_PATTERN.find(original) ?: return null
        val contact = match.groupValues[1].ifBlank { match.groupValues[2] }.trim().removeSuffix("'s")
        val storeApp = match.groupValues[3].trim()

        if (contact.isBlank() || storeApp.isBlank()) return null

        XLog.i(TAG, "TaskParser matched MessageToCart: contact='$contact', store='$storeApp'")
        return ParseResult(
            action = "message_to_cart",
            intent = null,
            toolName = "message_to_cart",
            toolParams = mapOf("contact" to contact, "store_app" to storeApp),
            description = "Reading message from $contact and ordering items on $storeApp"
        )
    }

    private val MEMORY_STATEMENT_PATTERN = Regex(
        """(?:remember\s+that\s+|note\s+that\s+|my\s+(?:favorite|preferred|project|address)\s+is\s+|set\s+my\s+|update\s+my\s+)(.+)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchMemoryStatement(lower: String, original: String): ParseResult? {
        val match = MEMORY_STATEMENT_PATTERN.find(original) ?: return null
        val fact = match.groupValues[1].trim()
        if (fact.isBlank()) return null

        XLog.i(TAG, "TaskParser matched Memory Statement: '$fact'")
        return ParseResult(
            action = "update_memory",
            intent = null,
            toolName = "update_memory",
            toolParams = mapOf("old_fact" to fact, "new_fact" to fact),
            description = "Updated memory with '$fact'"
        )
    }

    private val WHATSAPP_CALL_PATTERN = Regex(
        """(?:open\s+a\s+|start\s+a\s+|make\s+a\s+)?whatsapp\s+(video|voice)?\s*call\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE
    )

    private fun matchWhatsAppCall(lower: String, original: String): ParseResult? {
        val match = WHATSAPP_CALL_PATTERN.find(lower) ?: return null
        val mode = match.groupValues[1].lowercase()
        val contact = match.groupValues[2].trim()

        if (contact.isBlank()) return null

        val callType = if (mode.contains("video")) "whatsapp_video" else "whatsapp_voice"
        XLog.i(TAG, "TaskParser matched WhatsApp Call: type=$callType, contact='$contact'")

        return ParseResult(
            action = "place_call",
            intent = null,
            toolName = "place_call",
            toolParams = mapOf("contact" to contact, "call_type" to callType),
            description = "Starting $callType with $contact"
        )
    }

    private val SEND_MSG_PATTERN = Regex(
        """(?:send\s+(?:a\s+)?message\s+(?:to\s+)?|send\s+hi\s+to\s+|tell\s+|text\s+)(.+?)\s+(?:saying|about|to|and\s+ask\s+her|and\s+ask\s+him|for)?\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchSendMessage(lower: String, original: String): ParseResult? {
        val match = SEND_MSG_PATTERN.find(original) ?: return null
        val contact = match.groupValues[1].trim()
        val msgText = match.groupValues[2].trim()

        if (contact.isBlank() || msgText.isBlank()) return null

        XLog.i(TAG, "TaskParser matched Send Message: contact='$contact', msg='$msgText'")

        return ParseResult(
            action = "send_message",
            intent = null,
            toolName = "send_message",
            toolParams = mapOf("contact" to contact, "message" to msgText),
            description = "Sending message to $contact"
        )
    }

    private val CALL_PATTERN = Regex(
        """(?:call|phone|ring|dial|打電話|打畀|致電)\s+(.+)""", RegexOption.IGNORE_CASE
    )

    private fun matchCall(lower: String, original: String): ParseResult? {
        val match = CALL_PATTERN.find(lower) ?: return null
        val target = match.groupValues[1].trim()
        val numberMatch = Regex("""[\d\s\-+()]{7,}""").find(target)
        return if (numberMatch != null) {
            val number = numberMatch.value.replace(Regex("""[\s\-()]"""), "")
            ParseResult(
                action = "call",
                intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
                description = "Dialing $number"
            )
        } else null
    }

    private val SMS_PATTERN = Regex(
        """(?:sms|text|message|send.*(?:sms|text)|發短訊|發信息)\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE
    )

    private fun matchSms(lower: String, original: String): ParseResult? {
        val match = SMS_PATTERN.find(lower) ?: return null
        val target = match.groupValues[1].trim()
        val numberMatch = Regex("""[\d\s\-+()]{7,}""").find(target)
        return if (numberMatch != null) {
            val number = numberMatch.value.replace(Regex("""[\s\-()]"""), "")
            ParseResult(
                action = "sms",
                intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")),
                description = "Opening SMS to $number"
            )
        } else null
    }

    private val ALARM_PATTERN = Regex(
        """(?:set|create)?\s*(?:alarm|鬧鐘|叫醒|wake\s*(?:me\s*)?up)\s*(?:at|for)?\s*(\d{1,2})[:\s]?(\d{2})?\s*(am|pm)?""",
        RegexOption.IGNORE_CASE
    )

    private fun matchAlarm(lower: String, original: String): ParseResult? {
        val match = ALARM_PATTERN.find(lower) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase()
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0

        return ParseResult(
            action = "alarm",
            intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            },
            description = "Setting alarm for ${String.format("%02d:%02d", hour, minute)}"
        )
    }

    private val TIMER_PATTERN = Regex(
        """(?:set|start)?\s*(?:timer|countdown|計時)\s*(?:for)?\s*(\d+)\s*(second|sec|minute|min|hour|hr|s|m|h)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchTimer(lower: String, original: String): ParseResult? {
        val match = TIMER_PATTERN.find(lower) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val seconds = when {
            unit.startsWith("h") -> amount * 3600
            unit.startsWith("m") -> amount * 60
            else -> amount
        }

        return ParseResult(
            action = "timer",
            intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            },
            description = "Setting timer for $amount ${match.groupValues[2]}"
        )
    }

    private fun matchScreenshot(lower: String): ParseResult? {
        if (!lower.contains("screenshot") && !lower.contains("screencap") &&
            !lower.contains("截圖") && !lower.contains("影相")) return null
        return ParseResult(
            action = "screenshot",
            intent = null,
            toolName = "take_screenshot",
            toolParams = emptyMap(),
            description = "Taking a screenshot"
        )
    }

    private fun matchBackHome(lower: String): ParseResult? {
        return when {
            lower == "go back" || lower == "back" || lower == "返回" ->
                ParseResult("back", null, "system_key", mapOf("key" to "back"), "Going back")
            lower == "go home" || lower == "home" || lower == "返回主頁" ->
                ParseResult("home", null, "system_key", mapOf("key" to "home"), "Going home")
            else -> null
        }
    }

    private val URL_PATTERN = Regex(
        """(?:open|go\s*to|visit|navigate\s*to|打開)\s+(https?://\S+)""", RegexOption.IGNORE_CASE
    )

    private fun matchOpenUrl(lower: String, original: String): ParseResult? {
        val match = URL_PATTERN.find(original) ?: return null
        val url = match.groupValues[1].trim()
        return ParseResult(
            action = "open_url",
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            description = "Opening $url"
        )
    }

    private val SETTINGS_KEYWORDS = mapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "brightness" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "volume" to "android.settings.SOUND_SETTINGS",
        "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "notification" to "android.settings.APP_NOTIFICATION_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
    )

    private fun matchOpenSettings(lower: String): ParseResult? {
        if (!lower.contains("settings") && !lower.contains("設定")) return null

        for ((keyword, action) in SETTINGS_KEYWORDS) {
            if (lower.contains(keyword)) {
                return ParseResult(
                    action = "open_settings",
                    intent = Intent(action),
                    description = "Opening $keyword settings"
                )
            }
        }

        if (lower.matches(Regex(".*(?:open|go to|打開)\\s*(?:the\\s*)?settings.*"))) {
            return ParseResult(
                action = "open_settings",
                intent = Intent(Settings.ACTION_SETTINGS),
                description = "Opening Settings"
            )
        }

        return null
    }

    private val OPEN_APP_PATTERN = Regex(
        """(?:open|launch|start|打開|開)\s+(?:the\s+)?(.+?)(?:\s+app)?$""", RegexOption.IGNORE_CASE
    )

    private fun matchOpenApp(lower: String, original: String, installedPackages: List<String>): ParseResult? {
        val match = OPEN_APP_PATTERN.find(lower) ?: return null
        val appName = match.groupValues[1].trim()

        val lowerApp = appName.lowercase()
        if (lowerApp.contains("call") || lowerApp.contains("video") || lowerApp.contains("message") ||
            lowerApp.contains("chat") || lowerApp.contains("send") || lowerApp.contains("order") ||
            lowerApp.contains("buy") || lowerApp.contains("search") || lowerApp.contains("forward")) {
            return null
        }

        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains("，然後")) return null

        return ParseResult(
            action = "open_app",
            intent = null,
            toolName = "open_app",
            toolParams = mapOf("app_name" to appName),
            description = "Opening $appName"
        )
    }
}
