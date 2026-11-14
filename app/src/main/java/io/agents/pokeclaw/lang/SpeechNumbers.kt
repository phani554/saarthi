// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.lang

/**
 * Rewrites numbers in a reply so the voice reads them the way a person would. Pure Kotlin.
 *
 * - Phone numbers (10-digit Indian mobiles, optional +91 / 91 / 0 prefix, any spaces or dashes) are
 *   read digit by digit in two groups: "98765 43210" -> "9 8 7 6 5, 4 3 2 1 0". The country code is
 *   dropped (an elderly listener needs the 10 digits). Other runs of 10+ digits are also read digit
 *   by digit, in groups of 5.
 * - OTP / PIN / code values are read digit by digit.
 * - Amounts with ₹ / Rs / INR become "<number> rupees" in the reply language (रुपये, రూపాయలు),
 *   with Indian comma grouping above 4 digits ("₹100000" -> "1,00,000 rupees"). The word "rupees"
 *   is used instead of "Rs" because every voice reads it the same way.
 * - Other plain numbers of 5 to 9 digits get Indian commas (Sarvam: "for numbers larger than
 *   4 digits, use commas"). Numbers of 4 digits or fewer, decimals, times (10:30) and dates stay.
 */
object SpeechNumbers {

    private val AMOUNT = Regex("""(?:₹|\bRs\.?|\bINR\b)\s*(\d[\d,]*(?:\.\d{1,2})?)(?:\s*/-)?""", RegexOption.IGNORE_CASE)
    private val CODE = Regex("""(?i)(\b(?:otp|pin|code|ओटीपी|कोड|పిన్|కోడ్)\b\D{0,15}?)(\d{4,8})(?!\d)""")
    private val PHONE = Regex("""(?<![\d,.])(?:(?:\+\s?91|\b91|\b0)[\s-]?)?([6-9]\d{4})[\s-]?(\d{5})(?![\d,.]*\d)""")
    private val LONG_DIGITS = Regex("""(?<![\d,.])\d{10,}(?![\d,.]*\d)""")
    private val PLAIN = Regex("""(?<![\d,.:/+])\d{5,9}(?![\d:/]|[.,]\d)""")

    @JvmStatic
    fun rupeeWord(lang: ReplyLanguage): String = when (lang) {
        ReplyLanguage.EN -> "rupees"
        ReplyLanguage.HI, ReplyLanguage.MR -> "रुपये"
        ReplyLanguage.TE -> "రూపాయలు"
    }

    @JvmStatic
    fun normalize(text: String, lang: ReplyLanguage): String {
        if (text.none { it.isDigit() }) return text
        var out = AMOUNT.replace(text) { m -> formatAmount(m.groupValues[1]) + " " + rupeeWord(lang) }
        out = CODE.replace(out) { m -> m.groupValues[1] + spaced(m.groupValues[2]) }
        out = PHONE.replace(out) { m -> spaced(m.groupValues[1]) + ", " + spaced(m.groupValues[2]) }
        out = LONG_DIGITS.replace(out) { m -> m.value.chunked(5).joinToString(", ") { spaced(it) } }
        out = PLAIN.replace(out) { m -> if (m.value.startsWith("0")) m.value else indianGrouping(m.value) }
        return out
    }

    /** "12345" -> "1 2 3 4 5". */
    @JvmStatic
    fun spaced(digits: String): String = digits.filter { it.isDigit() }.toCharArray().joinToString(" ")

    /** "1234567" -> "12,34,567"; up to 4 digits unchanged. */
    @JvmStatic
    fun indianGrouping(digits: String): String {
        val d = digits
        if (d.length <= 4) return d
        val last3 = d.takeLast(3)
        val rest = d.dropLast(3)
        val groups = rest.reversed().chunked(2).map { it.reversed() }.reversed()
        return groups.joinToString(",") + "," + last3
    }

    /** "1,00,000.00" / "100000" -> "1,00,000"; "99.50" stays "99.50". */
    @JvmStatic
    fun formatAmount(raw: String): String {
        val clean = raw.replace(",", "")
        val intPart = clean.substringBefore('.')
        val frac = clean.substringAfter('.', "")
        val grouped = indianGrouping(intPart)
        return if (frac.isEmpty() || frac.all { it == '0' }) grouped else "$grouped.$frac"
    }
}
