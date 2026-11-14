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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val main = Handler(Looper.getMainLooper())
    @Volatile var isActive = false; private set
    private var queue: List<CareContact> = emptyList()
    private var index = 0
    private var offhookAt = 0L
    private var callback: Any? = null

    /** Starts the SOS chain. Returns a short sentence for the UI / agent. */
    fun trigger(context: Context, reason: String = "SOS"): String {
        val app = context.applicationContext
        val order = CareProfileStore.get().distressOrder()
        if (order.isEmpty()) {
            VoiceManager.speakNative("No emergency number is saved. Please add one in Care profile.", flush = true)
            return "No emergency contacts are saved yet. Add them in Settings → Care profile."
        }
        if (isActive) return "SOS is already calling ${order.getOrNull(index)?.label() ?: "your contacts"}."
        isActive = true
        queue = order
        index = 0
        val smsCount = sendAlertSms(app, order, reason)
        VoiceManager.speakNative("Calling ${order.first().label()} for help.", flush = true)
        listenForCallEnd(app)
        placeCall(app, order.first())
        main.postDelayed({ cancel(app, "timeout") }, GIVE_UP_AFTER_MS)
        val names = order.joinToString(" → ") { it.label() }
        return "SOS started. Calling in order: $names." + if (smsCount > 0) " Alert SMS sent to $smsCount." else ""
    }

    fun cancel(context: Context, why: String = "user") {
        if (!isActive) return
        XLog.i(TAG, "SOS chain stopped ($why)")
        isActive = false
        main.removeCallbacksAndMessages(null)
        unregister(context.applicationContext)
    }

    private fun placeCall(context: Context, c: CareContact) {
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${c.digits}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        XLog.w(TAG, "SOS call ${index + 1}/${queue.size}: ${c.role} (direct=$canCall)")
        try { context.startActivity(intent) } catch (e: Exception) { XLog.e(TAG, "SOS call failed", e) }
        if (!canCall) cancel(context, "no CALL_PHONE permission: opened dialer only")
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
        main.postDelayed({ if (isActive) placeCall(context, next) }, NEXT_CALL_DELAY_MS)
    }

    private fun onState(context: Context, state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> offhookAt = System.currentTimeMillis()
            TelephonyManager.CALL_STATE_IDLE -> if (offhookAt > 0) { onCallEnded(context); offhookAt = 0 }
        }
    }

    private fun listenForCallEnd(context: Context) {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            XLog.w(TAG, "READ_PHONE_STATE not granted: SOS will call only the first contact")
            return
        }
        offhookAt = 0
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(context, state)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(context, state)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                callback = l
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Could not watch call state: ${e.message}")
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
        XLog.w(TAG, "SOS SMS sent to $sent contacts (reason=$reason)")
        return sent
    }

    /** Words that start SOS straight from voice, skipping the normal confirm turn. */
    private val SOS_WORDS = Regex(
        """\b(sos|emergency|help me|bachao|bachaao|madad|madat|kapadandi|kaapaadu|kaapadu|ambulance|i fell|gir gayi|gir gaya)\b""",
        RegexOption.IGNORE_CASE
    )
    private val SETUP_WORDS = Regex("""\b(contact|number|save|add|set ?up|settings|profile)\b""", RegexOption.IGNORE_CASE)
    fun isDistressUtterance(text: String): Boolean =
        SOS_WORDS.containsMatchIn(text) && !SETUP_WORDS.containsMatchIn(text) // "add emergency contact" is not an SOS
}
