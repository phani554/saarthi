// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

/**
 * One remembered fact about the user. Stored as JSON in MMKV by [MemoryFactStore].
 *
 * key is "category:name", e.g. "person:son", "pref:milk", "health:diabetes", "profile:name",
 * "contact:ravi", "app:blinkit", "alias:project group", "note:<slug>".
 *
 * Every field has a default so Kotlin emits a no-arg constructor and Gson fills missing JSON
 * fields with these defaults instead of null.
 */
data class MemoryFact(
    val id: String = "",
    val key: String = "",
    val value: String = "",
    /** said = user's own words, learned = AI guess, task = seen in a finished task, migrated = old memory file. */
    val source: String = FactSource.SAID,
    val confidence: Float = 0.8f,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Guessed and not yet confirmed by the user: shown as "(not confirmed)" in prompts. */
    val pending: Boolean = false,
    /** Older values for the same key, newest first (max 3). */
    val history: List<String> = emptyList(),
    /** Times seen (contacts called, apps used, repeat orders). */
    val count: Int = 0,
    /** Free detail for counters, e.g. "call" / "WhatsApp message". */
    val detail: String = "",
) {
    val category: String get() = key.substringBefore(':', "note")
    val name: String get() = key.substringAfter(':', key)
}

object FactSource {
    const val SAID = "said"
    const val LEARNED = "learned"
    const val TASK = "task"
    const val MIGRATED = "migrated"
}

/** A fact candidate before it is stored. */
data class FactCandidate(val key: String, val value: String)

/**
 * Pure (no Android) extraction, matching and rendering for [MemoryFact]s, so it can be unit tested.
 * Handles English plus romanised Hindi and Telugu.
 */
object FactExtractor {

    // ── Secrets: never stored, never sent ───────────────────────────────────

    /**
     * PIN/OTP/CVV/passwords, card/Aadhaar/PAN/account numbers, and long digit runs (card-like).
     * "pin code" (postal code) is allowed.
     */
    private val SENSITIVE_PATTERN = Regex(
        """(?i)\b(?:pin\b(?!\s*code)|m-?pin|upi\s*pin|otp|one[\s-]*time\s*(?:password|code)|cvv|cvc|password|passcode|passwd|card\s*(?:number|no)|debit\s*card|credit\s*card|aadhaa?r|pan\s*(?:card|number|no)|account\s*(?:number|no)|net\s*banking|security\s*code|verification\s*code)"""
    )
    private val LONG_DIGIT_RUN = Regex("""\d(?:[\s-]?\d){12,}""")

    fun isSensitive(text: String): Boolean =
        SENSITIVE_PATTERN.containsMatchIn(text) || LONG_DIGIT_RUN.containsMatchIn(text)

    // ── Words ───────────────────────────────────────────────────────────────

    /** Relation words (English, Hindi, Telugu romanised) -> canonical relation. */
    private val RELATIONS: Map<String, String> = buildMap {
        fun add(canon: String, vararg words: String) { words.forEach { this[it] = canon } }
        add("son", "son", "beta", "bete", "koduku", "kodukku", "putra")
        add("daughter", "daughter", "beti", "bitiya", "kuthuru", "kooturu", "kuturu", "ammayi")
        add("husband", "husband", "pati", "patiji", "bharta", "aayana", "mogudu", "shohar")
        add("wife", "wife", "patni", "biwi", "bharya", "aavida", "pellam")
        add("brother", "brother", "bhai", "bhaiya", "bhaiyya", "anna", "tammudu", "annayya")
        add("sister", "sister", "behen", "bahen", "didi", "akka", "chelli", "chellelu")
        add("mother", "mother", "mom", "mummy", "maa", "amma", "mataji")
        add("father", "father", "dad", "papa", "pitaji", "nanna", "babuji")
        add("grandson", "grandson", "pota", "naati", "nati", "manavadu", "manumadu")
        add("granddaughter", "granddaughter", "poti", "naatin", "manavaralu", "manumaralu")
        add("daughter_in_law", "daughter-in-law", "daughter in law", "bahu", "kodalu")
        add("son_in_law", "son-in-law", "son in law", "damad", "jamai", "alludu")
        add("friend", "friend", "dost", "saheli", "snehitudu", "snehituralu")
        add("neighbour", "neighbour", "neighbor", "padosi", "padosan", "pakkintivallu")
        add("maid", "maid", "bai", "kaamwali", "kamwali", "panimanishi")
        add("driver", "driver")
        add("caretaker", "caretaker", "nurse")
    }

    fun relationOf(word: String): String? = RELATIONS[word.trim().lowercase()]

    /** Romanised Hindi/Telugu (and a few English variants) -> canonical English, for matching. */
    private val SYNONYMS: Map<String, String> = buildMap {
        RELATIONS.forEach { (w, c) -> if (!w.contains(' ')) this[w] = c }
        fun add(canon: String, vararg words: String) { words.forEach { this[it] = canon } }
        add("milk", "doodh", "dudh", "paalu", "palu")
        add("curd", "dahi", "perugu")
        add("rice", "chawal", "chaawal", "biyyam")
        add("flour", "atta", "aata")
        add("tea", "chai", "chaay", "tee")
        add("egg", "eggs", "anda", "ande", "guddu")
        add("onion", "onions", "pyaz", "pyaaz", "ullipaya", "ullipayalu")
        add("tomato", "tomatoes", "tamatar", "tamata")
        add("potato", "potatoes", "aloo", "alu", "bangaladumpa")
        add("vegetable", "vegetables", "sabzi", "sabji", "kooragayalu", "kuralu")
        add("fruit", "fruits", "phal", "pandlu")
        add("oil", "tel", "nune", "noone")
        add("water", "paani", "pani", "neellu")
        add("bread", "pav", "roti")
        add("medicine", "medicines", "dawai", "dawa", "davai", "mandu", "mandulu", "goli", "tablet", "tablets")
        add("doctor", "dr", "vaidya", "daktar")
        add("diabetes", "sugar", "madhumeh", "diabetic")
        add("blood_pressure", "bp", "pressure", "hypertension")
        add("call", "phone", "ring", "dial", "calling", "called", "baat")
        add("message", "msg", "messages", "text", "sms", "sandesh")
        add("order", "mangao", "mangwao", "mangana", "kharido", "buy", "ordered", "cheppu")
        add("whatsapp", "whats", "wa")
        add("youtube", "yt")
        add("blood_pressure", "bp")
        add("name", "naam", "peru")
    }

    private val STOP = setOf(
        "a", "an", "the", "to", "of", "and", "or", "on", "in", "at", "for", "with", "me", "my", "is", "are",
        "please", "pls", "plz", "now", "do", "it", "this", "that", "i", "you", "from",
        "ka", "ki", "ke", "ko", "se", "hai", "hain", "karo", "kar", "do", "de", "dena", "mera", "meri", "mere",
        "mujhe", "aur", "bhi", "ek", "jara", "zara", "na", "naa", "naaku", "naku", "ni", "ku", "cheyyi", "chey",
        "undi", "kavali", "karna", "kijiye", "dijiye", "abhi", "wala", "wali", "garu", "ji"
    )

    /** Lowercase words, synonyms folded to canonical English, stop words removed. */
    fun tokens(text: String): Set<String> =
        Regex("""[\p{L}\p{N}_]+""").findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 1 && it !in STOP }
            .map { SYNONYMS[it] ?: it }
            .toSet()

    // ── Health ──────────────────────────────────────────────────────────────

    private data class Condition(val key: String, val label: String, val words: String)
    private val CONDITIONS = listOf(
        Condition("diabetes", "Diabetes (sugar)", """sugar|diabetes|diabetic|madhumeh"""),
        Condition("blood_pressure", "High BP", """high\s+bp|bp|high\s+blood\s*pressure|blood\s*pressure|hypertension|pressure"""),
        Condition("asthma", "Asthma", """asthma|dama|ubbasam"""),
        Condition("thyroid", "Thyroid", """thyroid"""),
        Condition("heart", "Heart problem", """heart\s+(?:problem|disease|trouble)|dil\s+ki\s+bimari|gunde\s+jabbu"""),
        Condition("joint_pain", "Joint / knee pain", """arthritis|knee\s+pain|joint\s+pain|ghutno\s+(?:ka|me|mein)\s+dard|ghutne\s+(?:ka|me|mein)\s+dard|mokalla\s+noppi|kaallu\s+noppi"""),
        Condition("cholesterol", "Cholesterol", """cholesterol"""),
        Condition("kidney", "Kidney problem", """kidney\s+(?:problem|disease|stone)"""),
    )

    /**
     * Same subject, same key: "person:beta" -> "person:son", "health:sugar" -> "health:diabetes",
     * "pref:doodh" -> "pref:milk". [name] is already lowercase with "_" for spaces.
     */
    fun canonicalName(category: String, name: String): String {
        val spaced = name.replace('_', ' ').trim()
        return when (category) {
            "person" -> relationOf(spaced) ?: relationOf(spaced.replace(' ', '-')) ?: name
            "health" -> CONDITIONS.firstOrNull { Regex("""(?i)^(?:${it.words})$""").matches(spaced) || it.key == name }?.key ?: name
            "pref" -> ITEM_WORDS[spaced] ?: name
            else -> name
        }
    }

    /** "I have sugar", "I am diabetic", "mujhe sugar hai", "naaku BP undi", "main sugar ki patient hoon". */
    private fun conditionRegexes(words: String) = listOf(
        Regex("""(?i)\b(?:i\s+have|i\s+suffer\s+from|i\s+am|i'm|i\s+got)\s+(?:a\s+|an\s+|the\s+)?(?:patient\s+of\s+)?(?:high\s+|low\s+)?(?:$words)\b"""),
        Regex("""(?i)\b(?:mujhe|mujhko|muje|mujhe\s+bhi|hamein)\s+(?:high\s+|low\s+)?(?:$words)\s+(?:hai|he|h|ki\s+bimari\s+hai|ki\s+problem\s+hai|ki\s+shikayat\s+hai)\b"""),
        Regex("""(?i)\b(?:main|mai|mein)\s+(?:$words)\s+(?:ka|ki)\s+(?:patient|mariz|mareez)\b"""),
        Regex("""(?i)\b(?:naaku|naku|naku\s+kuda)\s+(?:$words)\s+(?:undi|vundi|unnadi|undhi)\b"""),
    )
    private val CONDITION_RES: List<Pair<Condition, List<Regex>>> by lazy { CONDITIONS.map { it to conditionRegexes(it.words) } }

    /** Words that follow "my son is" but are not names. */
    private val NOT_A_NAME = setOf(
        "not", "nahi", "very", "also", "a", "an", "the", "in", "at", "with", "on", "coming", "going", "sick", "ill",
        "here", "there", "busy", "working", "married", "abroad", "fine", "good", "home", "office", "outside", "late",
        "angry", "happy", "back", "raha", "rahi", "rahe", "gaya", "gayi", "aaya", "aayi", "hoga", "bimar", "ghar",
        "yahan", "wahan", "bahut", "accha", "acha", "theek", "thik", "kahan", "kaha", "kya", "kaun", "abhi",
        "naam", "peru", "name", "number", "phone", "lo", "ki", "ka", "ke", "vachadu", "ledu", "unnadu",
        "aa", "ja", "jaa", "so", "rha", "rhi", "gone", "dead", "late", "no", "yes", "ok", "okay", "sir", "madam",
    )

    // ── Statements the user says about themselves ───────────────────────────

    private const val NAME = """([A-Za-z][A-Za-z.']{1,20}(?:\s+[A-Za-z][A-Za-z.']{1,20})?)"""
    private val NAME_TAIL_STOP = setOf("hai", "he", "h", "hain", "undi", "garu", "ji", "is", "and", "aur", "ane", "ani", "antaru", "hei")

    private val REL_WORDS: String by lazy {
        RELATIONS.keys.sortedByDescending { it.length }.joinToString("|") { k -> k.split(' ').joinToString("\\s+") { Regex.escape(it) } }
    }

    private val PERSON_PATTERNS: List<Pair<Regex, Boolean>> by lazy {
        // Pair(regex, relationFirst): relationFirst -> group1 = relation, group2 = name; else reversed.
        listOf(
            // "my son is Ravi", "my son's name is Ravi", "my son is called Ravi"
            Regex("""(?i)\bmy\s+($REL_WORDS)(?:'s)?(?:\s+name)?\s+is\s+(?:called\s+|named\s+)?$NAME""") to true,
            // "Ravi is my son"
            Regex("""(?i)\b$NAME\s+is\s+my\s+($REL_WORDS)\b""") to false,
            // Hindi: "mera beta Ravi hai", "mere bete ka naam Ravi hai", "meri beti ka naam Sita hai"
            Regex("""(?i)\b(?:mera|meri|mere|hamara|hamari|hamare)\s+($REL_WORDS)\s+(?:ka\s+naam\s+|ki\s+naam\s+|ka\s+name\s+)?$NAME\s+(?:hai|he|h|hain)\b""") to true,
            // Hindi: "Ravi mera beta hai"
            Regex("""(?i)\b$NAME\s+(?:mera|meri|mere)\s+($REL_WORDS)\s+(?:hai|he|h)\b""") to false,
            // Telugu: "naa koduku peru Ravi", "maa ammayi peru Sita"
            Regex("""(?i)\b(?:naa|na|maa|ma)\s+($REL_WORDS)\s+peru\s+$NAME""") to true,
            // Telugu: "Ravi naa koduku"
            Regex("""(?i)\b$NAME\s+(?:naa|maa)\s+($REL_WORDS)\b""") to false,
        )
    }

    private val NAME_PATTERNS = listOf(
        Regex("""(?i)\bmy\s+name\s+is\s+$NAME"""),
        Regex("""(?i)\bmera\s+naam\s+$NAME"""),
        Regex("""(?i)\b(?:naa|na)\s+peru\s+$NAME"""),
    )

    private val ALLERGY = Regex("""(?i)\b(?:i\s+am\s+allergic\s+to|i'm\s+allergic\s+to|i\s+have\s+(?:an\s+)?allergy\s+(?:to|from)|mujhe\s+([A-Za-z][A-Za-z ]{1,25}?)\s+se\s+allergy)\s*([A-Za-z][A-Za-z ]{1,25})?""")

    private val DIET = Regex("""(?i)\b(?:i\s+am|i'm|main|mai|nenu)\s+(?:a\s+|pure\s+)?(vegetarian|vegan|shakahari|eggetarian|jain)\b""")

    private val PREFER = Regex("""(?i)\b(?:i\s+(?:prefer|like|love|always\s+(?:order|buy|use|drink|take))\s+(.{2,60})|mujhe\s+(.{2,40}?)\s+pasand\s+hai|naaku\s+(.{2,40}?)\s+ishtam)""")

    /**
     * Facts stated in one utterance. Each line is checked; secrets are dropped.
     */
    fun extract(text: String): List<FactCandidate> {
        if (text.isBlank() || isSensitive(text)) return emptyList()
        val out = mutableListOf<FactCandidate>()
        for (rawLine in text.split('\n', '.', ';', '?', '!', '।')) {
            val line = rawLine.trim()
            if (line.length < 4) continue

            NAME_PATTERNS.firstNotNullOfOrNull { it.find(line) }?.let { m ->
                cleanName(m.groupValues[1])?.let { out += FactCandidate("profile:name", it) }
            }

            for ((re, relationFirst) in PERSON_PATTERNS) {
                val m = re.find(line) ?: continue
                val relWord = if (relationFirst) m.groupValues[1] else m.groupValues[2]
                val nameWord = if (relationFirst) m.groupValues[2] else m.groupValues[1]
                val rel = relationOf(relWord.replace(Regex("\\s+"), " ")) ?: continue
                val name = cleanName(nameWord) ?: continue
                if (relationOf(name) != null) continue  // "my son is my brother's ..." nonsense
                out += FactCandidate("person:$rel", name)
                break
            }

            CONDITION_RES.filter { (_, res) -> res.any { it.containsMatchIn(line) } }
                .forEach { (c, _) -> out += FactCandidate("health:${c.key}", c.label) }
            ALLERGY.find(line)?.let { m ->
                val what = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
                if (what.length >= 2) out += FactCandidate("health:allergy_${slug(what)}", "Allergic to $what")
            }
            DIET.find(line)?.let { m ->
                val d = m.groupValues[1].lowercase().let { if (it == "shakahari") "vegetarian" else it }
                out += FactCandidate("pref:diet", d.replaceFirstChar { c -> c.uppercase() })
            }
            PREFER.find(line)?.let { m ->
                val what = (1..3).map { m.groupValues[it] }.firstOrNull { it.isNotBlank() }.orEmpty().trim().trimEnd(',', ' ')
                // Only item preferences ("I prefer Amul milk"); "I like to call Ravi" is not a preference.
                val item = Regex("""[\p{L}\p{N}]+""").findAll(what.lowercase()).mapNotNull { ITEM_WORDS[it.value] }.firstOrNull()
                if (item != null && what.length >= 3) out += FactCandidate("pref:$item", what.replaceFirstChar { it.uppercase() })
            }
        }
        return out.distinctBy { it.key }
    }

    private fun cleanName(raw: String): String? {
        fun junk(w: String): Boolean = w.lowercase().trimEnd('.').let { it in NAME_TAIL_STOP || it in STOP || it in NOT_A_NAME }
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        while (words.isNotEmpty() && junk(words.last())) words.removeAt(words.size - 1)
        if (words.isEmpty() || words.any { junk(it) }) return null
        if (words.any { relationOf(it) != null }) return null
        val name = words.joinToString(" ") { w -> w.trim('.', '\'').replaceFirstChar { it.uppercase() } }
        return name.takeIf { it.length in 2..40 }
    }

    fun slug(s: String): String =
        s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_').ifBlank { "x" }

    // ── Forget commands ─────────────────────────────────────────────────────

    /** The whole utterance must be the command: "forget that my son is Ramesh" is not a forget. */
    private val FORGET = Regex(
        """(?i)^\s*(?:(?:please|plz|saathi|saarthi|ok|okay)[\s,]+)*(?:forget\s+(?:that|it|this|what\s+i\s+(?:just\s+)?said|the\s+last\s+(?:one|thing))|don'?t\s+remember\s+(?:that|it|this)|(?:(?:yeh|ye|ise|isko|woh|wo|vo|is)\s+(?:baat\s+)?(?:ko\s+)?)?(?:bhool|bhul)\s+(?:jao|jaao|jaiye|jaye|ja|jaana|jana)|(?:(?:yeh|ye|ise|isko|is\s+baat\s+ko)\s+)?mat\s+yaad\s+rakh(?:o|na|iye|en)?|yaad\s+mat\s+rakh(?:o|na|iye)?|(?:adi\s+)?marchipo(?:ndi)?|gurthu\s+pettukoku)(?:[\s,]+(?:please|plz|na|ji|saathi|saarthi|now|abhi))*\s*[.!]*\s*$"""
    )

    fun isForgetCommand(text: String): Boolean = text.length < 80 && FORGET.containsMatchIn(text.trim())

    // ── Finished tasks ──────────────────────────────────────────────────────

    /** Item word (any language) -> canonical item. */
    private val ITEM_WORDS: Map<String, String> = linkedMapOf(
        "milk" to "milk", "doodh" to "milk", "dudh" to "milk", "paalu" to "milk",
        "curd" to "curd", "dahi" to "curd", "perugu" to "curd", "yogurt" to "curd",
        "bread" to "bread", "eggs" to "eggs", "egg" to "eggs", "ande" to "eggs", "anda" to "eggs",
        "butter" to "butter", "makhan" to "butter", "paneer" to "paneer", "ghee" to "ghee", "neyyi" to "ghee",
        "atta" to "atta", "aata" to "atta", "flour" to "atta",
        "rice" to "rice", "chawal" to "rice", "biyyam" to "rice",
        "tea" to "tea", "chai" to "tea", "coffee" to "coffee",
        "oil" to "oil", "tel" to "oil", "nune" to "oil",
        "dal" to "dal", "pappu" to "dal", "salt" to "salt", "namak" to "salt", "uppu" to "salt",
        "onion" to "onion", "onions" to "onion", "pyaz" to "onion", "pyaaz" to "onion", "ullipaya" to "onion",
        "tomato" to "tomato", "tomatoes" to "tomato", "tamatar" to "tomato",
        "potato" to "potato", "potatoes" to "potato", "aloo" to "potato",
        "banana" to "banana", "bananas" to "banana", "kela" to "banana", "arati" to "banana",
        "water" to "water", "biscuit" to "biscuits", "biscuits" to "biscuits", "soap" to "soap",
        "detergent" to "detergent", "toothpaste" to "toothpaste", "diaper" to "diapers", "diapers" to "diapers",
    )

    private val STORES = linkedMapOf(
        "swiggy instamart" to "Instamart", "instamart" to "Instamart", "blinkit" to "Blinkit", "zepto" to "Zepto",
        "bigbasket" to "BigBasket", "big basket" to "BigBasket", "jiomart" to "JioMart", "amazon" to "Amazon",
        "flipkart" to "Flipkart", "dmart" to "DMart", "swiggy" to "Swiggy", "zomato" to "Zomato",
    )

    private val APPS = linkedMapOf(
        "whatsapp" to "WhatsApp", "youtube" to "YouTube", "phonepe" to "PhonePe", "google pay" to "Google Pay",
        "gpay" to "Google Pay", "paytm" to "Paytm", "ola" to "Ola", "uber" to "Uber", "rapido" to "Rapido",
        "telegram" to "Telegram", "facebook" to "Facebook", "instagram" to "Instagram", "gmail" to "Gmail",
        "maps" to "Maps", "camera" to "Camera", "gallery" to "Gallery", "practo" to "Practo",
        "1mg" to "Tata 1mg", "pharmeasy" to "PharmEasy", "netmeds" to "Netmeds", "apollo" to "Apollo 24|7",
    ) + STORES

    fun appsIn(text: String): List<String> {
        val l = text.lowercase()
        return APPS.entries.filter { (w, _) -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(l) }
            .map { it.value }.distinct()
    }

    private val QTY = Regex(
        """(?i)\b(\d+(?:\.\d+)?|half|aadha|aadhi|ek|do|teen|char|paanch|one|two|three|four|five)\s*(litres?|liters?|ltrs?|l|ml|kgs?|kilo|g|gms?|grams?|packets?|packs?|pkts?|dozen|pcs|pieces?|bottles?|loaf|loaves)\b"""
    )
    private val QTY_UNIT = Regex("""(?i)(litres?|liters?|ltrs?|l|ml|kgs?|kilo|g|gms?|grams?|packets?|packs?|pkts?|dozen|pcs|pieces?|bottles?|loaf|loaves)""")
    private val NUM_WORDS = mapOf(
        "half" to "0.5", "aadha" to "0.5", "aadhi" to "0.5", "ek" to "1", "one" to "1", "do" to "2", "two" to "2",
        "teen" to "3", "three" to "3", "char" to "4", "four" to "4", "paanch" to "5", "five" to "5"
    )
    private fun unit(u: String): String = when (u.lowercase().trimEnd('s')) {
        "litre", "liter", "ltr", "l" -> "L"
        "ml" -> "ml"
        "kg", "kilo" -> "kg"
        "g", "gm", "gram" -> "g"
        "packet", "pack", "pkt" -> "packet"
        "dozen" -> "dozen"
        "pc", "pcs", "piece" -> "pcs"
        "bottle" -> "bottle"
        else -> u.lowercase()
    }

    private val BRAND_STOP = setOf(
        "order", "buy", "get", "add", "some", "the", "a", "an", "of", "and", "me", "my", "fresh", "packet", "packets",
        "litre", "liter", "l", "kg", "ml", "ek", "do", "teen", "aur", "se", "par", "pe", "on", "from", "please",
        "mangao", "mangwao", "cart", "to", "in", "toned", "full", "cream", "double", "low", "fat", "with", "for", "half",
    )

    private val ITEM_SEPARATORS = setOf("and", "aur", "&", "plus", "also", "then", "phir", "mariyu")

    private val ORDER_VERB = Regex("""(?i)\b(order|buy|get|add|mangao|mangwao|mangana|kharido|bhejo|teppinchu|konu|cart)\b""")

    /** "Order Amul milk 1 litre on Blinkit" -> pref:milk = "Amul 1 L (Blinkit)". */
    fun orderPreferences(task: String): List<FactCandidate> {
        if (!ORDER_VERB.containsMatchIn(task)) return emptyList()
        val store = STORES.entries.firstOrNull { (w, _) -> Regex("""(?i)\b${Regex.escape(w)}\b""").containsMatchIn(task) }?.value
        val words = Regex("""[\p{L}\p{N}.]+""").findAll(task).toList()
        val out = mutableListOf<FactCandidate>()
        fun brandWord(p: String?): Boolean {
            if (p == null) return false
            val pl = p.lowercase()
            return pl.length >= 2 && pl !in BRAND_STOP && pl !in NUM_WORDS && !pl.first().isDigit() &&
                ITEM_WORDS[pl] == null && STORES.keys.none { it == pl } && !QTY_UNIT.matches(pl)
        }
        fun qtyIn(from: Int, to: Int): String? {
            if (from > to || from < 0 || to >= task.length) return null
            return QTY.find(task.substring(from, to + 1))?.let { m ->
                val n = NUM_WORDS[m.groupValues[1].lowercase()] ?: m.groupValues[1]
                "$n ${unit(m.groupValues[2])}"
            }
        }
        for ((i, w) in words.withIndex()) {
            val item = ITEM_WORDS[w.value.lowercase()] ?: continue
            // Brand: the word(s) right before the item ("Amul milk", "Mother Dairy milk").
            val prev = words.getOrNull(i - 1)?.value
            val prev2 = words.getOrNull(i - 2)?.value
            val brand = if (brandWord(prev)) {
                val two = prev2 != null && brandWord(prev2) && prev2.first().isUpperCase() && prev!!.first().isUpperCase()
                (if (two) "$prev2 $prev" else prev!!).split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            } else null
            // Quantity: nearest after the item (up to 3 words), else just before it (up to 4 words);
            // never across "and"/"aur" or another item ("milk and 1 loaf bread").
            fun boundary(j: Int): Boolean = words[j].value.lowercase().let { it in ITEM_SEPARATORS || ITEM_WORDS[it] != null }
            var endIdx = i
            while (endIdx + 1 < words.size && endIdx + 1 <= i + 3 && !boundary(endIdx + 1)) endIdx++
            var startIdx = i
            while (startIdx - 1 >= 0 && startIdx - 1 >= i - 4 && !boundary(startIdx - 1)) startIdx--
            val after = if (endIdx > i) qtyIn(w.range.first, words[endIdx].range.last) else null
            val before = if (after == null && startIdx < i) qtyIn(words[startIdx].range.first, w.range.last) else null
            val qty = after ?: before
            if (brand == null && qty == null) continue  // "order milk" alone says nothing new
            val value = listOfNotNull(brand, qty).joinToString(" ") + (store?.let { " ($it)" } ?: "")
            out += FactCandidate("pref:$item", value.trim())
        }
        return out.distinctBy { it.key }
    }

    // ── Usual products, learned from what actually went into the cart ──────────

    /** "ITEMS: Everest Garam Masala 100 g; Amul Taaza Milk 1 L" (last line of a cart task's finish summary). */
    private val ITEMS_LINE = Regex("""(?im)^\s*ITEMS\s*:\s*(.+)$""")

    /** The finish summary without its ITEMS line: what she sees and hears. */
    fun stripItemsLine(summary: String): String =
        summary.replace(Regex("""(?im)\n?^\s*ITEMS\s*:.*$"""), "").trim().ifEmpty { summary.trim() }

    /** Products listed on the ITEMS line, cleaned. Empty when there is none. */
    fun cartItems(summary: String): List<String> =
        ITEMS_LINE.find(summary)?.groupValues?.get(1).orEmpty()
            .split(';', '|')
            .map { it.replace(Regex("""\s*[₹(]?\s*₹\s*[\d,.]+\)?"""), "").replace(Regex("""\s+"""), " ").trim().trim(',', '.') }
            .filter { it.length in 3..80 }

    private val REQUEST_NOISE = Regex(
        """(?i)\b(order|buy|get|add|mangao|mangwao|mangana|kharido|bhejo|cart|from|on|se|par|pe|please|me|my|for|to|the|some|ek|do|teen|and|aur|also|plus|ko|ka|ki|ke)\b"""
    )

    /**
     * Items she asked for, in her words: "order garam masala and milk from Flipkart Minutes" ->
     * ["garam masala", "milk"]. Stores and quantities are dropped.
     */
    fun requestedItems(request: String): List<String> {
        var t = request.lowercase()
        STORES.keys.sortedByDescending { it.length }.forEach { t = t.replace(Regex("""\b${Regex.escape(it)}\b"""), ",") }
        t = t.replace("minutes", ",")
        t = QTY.replace(t, ",")
        t = t.replace(Regex("""\b(and|aur|plus|also|then|phir|mariyu|&)\b"""), ",")
        return t.split(',', '.', ';', '\n')
            .map { REQUEST_NOISE.replace(it, " ").replace(Regex("""[^\p{L}\p{N} ]"""), " ").replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length >= 3 }
            .distinct()
    }

    /**
     * pref:<item> = exact product, for each cart product that matches something she asked for
     * ("garam masala" -> "Everest Garam Masala 100 g (Flipkart Minutes)"). A product that matches
     * nothing she said is skipped: the model may have added it by mistake.
     */
    fun cartPreferences(request: String, summary: String): List<FactCandidate> {
        val products = cartItems(summary)
        if (products.isEmpty()) return emptyList()
        val store = STORES.entries.firstOrNull { (w, _) -> Regex("""(?i)\b${Regex.escape(w)}\b""").containsMatchIn(request) }?.value
            ?.let { if (it == "Flipkart" && request.contains("minutes", true)) "Flipkart Minutes" else it }
        val asked = requestedItems(request)
        val out = mutableListOf<FactCandidate>()
        for (product in products) {
            val pTokens = tokens(product)
            val phrase = asked.firstOrNull { a -> tokens(a).let { it.isNotEmpty() && pTokens.containsAll(it) } }
                ?: continue
            val key = ITEM_WORDS[phrase] ?: slug(phrase)
            out += FactCandidate("pref:$key", product + (store?.let { " ($it)" } ?: ""))
        }
        return out.distinctBy { it.key }
    }

    private val CONTACT_PATTERNS = listOf(
        // English: "call Ravi", "message Ravi on WhatsApp", "send a message to Ravi", "video call Ravi"
        Regex("""(?i)\b(call|phone|ring|dial|message|text|msg|whatsapp)\s+(?:to\s+)?(?:my\s+)?$NAME"""),
        Regex("""(?i)\bsend\s+(?:a\s+)?(?:message|msg|text)\s+to\s+(?:my\s+)?$NAME"""),
        // Hindi/Telugu: "Ravi ko call karo", "beta ko message bhejo", "Ravi ki phone cheyyi"
        Regex("""(?i)\b$NAME\s+(?:ko|ku|ki|ke\s+liye)\s+(call|phone|message|msg|whatsapp|sms|text)"""),
    )
    private val READ_ONLY = Regex("""(?i)\b(read|check|show|padho|padhke|sunao|dikhao|chaduvu|chupinchu)\b.*\b(message|messages|msg|sms)\b""")
    private val NOT_A_CONTACT = setOf(
        "me", "him", "her", "them", "back", "now", "karo", "the", "a", "on", "number", "someone", "help",
        "ambulance", "police", "everyone", "all", "it", "this", "group", "and", "doctor", "sos",
        "call", "phone", "message", "msg", "text", "sms", "whatsapp", "video", "voice", "bhejo", "open", "kholo",
        "send", "dial", "ring", "missed", "app", "chat",
    )

    /** "call Ravi" -> ("Ravi", "call"); "beta ko message bhejo" -> ("son", "message"). */
    fun contactUse(task: String): Pair<String, String>? {
        // Reading someone's messages is not contacting them.
        if (READ_ONLY.containsMatchIn(task)) return null
        val text = task.replace(Regex("""(?i)\b(and|aur|then|phir|&)\b"""), " ")
        for ((idx, re) in CONTACT_PATTERNS.withIndex()) for (m in re.findAll(text)) {
            val (verb, rawName) = when (idx) {
                0 -> m.groupValues[1] to m.groupValues[2]
                1 -> "message" to m.groupValues[1]
                else -> m.groupValues[2] to m.groupValues[1]
            }
            var name = rawName.trim().split(Regex("\\s+")).filter { it.lowercase() !in NOT_A_CONTACT && it.lowercase() !in STOP && it.lowercase() !in NAME_TAIL_STOP }
                .let { ws ->
                    // keep a second word only if it looks like a surname (capitalised in the original)
                    if (ws.size > 1 && !ws[1].first().isUpperCase()) ws.take(1) else ws.take(2)
                }.joinToString(" ")
            if (name.isBlank() || name.lowercase() in NOT_A_CONTACT || APPS.containsKey(name.lowercase())) continue
            relationOf(name)?.let { name = it }
            val kind = when (verb.lowercase()) {
                "call", "phone", "ring", "dial" -> "call"
                "whatsapp" -> "WhatsApp"
                else -> "message"
            }
            return name.replaceFirstChar { it.uppercase() } to kind
        }
        return null
    }

    // ── Relevance + rendering ───────────────────────────────────────────────

    private val CATEGORY_HINTS = mapOf(
        "health" to setOf("medicine", "doctor", "health", "diabetes", "blood_pressure", "hospital", "pain", "sick", "tabiyat", "bimar", "chemist", "pharmacy", "food", "eat", "khana", "sweet", "mithai"),
        "pref" to setOf("order", "buy", "cart", "grocery", "kirana", "blinkit", "zepto", "instamart", "bigbasket", "jiomart", "amazon", "swiggy", "zomato", "food", "khana"),
        "contact" to setOf("call", "message", "whatsapp", "video"),
        "person" to setOf("call", "message", "whatsapp", "family", "son", "daughter"),
    )

    /** Score how relevant [f] is to a request with tokens [q] (and its raw lowercase text). */
    fun score(f: MemoryFact, q: Set<String>, rawLower: String): Double {
        if (q.isEmpty()) return 0.0
        val ft = tokens(f.key.replace(':', ' ').replace('_', ' ') + " " + f.value + " " + f.detail)
        var s = (ft intersect q).size * 2.0
        // Exact person/app name mentioned in the request.
        if (f.value.length >= 3 && rawLower.contains(f.value.lowercase())) s += 3.0
        if (CATEGORY_HINTS[f.category]?.any { it in q } == true) s += 0.5
        if (s <= 0.0) return 0.0
        s += minOf(f.count, 10) * 0.1
        s += f.confidence
        if (f.pending) s -= 0.5
        return s
    }

    /** One prompt line for a fact. */
    fun render(f: MemoryFact): String {
        val label = f.name.replace('_', ' ')
        val text = when (f.category) {
            "profile" -> "${label.replaceFirstChar { it.uppercase() }}: ${f.value}"
            "person" -> "${label.replaceFirstChar { it.uppercase() }}: ${f.value}"
            "health" -> "Health: ${f.value}"
            "pref" -> if (label == "diet") "Diet: ${f.value}" else "Usual $label: ${f.value}" +
                (if (f.count > 1) " (ordered ${f.count} times)" else "")
            "contact" -> "Often contacts ${f.value}" + (if (f.count > 1) " (${f.count} times" + (if (f.detail.isNotBlank()) ", last by ${f.detail})" else ")") else "")
            "app" -> "Uses ${f.value}" + (if (f.count > 1) " (${f.count} times)" else "")
            "alias" -> "\"$label\" means ${f.value}"
            else -> f.value
        }
        return if (f.pending) "$text (not confirmed)" else text
    }

    /**
     * Pick facts for the prompt: [core] first (always), then the most relevant others for [query],
     * within [maxFacts] lines and [maxChars] characters. With a blank query, newest/most used first.
     */
    fun select(
        facts: List<MemoryFact>,
        query: String,
        maxFacts: Int = 12,
        maxChars: Int = 1200,
    ): List<MemoryFact> {
        val core = facts.filter { it.category == "profile" } +
            facts.filter { it.category == "person" && !it.pending }.sortedByDescending { it.updatedAt }.take(6)
        val rest = facts.filter { it !in core }
        val ranked = if (query.isBlank()) {
            rest.sortedWith(compareByDescending<MemoryFact> { !it.pending }.thenByDescending { it.count }.thenByDescending { it.updatedAt })
        } else {
            val q = tokens(query)
            val lower = query.lowercase()
            rest.map { it to score(it, q, lower) }.filter { it.second > 0.0 }
                .sortedWith(compareByDescending<Pair<MemoryFact, Double>> { it.second }.thenByDescending { it.first.updatedAt })
                .map { it.first }
        }
        val out = mutableListOf<MemoryFact>()
        var chars = 0
        for (f in core + ranked) {
            if (out.size >= maxFacts) break
            val len = render(f).length + 3
            if (chars + len > maxChars) continue
            out += f
            chars += len
        }
        return out
    }
}
