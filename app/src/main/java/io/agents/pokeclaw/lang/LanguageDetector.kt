// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.lang

/**
 * Script and word-list language guessing for English, Hindi, Marathi and Telugu. Pure Kotlin
 * (no Android), unit-tested on the JVM.
 *
 * - Telugu script is unambiguous: Telugu.
 * - Devanagari is Hindi or Marathi: a few marker words decide; when they do not, the caller keeps
 *   what Sarvam STT said or the previous language.
 * - Latin text is English unless romanised Hindi / Marathi / Telugu words outweigh English
 *   function words ("mujhe milk order karo" is Hindi; "Call Ravi and tell him I am late" is English).
 *   Content words that Hinglish borrows (call, message, order, phone, send) are deliberately not in
 *   the English list, so they never decide the language.
 */
object LanguageDetector {

    enum class Script { LATIN, DEVANAGARI, TELUGU, OTHER, NONE }

    data class ScriptCounts(val latin: Int, val devanagari: Int, val telugu: Int, val other: Int) {
        val total: Int get() = latin + devanagari + telugu + other
    }

    /** [language] null = no idea. [strong] = enough evidence to override a speech-to-text guess. */
    data class TextGuess(val language: ReplyLanguage?, val strong: Boolean)

    private val ENGLISH_WORDS = setOf(
        "i", "im", "me", "my", "mine", "you", "your", "yours", "we", "us", "our", "they", "them", "their",
        "he", "him", "his", "she", "her", "it", "its", "this", "that", "these", "those", "there", "here",
        "the", "a", "an", "is", "am", "are", "was", "were", "be", "been", "being", "to", "of", "and", "or",
        "but", "for", "in", "on", "at", "with", "from", "by", "about", "into", "up", "out", "what", "when",
        "where", "who", "whom", "why", "how", "which", "can", "could", "will", "would", "should", "shall",
        "may", "might", "must", "please", "do", "does", "did", "dont", "doesnt", "didnt", "have", "has",
        "had", "not", "yes", "tell", "show", "check", "today", "tomorrow", "yesterday", "now", "then",
        "weather", "time", "some", "any", "all", "just", "get", "give", "want", "need", "let", "know",
        "thank", "thanks", "hello", "hi", "good", "morning", "evening", "night", "much", "many", "more",
        "if", "so", "too", "very", "again", "after", "before", "say", "said", "find", "read", "make",
        "going", "come", "coming", "late", "sorry", "okay", "fine", "well", "also", "only",
        "remind", "set", "alarm", "buy", "add", "cart", "list", "price", "cost", "news"
    )

    /** Romanised Hindi. Words shared with Marathi ("nahi", "kal", "kar") are here and in Marathi. */
    private val HINDI_ROMAN = setOf(
        "hai", "hain", "hoon", "hun", "tha", "thi", "kya", "kyun", "kyon", "kaise", "kaisa", "kaisi",
        "kitna", "kitni", "kitne", "kab", "kahan", "kaun", "mujhe", "mujhko", "mera", "meri", "mere",
        "tum", "tumhe", "tera", "teri", "aap", "aapka", "aapki", "aapko", "hum", "humein", "hamara", "ko",
        "ka", "ki", "ke", "se", "mein", "main", "par", "pe", "aur", "ya", "bhi", "nahi", "nahin", "mat",
        "karo", "karna", "karni", "karke", "kar", "kardo", "kariye", "kijiye", "bhejo", "bhej", "bhejna",
        "batao", "bataiye", "bolo", "boliye", "dekho", "dikhao", "lagao", "milao", "chahiye", "abhi", "aaj",
        "kal", "wala", "wali", "wale", "haan", "theek", "thik", "accha", "acha", "achha", "beta", "beti",
        "bhai", "didi", "ji", "raha", "rahi", "rahe", "gaya", "gayi", "hua", "hui", "sab", "kuch", "koi",
        "zara", "jaldi", "dobara", "phir", "lekin", "kyunki", "matlab", "yeh", "ye", "woh", "vo", "wo",
        "isko", "usko", "unko", "inko", "liye", "saath", "paas", "dena", "dedo", "lena", "lelo", "aana",
        "jana", "jao", "aao", "suno", "samjha", "samjhao", "padho", "likho", "rakho", "hatao", "band", "kholo"
    )

    private val MARATHI_ROMAN = setOf(
        "aahe", "ahe", "aahet", "ahet", "nahi", "naahi", "kay", "kaay", "mala", "tula", "tumhi", "tumhala",
        "aamhi", "amhi", "aapan", "kara", "kar", "kartay", "karte", "karto", "pathva", "pathav", "pathvaa",
        "sanga", "sang", "saang", "bagha", "bagh", "baghu", "kasa", "kashi", "kase", "kuthe", "kiti", "kevha",
        "hoy", "ho", "nako", "zala", "zali", "jhala", "jhali", "zhala", "aata", "atta", "udya", "kal",
        "aani", "ani", "pan", "mhanun", "mhana", "bola", "lava", "laav", "ugha", "ughad", "hava", "havi",
        "majha", "mazha", "majhi", "mazhi", "tujha", "tuzha", "amcha", "tumcha", "tyala", "tila",
        "la", "chi", "cha", "che", "madhe", "madhye", "sathi", "barobar", "bara", "thamba", "yeto", "yete"
    )

    private val TELUGU_ROMAN = setOf(
        "emi", "enti", "endi", "ela", "elaa", "ekkada", "eppudu", "evaru", "enduku", "entha", "cheyyi",
        "cheyyandi", "cheyandi", "chey", "chesi", "chestunnanu", "chesanu", "cheyali", "pampu", "pampandi",
        "pampinchu", "naaku", "naku", "nenu", "meeru", "miru", "mee", "naa", "nuvvu", "neeku", "avunu",
        "ledu", "vaddu", "kaadu", "sare", "sari", "unnaru", "unnanu", "undi", "unnayi", "kavali", "kaavali",
        "cheppu", "cheppandi", "chudu", "chudandi", "chupinchu", "ivvu", "ivvandi", "tisuko", "teesuko",
        "randi", "raa", "poyi", "vellu", "vellandi", "amma", "nanna", "akka", "anna", "tammudu", "chelli",
        "lo", "ki", "ni", "nunchi", "tho", "gurinchi", "kosam", "ippudu", "inka", "kuda", "koncham",
        "baagunnara", "bagunnara", "dhanyavadalu", "telugulo", "mataladu", "pettu", "pettandi", "teravandi"
    )

    // Devanagari markers. Marathi: आहे, नाही (Hindi says नहीं), मला, ... Hindi: है, क्या, मुझे, ...
    private val HINDI_DEVA = setOf(
        "है", "हैं", "हूँ", "हूं", "था", "थी", "क्या", "क्यों", "कैसे", "कैसा", "कितना", "कितने", "कब", "कहाँ",
        "कहां", "मुझे", "मेरा", "मेरी", "मेरे", "आप", "आपका", "आपकी", "आपको", "को", "का", "की", "के", "से",
        "में", "पर", "और", "भी", "नहीं", "नही", "करो", "करना", "कीजिए", "करिए", "भेजो", "भेजना", "बताओ",
        "बताइए", "बोलो", "बोलिए", "दिखाओ", "लगाओ", "मिलाओ", "चाहिए", "अभी", "आज", "कल", "वाला", "वाली", "हाँ",
        "हां", "ठीक", "रहा", "रही", "रहे", "गया", "गई", "हुआ", "यह", "वह", "लिए", "साथ", "अब", "दो", "दीजिए"
    )
    private val MARATHI_DEVA = setOf(
        "आहे", "आहेत", "नाही", "काय", "मला", "तुला", "तुम्ही", "तुम्हाला", "आम्ही", "आपण", "करा", "करते", "करतो",
        "पाठवा", "पाठव", "सांगा", "सांग", "बघा", "बघ", "कसा", "कशी", "कसे", "कुठे", "किती", "केव्हा", "होय",
        "नको", "झाले", "झालं", "झाला", "झाली", "आता", "उद्या", "आणि", "पण", "म्हणून", "म्हणा", "बोला", "लावा",
        "उघडा", "हवं", "हवे", "हवा", "हवी", "माझा", "माझी", "माझे", "तुझा", "आमचा", "तुमचा", "त्याला", "तिला",
        "मध्ये", "साठी", "बरोबर", "बरं", "थांबा", "येतो", "येते", "ला", "ची", "चा", "चे", "केलं", "केले"
    )

    /** Yes/no and filler words: a one- or two-word reply made only of these never flips the language. */
    private val FILLERS = setOf(
        "haan", "han", "ha", "haa", "haanji", "hanji", "ji", "ok", "okay", "okk", "yes", "yeah", "no", "na",
        "naa", "nahi", "theek", "thik", "hai", "accha", "acha", "hmm", "hm", "sare", "sari", "avunu", "ledu",
        "vaddu", "hoy", "ho", "nako", "thanks", "thank", "bas", "chalo", "sure",
        "हाँ", "हां", "हा", "जी", "ठीक", "है", "ना", "नहीं", "होय", "हो", "नको", "नाही", "अच्छा", "बस",
        "అవును", "సరే", "లేదు", "వద్దు", "కాదు"
    )

    private val WORD = Regex("[\\p{L}\\p{M}]+")
    private val QUOTED = Regex("\"[^\"]*\"|“[^”]*”|‘[^’]*’|(?<![\\p{L}])'[^']*'(?![\\p{L}])")

    @JvmStatic
    fun words(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }.toList()

    @JvmStatic
    fun scriptCounts(text: String): ScriptCounts {
        var latin = 0; var deva = 0; var tel = 0; var other = 0
        for (ch in text) {
            when {
                ch in '\u0900'..'\u097F' || ch in '\uA8E0'..'\uA8FF' -> deva++
                ch in '\u0C00'..'\u0C7F' -> tel++
                (ch in 'a'..'z') || (ch in 'A'..'Z') -> latin++
                Character.isLetter(ch) -> other++
            }
        }
        return ScriptCounts(latin, deva, tel, other)
    }

    /**
     * The script that carries the sentence. An Indic script counts once it is at least a fifth of the
     * letters, so code-mixed "मेरा phone number है" is Devanagari, not Latin.
     */
    @JvmStatic
    fun dominantScript(text: String): Script {
        val c = scriptCounts(text)
        val total = c.total
        if (total == 0) return Script.NONE
        val indicFloor = maxOf(2, (total * 0.2).toInt())
        return when {
            c.telugu >= indicFloor && c.telugu >= c.devanagari -> Script.TELUGU
            c.devanagari >= indicFloor -> Script.DEVANAGARI
            c.telugu >= indicFloor -> Script.TELUGU
            c.latin > 0 && c.latin >= c.other -> Script.LATIN
            c.other > 0 -> Script.OTHER
            else -> Script.NONE
        }
    }

    /** [text] with quoted parts removed (dictated message text), unless nothing else is left. */
    @JvmStatic
    fun withoutQuotes(text: String): String {
        val stripped = QUOTED.replace(text, " ")
        return if (WORD.containsMatchIn(stripped)) stripped else text
    }

    /** "haan", "ok", "theek hai", a single word: too little to switch the reply language on. */
    @JvmStatic
    fun isShortUtterance(text: String): Boolean {
        val w = words(text)
        if (w.size <= 1) return true
        return w.size <= 3 && w.all { it in FILLERS }
    }

    @JvmStatic
    fun guessFromText(text: String): TextGuess {
        val w = words(text)
        if (w.isEmpty()) return TextGuess(null, false)
        return when (dominantScript(text)) {
            Script.TELUGU -> TextGuess(ReplyLanguage.TE, true)
            Script.DEVANAGARI -> {
                val hi = w.count { it in HINDI_DEVA }
                val mr = w.count { it in MARATHI_DEVA }
                when {
                    mr > hi -> TextGuess(ReplyLanguage.MR, mr >= 2)
                    hi > mr -> TextGuess(ReplyLanguage.HI, hi >= 2)
                    else -> TextGuess(null, false)
                }
            }
            Script.LATIN -> {
                val en = w.count { it in ENGLISH_WORDS }
                val hi = w.count { it in HINDI_ROMAN }
                val mr = w.count { it in MARATHI_ROMAN }
                val te = w.count { it in TELUGU_ROMAN }
                val best = maxOf(hi, mr, te)
                // Ties go Hindi > Marathi > Telugu (shared words are mostly Hindi in practice).
                val indic = when (best) {
                    0 -> null
                    hi -> ReplyLanguage.HI
                    mr -> ReplyLanguage.MR
                    else -> ReplyLanguage.TE
                }
                when {
                    indic == null && en == 0 -> TextGuess(null, false)
                    indic == null -> TextGuess(ReplyLanguage.EN, en >= 2)
                    best >= en -> TextGuess(indic, best >= 2)
                    else -> TextGuess(ReplyLanguage.EN, en >= 2 && en >= best * 2)
                }
            }
            Script.OTHER, Script.NONE -> TextGuess(null, false)
        }
    }

    /**
     * Language to voice [text] in (Sarvam target code / Android TTS locale), judged from the text
     * itself, never only from what the user last spoke: Telugu script -> Telugu; Devanagari -> the
     * current Hindi/Marathi; Latin -> English, unless it is clearly romanised Indic (old Latin-script
     * lines like "Theek hai, kar diya"). Quoted message text does not count. A Latin English reply
     * therefore never gets the hi-IN voice.
     */
    @JvmStatic
    fun ttsLanguageFor(text: String, current: ReplyLanguage): ReplyLanguage {
        val body = withoutQuotes(text)
        return when (dominantScript(body)) {
            Script.TELUGU -> ReplyLanguage.TE
            Script.DEVANAGARI -> when (current) {
                ReplyLanguage.HI, ReplyLanguage.MR -> current
                else -> if (guessFromText(body).language == ReplyLanguage.MR) ReplyLanguage.MR else ReplyLanguage.HI
            }
            Script.LATIN -> {
                val g = guessFromText(body)
                if (g.strong && g.language != null && g.language != ReplyLanguage.EN) g.language else ReplyLanguage.EN
            }
            Script.OTHER, Script.NONE -> current
        }
    }

    /** True when [text] (outside quotes) is already written the way [lang] should be. */
    @JvmStatic
    fun isWrittenIn(text: String, lang: ReplyLanguage): Boolean {
        val body = withoutQuotes(text)
        return when (dominantScript(body)) {
            Script.TELUGU -> lang == ReplyLanguage.TE
            Script.DEVANAGARI -> lang == ReplyLanguage.HI || lang == ReplyLanguage.MR
            Script.LATIN -> lang == ReplyLanguage.EN && guessFromText(body).let { it.language == null || it.language == ReplyLanguage.EN || !it.strong }
            Script.OTHER -> false
            Script.NONE -> true
        }
    }
}

/**
 * Picks the reply language for one user turn (spoken or typed). Pure; [ReplyLanguageState] holds
 * the result and applies the user's fixed choice from Settings.
 */
object LanguageDecider {

    /** Below this Sarvam language_probability the STT language is not trusted on its own. */
    const val MIN_PROBABILITY = 0.6

    /**
     * @param previous the language of the last turn.
     * @param sttCode Sarvam's language_code for a spoken turn; null for typed text.
     * @param sttProbability Sarvam's language_probability (null when not returned).
     * @param text the transcript or typed text.
     */
    @JvmStatic
    fun decide(previous: ReplyLanguage, sttCode: String?, sttProbability: Double?, text: String): ReplyLanguage {
        if (text.isBlank() || LanguageDetector.isShortUtterance(text)) return previous
        val guess = LanguageDetector.guessFromText(text)
        val stt = ReplyLanguage.fromCode(sttCode)
        val sttTrusted = stt != null && (sttProbability == null || sttProbability >= MIN_PROBABILITY)
        val typed = sttCode == null

        return when (LanguageDetector.dominantScript(text)) {
            LanguageDetector.Script.TELUGU -> ReplyLanguage.TE
            LanguageDetector.Script.DEVANAGARI -> when {
                sttTrusted && (stt == ReplyLanguage.HI || stt == ReplyLanguage.MR) -> stt!!
                guess.language != null && (guess.strong || typed) -> guess.language
                previous == ReplyLanguage.HI || previous == ReplyLanguage.MR -> previous
                else -> guess.language ?: ReplyLanguage.HI
            }
            LanguageDetector.Script.LATIN -> when {
                guess.strong && guess.language != null -> guess.language
                sttTrusted && (guess.language == null || guess.language == stt) -> stt!!
                typed && guess.language != null -> guess.language
                else -> previous
            }
            LanguageDetector.Script.OTHER, LanguageDetector.Script.NONE -> previous
        }
    }
}
