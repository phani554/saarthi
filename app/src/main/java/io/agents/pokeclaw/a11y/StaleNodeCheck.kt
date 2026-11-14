// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.a11y

/**
 * Decides whether a node cached by get_screen_info still is the element the model saw.
 * Pure Kotlin (JVM-testable); ClawAccessibilityService feeds it the cached snapshot and the values
 * read after `AccessibilityNodeInfo.refresh()`.
 */
object StaleNodeCheck {
    /** How far (px) the centre may move before the node counts as moved (small relayout jitter). */
    const val MAX_CENTER_SHIFT_PX = 24

    enum class Verdict { FRESH, GONE, MOVED, TEXT_CHANGED }

    /**
     * @param refreshed result of refresh(); false = the view is gone.
     * @param visible isVisibleToUser() after refresh.
     */
    @JvmStatic
    fun judge(
        refreshed: Boolean,
        visible: Boolean,
        cachedText: String?, cachedDesc: String?, cachedCx: Int, cachedCy: Int,
        nowText: CharSequence?, nowDesc: CharSequence?, nowCx: Int, nowCy: Int,
    ): Verdict {
        if (!refreshed || !visible) return Verdict.GONE
        if (Math.abs(nowCx - cachedCx) > MAX_CENTER_SHIFT_PX || Math.abs(nowCy - cachedCy) > MAX_CENTER_SHIFT_PX) {
            return Verdict.MOVED
        }
        if (norm(cachedText) != norm(nowText) || norm(cachedDesc) != norm(nowDesc)) return Verdict.TEXT_CHANGED
        return Verdict.FRESH
    }

    private fun norm(s: CharSequence?): String = s?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    @JvmStatic
    fun describe(v: Verdict): String = when (v) {
        Verdict.FRESH -> "fresh"
        Verdict.GONE -> "no longer on screen"
        Verdict.MOVED -> "moved since the last screen read"
        Verdict.TEXT_CHANGED -> "its text changed since the last screen read"
    }
}
