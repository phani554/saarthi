// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.a11y

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Safe recycling of accessibility nodes on API < 33 (minSdk 28). From API 33 `recycle()` is a
 * no-op, so this does nothing there.
 *
 * Rule for callers: recycle only a node that THIS code obtained (getChild / getParent /
 * window.root / findAccessibilityNodeInfos...) and that is not returned, cached, or still held by
 * anyone else. Never recycle a node passed in by a caller, the root a caller gave you, or a node put
 * into ClawAccessibilityService's node cache. Recycling a node that is still in use lets Android
 * hand the same object to another lookup, so a later click could land on a different element.
 */
object NodeRecycler {
    @JvmStatic
    val needsRecycle: Boolean = Build.VERSION.SDK_INT < 33

    @JvmStatic
    @Suppress("DEPRECATION")
    fun recycle(node: AccessibilityNodeInfo?) {
        if (node == null || !needsRecycle) return
        try { node.recycle() } catch (_: Throwable) { /* already recycled */ }
    }

    /** Recycles [node] unless it is the same instance as one of [keep]. */
    @JvmStatic
    fun recycleUnless(node: AccessibilityNodeInfo?, vararg keep: AccessibilityNodeInfo?) {
        if (node == null || !needsRecycle) return
        for (k in keep) if (k === node) return
        recycle(node)
    }

    @JvmStatic
    fun recycleAll(nodes: Iterable<AccessibilityNodeInfo?>?) {
        if (nodes == null || !needsRecycle) return
        for (n in nodes) recycle(n)
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun recycleWindow(window: AccessibilityWindowInfo?) {
        if (window == null || !needsRecycle) return
        try { window.recycle() } catch (_: Throwable) {}
    }
}
