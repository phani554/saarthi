// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.care

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.service.VoiceManager
import io.agents.pokeclaw.utils.XLog
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SOS: texts everyone on the distress list, then phones them one by one in the user's priority
 * order (e.g. loved one -> building watchman -> family doctor) until someone stays on the line.
 *
 * Android does not tell a normal app whether an outgoing call was answered. Rule used here:
 * a call that ends within [LIKELY_UNANSWERED_MS] (ringing + a very short pickup) moves on to the
 * next person. Calling one person too many is safer than stopping early.
 */
object DistressEscalator {
    private const val TAG = "DistressEscalator"
    private const val LIKELY_UNANSWERED_MS = 60_000L
    private const val NEXT_CALL_DELAY_MS = 2_000L
    private const val GIVE_UP_AFTER_MS = 15 * 60_000L
    /** Without call-state access, a repeat SOS within this window calls the next person. */
    private const val REPEAT_WINDOW_MS = 5 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    // All state below is touched only on the main thread (trigger/cancel hop to main).
    @Volatile
    var isActive = false
        private set
    /** True when the last trigger() actually placed a direct call (not just opened the dialer). */
    @Volatile
    var lastTriggerPlacedCall = false
        private set
    private var queue: List<CareContact> = emptyList()
    private var index = 0
    private var offhookAt = 0L
    private var incomingCall = false
    private var callback: Any? = null
    // Fallback when the call state cannot be watched: where a repeat press continues.
    private var unwatchedNextIndex = 0
    private var unwatchedAt = 0L

    /** Starts the SOS chain. Returns a short, honest sentence for the UI / agent. Safe from any thread. */
    fun trigger(context: Context, reason: String = "SOS"): String {
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) return triggerOnMain(app, reason)
        // Telephony listeners need a Looper thread (API < 31), and the state must not race: run on main.
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                result.set(triggerOnMain(app, reason))
            } catch (e: Exception) {
                XLog.e(TAG, "SOS trigger failed", e)
                isActive = false
                result.set("SOS could not start: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        return try {
            if (latch.await(5, TimeUnit.SECONDS)) result.get() ?: "SOS started." else "SOS is starting."
        } catch (_: InterruptedException) {
            "SOS is starting."
        }
    }

    private fun triggerOnMain(app: Context, reason: String): String {
        lastTriggerPlacedCall = false
        val order = CareProfileStore.get().distressOrder()
        if (order.isEmpty()) {
            speak(io.agents.pokeclaw.agent.SpokenStrings.sosNoNumber(), flush = true)
            return "No emergency contacts are saved yet. Add them in Settings → Care profile."
        }
        if (isActive) {
            lastTriggerPlacedCall = true
            return "SOS is already calling ${queue.getOrNull(index)?.label() ?: "your contacts"}."
        }
        val canCall = ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val repeat = unwatchedAt > 0 && System.currentTimeMillis() - unwatchedAt < REPEAT_WINDOW_MS
        val startIndex = if (repeat) unwatchedNextIndex % order.size else 0
        unwatchedAt = 0
        queue = order
        index = startIndex
        val first = order[startIndex]
        // Texts go out once per emergency, not again on a repeat press.
        val smsCount = if (repeat) 0 else sendAlertSms(app, order, reason)
        val smsNote = if (smsCount > 0) " Alert SMS queued to $smsCount." else ""

        if (!canCall) {
            // Only the dialer can be opened: the user must press call. Nothing stays "active".
            speak(io.agents.pokeclaw.agent.SpokenStrings.sosPressGreen(first.label()), flush = true)
            openCall(app, first, direct = false)
            unwatchedAt = System.currentTimeMillis()
            unwatchedNextIndex = startIndex + 1
            return "I could not place the call myself (phone-call permission is off). " +
                "Opened the dialer for ${first.label()}: please press call.$smsNote"
        }

        isActive = true
        main.postDelayed({ cancel(app, "timeout") }, GIVE_UP_AFTER_MS)
        val watching = listenForCallEnd(app)
        speak(io.agents.pokeclaw.agent.SpokenStrings.sosCallingForHelp(first.label()), flush = true)
        val placed = openCall(app, first, direct = true)
        lastTriggerPlacedCall = placed
        if (!placed) {
            cancel(app, "call could not be started")
            return "SOS could not start the call to ${first.label()}.$smsNote"
        }
        if (!watching) {
            // Cannot tell when the call ends, so no automatic next call. Reset so a repeat press works
            // and continues with the next person.
            cancel(app, "no call-state access")
            unwatchedAt = System.currentTimeMillis()
            unwatchedNextIndex = startIndex + 1
            val next = order.getOrNull(startIndex + 1)
            return "Calling ${first.label()} only. I can't see when the call ends, so I will not call the next person automatically." +
                (if (next != null) " Press SOS again to call ${next.label()}." else "") + smsNote
        }
        val names = order.drop(startIndex).joinToString(" → ") { it.label() }
        return "SOS started. Calling in order until someone answers: $names.$smsNote"
    }

    /** speakNative can wait on VoiceManager's speak lock, so never call it on the main thread. */
    private fun speak(text: String, flush: Boolean) {
        Thread({
            try { VoiceManager.speakNative(text, flush = flush) } catch (e: Exception) { XLog.w(TAG, "SOS speech failed: ${e.message}") }
        }, "sos-speak").start()
    }

    fun cancel(context: Context, why: String = "user") {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel(app, why) }
            return
        }
        if (!isActive) return
        XLog.i(TAG, "SOS chain stopped ($why)")
        isActive = false
        main.removeCallbacksAndMessages(null)
        unregister(app)
    }

    /** Returns true when the call/dial activity was started. */
    private fun openCall(context: Context, c: CareContact, direct: Boolean): Boolean {
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${c.digits}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        XLog.w(TAG, "SOS call ${index + 1}/${queue.size}: ${c.role} (direct=$direct)")
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            XLog.e(TAG, "SOS call failed", e)
            false
        }
    }

    private fun onCallEnded(context: Context) {
        if (!isActive) return
        val lasted = System.currentTimeMillis() - offhookAt
        if (lasted >= LIKELY_UNANSWERED_MS) {
            cancel(context, "call lasted ${lasted / 1000}s, assuming answered")
            return
        }
        index++
        val next = queue.getOrNull(index)
        if (next == null) {
            cancel(context, "end of list")
            return
        }
        main.postDelayed({
            if (isActive && !openCall(context, next, direct = true)) cancel(context, "call could not be started")
        }, NEXT_CALL_DELAY_MS)
    }

    private fun onState(context: Context, state: Int) {
        when (state) {
            // Ringing = an incoming call (e.g. a contact calling back); its end is not our call's end.
            TelephonyManager.CALL_STATE_RINGING -> incomingCall = true
            TelephonyManager.CALL_STATE_OFFHOOK -> if (!incomingCall) offhookAt = System.currentTimeMillis()
            TelephonyManager.CALL_STATE_IDLE -> {
                if (incomingCall) {
                    incomingCall = false
                    offhookAt = 0
                } else if (offhookAt > 0) {
                    onCallEnded(context)
                    offhookAt = 0
                }
            }
        }
    }

    /** Must run on the main thread. Returns true when call-end events will arrive. */
    private fun listenForCallEnd(context: Context): Boolean {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
        offhookAt = 0
        incomingCall = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: call-state callbacks require READ_PHONE_STATE.
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    XLog.w(TAG, "READ_PHONE_STATE not granted: cannot watch call end")
                    return false
                }
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(context, state)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
                callback = cb
            } else {
                // API < 31: LISTEN_CALL_STATE needs no permission; the listener binds to this (main) Looper.
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(context, state)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                callback = l
            }
            return true
        } catch (e: Exception) {
            XLog.w(TAG, "Could not watch call state: ${e.message}")
            return false
        }
    }
    private fun unregister(context: Context) {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val cb = callback ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cb is TelephonyCallback) {
                tm.unregisterTelephonyCallback(cb)
            } else if (cb is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {}
        callback = null
    }

    private fun sendAlertSms(context: Context, order: List<CareContact>, reason: String): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return 0
        val time = SimpleDateFormat("dd MMM, h:mm a", Locale.ENGLISH).format(Date())
        val text = "SOS from Saathi ($time): I need help. Please call me or come now."
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        var sent = 0
        order.filter { it.role != CareRoles.FAMILY_DOCTOR }.forEach { c ->
            try { sms.sendTextMessage(c.digits, null, text, null, null); sent++ } catch (e: Exception) {
                XLog.w(TAG, "SOS SMS failed for ${c.role}: ${e.message}")
            }
        }
        // Counted when handed to the SIM, not when delivered (no sentIntent tracking).
        XLog.w(TAG, "SOS SMS queued to $sent contacts (reason=$reason)")
        return sent
    }

    // ---------- Voice phrases ----------

    private val TOKEN_SPLIT = Regex("""[^\p{L}\p{M}\p{Nd}']+""")

    private fun tokens(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .split(TOKEN_SPLIT).filter { it.isNotBlank() }

    private fun containsPhrase(padded: String, phrases: Collection<String>): Boolean =
        phrases.any { padded.contains(" $it ") }

    /** Unmistakable cries for help: start SOS even inside a longer sentence. */
    private val STRONG_PHRASES = listOf(
        "bachao", "bachaao", "bachaoo", "bachalo", "बचाओ", "बचा लो", "बचालो",
        "help help", "madad madad", "मदद मदद",
        "కాపాడండి", "కాపాడు", "కాపాడండీ", "kapadandi", "kaapaadandi", "kaapaadu", "kaapadu",
        // Marathi
        "वाचवा", "vachva", "vaachva",
    )

    /** Distress words that count only when they are (nearly) the whole utterance. */
    private val SHORT_PHRASES = listOf(
        "sos", "help", "help me", "please help", "madad", "madat", "मदद", "emergency", "इमरजेंसी",
        "ambulance", "एम्बुलेंस", "एंबुलेंस", "అంబులెన్స్", "i fell", "i have fallen", "i fell down",
        "gir gayi", "gir gaya", "gir gai", "गिर गई", "गिर गयी", "गिर गया", "sahayam", "sahaayam", "సహాయం",
        "padipoyanu", "పడిపోయాను",
        // Marathi
        "मदत", "padle", "padlo", "पडले", "पडलो",
    )

    /** Words allowed around a short distress word ("please help me", "jaldi madad karo"). */
    private val FILLER = setOf(
        "please", "plz", "pls", "me", "i", "need", "someone", "somebody", "now", "quick", "fast", "come",
        "mujhe", "koi", "karo", "kijiye", "chahiye", "jaldi", "ji", "arre", "are", "hai", "ho", "main", "mai",
        "मुझे", "कोई", "करो", "कीजिए", "करिए", "चाहिए", "जल्दी", "जी", "अरे", "है", "मैं",
        "nenu", "నేను", "naaku", "నాకు", "kavali", "కావాలి", "ra", "andi",
        "mala", "मला", "kara", "करा", "mi", "मी", "lavkar", "लवकर", "pahije", "पाहिजे",
    )

    /** "No emergency", "I'm fine", "stop": never an SOS, and cancels a running SOS. */
    private val CANCEL_PHRASES = listOf(
        "stop", "cancel", "no emergency", "not an emergency", "not emergency", "false alarm",
        "i am fine", "i'm fine", "i m fine", "im fine", "i am ok", "i'm ok", "i am okay", "i'm okay", "no help",
        "theek hoon", "theek hu", "thik hu", "thik hoon", "theek hai", "band karo", "ruko", "mat karo",
        "ठीक हूँ", "ठीक हूं", "ठीक है", "बंद करो", "रुको", "मत करो",
        "vaddu", "వద్దు", "aapu", "ఆపు", "ఆపండి", "బాగున్నాను", "baagunnanu",
        "ठीक आहे", "बरी आहे", "बरा आहे", "थांबा", "नको", "theek aahe", "bari aahe", "bara aahe", "thamba", "nako",
    )

    /** Negations and setup talk ("add emergency contact", "emergency number kya hai"). */
    private val EXCLUDE_TOKENS = setOf(
        "no", "not", "don't", "dont", "nahi", "nahin", "nai", "नहीं", "नही",
        "contact", "contacts", "number", "numbers", "save", "add", "setup", "settings", "profile",
        "what", "which", "kya", "kaun", "kaunsa", "क्या", "नंबर", "कॉन्टैक्ट", "नम्बर", "నంబర్", "ఏమిటి",
    )

    /**
     * True only for distress cries: "bachao"/"बचाओ"/"కాపాడండి"/"help help" anywhere, or a short utterance
     * (≤ 5 words) made only of a distress word plus filler ("help me", "मदद करो", "jaldi madad karo").
     * "Help me message my son", "WhatsApp mein madad karo", "emergency number" do not match.
     */
    fun isDistressUtterance(text: String): Boolean {
        val toks = tokens(text)
        if (toks.isEmpty()) return false
        val padded = " " + toks.joinToString(" ") + " "
        if (containsPhrase(padded, CANCEL_PHRASES)) return false
        if (containsPhrase(padded, STRONG_PHRASES)) return true
        if (toks.any { it in EXCLUDE_TOKENS } || padded.contains(" set up ")) return false
        if (toks.size > 5) return false
        var rest = padded
        var found = false
        // Longest phrases first so "help me" is removed before "help".
        for (p in SHORT_PHRASES.sortedByDescending { it.length }) {
            while (rest.contains(" $p ")) {
                rest = rest.replaceFirst(" $p ", " ")
                found = true
            }
        }
        if (!found) return false
        return rest.trim().split(' ').filter { it.isNotBlank() }.all { it in FILLER }
    }

    /** True when the user asks to stop / says they are fine ("no emergency", "theek hoon", "stop"). */
    fun isCancelUtterance(text: String): Boolean {
        val toks = tokens(text)
        if (toks.isEmpty()) return false
        return containsPhrase(" " + toks.joinToString(" ") + " ", CANCEL_PHRASES)
    }
}
