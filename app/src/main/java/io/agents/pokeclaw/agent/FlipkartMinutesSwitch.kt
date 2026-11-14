// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.graphics.Rect
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.utils.XLog

/**
 * Flipkart's top "Minutes" tab is drawn as an image (the festive "5 Minutes" logo) with no text or
 * description, so the model can't find it by text and ends up swiping. When a task asks for Flipkart
 * Minutes, Saathi switches to it in code right after Flipkart opens: by text if the tab has a label,
 * otherwise by its position (second tile of the top row, measured on the vivo I2221).
 */
object FlipkartMinutesSwitch {
    private const val TAG = "FlipkartMinutes"
    const val PKG = "com.flipkart.android"

    /** Centre of the Minutes tile as a fraction of the window: x 348..612 of 1260, y 126..282 of 2800. */
    const val X_FRAC = 0.381f
    const val Y_FRAC = 0.073f

    fun wanted(task: String): Boolean = ShoppingStoreRouter.modeFor(task) == ShoppingStoreRouter.Mode.MINUTES

    fun point(width: Int, height: Int): Pair<Int, Int> = Pair((width * X_FRAC).toInt(), (height * Y_FRAC).toInt())

    /** Returns a short note for the model, or null when nothing was done. */
    fun ensure(service: ClawAccessibilityService): String? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != PKG) return null
        val win = Rect().also { root.getBoundsInScreen(it) }
        if (win.width() <= 0 || win.height() <= 0) return null
        val topLimit = win.top + (win.height() * 0.15f).toInt()

        // 1) A labelled tab near the top, if this Flipkart version has one.
        val nodes = service.findNodesByText("Minutes")
        try {
            for (n in nodes) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val label = (n.text ?: n.contentDescription ?: "").toString().trim()
                if (r.centerY() > topLimit || !label.equals("Minutes", true) && !label.endsWith(" Minutes", true)) continue
                var target = n
                var depth = 0
                while (!target.isClickable && target.parent != null && depth < 4) { target = target.parent; depth++ }
                if (service.clickNode(target)) {
                    XLog.i(TAG, "Switched to Minutes by its label")
                    return "Saathi already switched Flipkart to Minutes. Go straight to the search bar."
                }
            }
        } finally {
            ClawAccessibilityService.recycleNodes(nodes)
        }

        // 2) Image-only tab: tap its position.
        val (x, y) = point(win.width(), win.height())
        val ok = service.performTap(win.left + x, win.top + y)
        XLog.i(TAG, "Minutes tab has no label; tapped position ($x,$y) ok=$ok")
        return if (ok) "Saathi already switched Flipkart to Minutes. Go straight to the search bar; do not look for the Minutes tab or swipe." else null
    }
}
