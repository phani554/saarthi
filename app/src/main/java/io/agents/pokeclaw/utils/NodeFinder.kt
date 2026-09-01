// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.service.ClawAccessibilityService

/**
 * Robust semantic and structural node finder.
 * Eliminates hardcoded coordinates and provides resilient UI tree processing.
 */
object NodeFinder {

    private const val TAG = "NodeFinder"

    fun findNodeByKeywords(root: AccessibilityNodeInfo?, vararg keywords: String): AccessibilityNodeInfo? {
        if (nodeInvalid(root)) return null

        val text = root?.text?.toString().orEmpty().lowercase()
        val desc = root?.contentDescription?.toString().orEmpty().lowercase()
        val hint = root?.hintText?.toString().orEmpty().lowercase()

        for (kw in keywords) {
            val lowerKw = kw.lowercase()
            if (text.contains(lowerKw) || desc.contains(lowerKw) || hint.contains(lowerKw)) {
                return root
            }
        }

        for (i in 0 until (root?.childCount ?: 0)) {
            val childMatch = findNodeByKeywords(root?.getChild(i), *keywords)
            if (childMatch != null) return childMatch
        }
        return null
    }

    fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return node
    }

    fun findNodeByIdOrText(root: AccessibilityNodeInfo?, vararg identifiers: String): AccessibilityNodeInfo? {
        if (nodeInvalid(root)) return null

        val resId = root?.viewIdResourceName?.lowercase().orEmpty()
        val text = root?.text?.toString().orEmpty().lowercase()
        val desc = root?.contentDescription?.toString().orEmpty().lowercase()

        for (id in identifiers) {
            val lowerId = id.lowercase()
            if (resId.contains(lowerId) || text == lowerId || desc == lowerId) {
                return root
            }
        }

        for (i in 0 until (root?.childCount ?: 0)) {
            val child = findNodeByIdOrText(root?.getChild(i), *identifiers)
            if (child != null) return child
        }
        return null
    }

    /**
     * Ensures Flipkart is switched into Minutes mode using top banner inspection.
     * Top banner tab bounds: [348,126][612,282].
     */
    fun ensureFlipkartMinutesMode(service: ClawAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "com.flipkart.android") return false

        // 1. Check if already on Minutes screen (top search bar says "Search in minutes")
        val currentSearchHint = findNodeByKeywords(root, "Search in minutes")
        if (currentSearchHint != null) {
            XLog.i(TAG, "ensureFlipkartMinutesMode: already in Flipkart Minutes mode")
            return true
        }

        // 2. Inspect Top Banner (Y <= 320px) for Minutes tab
        val topNodes = mutableListOf<AccessibilityNodeInfo>()
        collectTopNodes(root, topNodes, 320)
        for (node in topNodes) {
            val desc = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val isMinutesTarget = desc.equals("Minutes", ignoreCase = true) ||
                    text.equals("Minutes", ignoreCase = true) ||
                    (bounds.left in 300..400 && bounds.top in 100..300)

            if (isMinutesTarget && bounds.height() > 30) {
                val clickable = findClickableAncestor(node) ?: node
                XLog.i(TAG, "ensureFlipkartMinutesMode: found top banner Minutes tab at $bounds, tapping to switch mode")
                service.clickNode(clickable)
                try { Thread.sleep(800) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                return true
            }
        }

        // 3. Fallback: check bottom tab bar for Minutes tab
        val minutesTab = findNodeByIdOrText(root, "Minutes", "content-desc=Minutes")
            ?: findNodeByKeywords(root, "Minutes")

        if (minutesTab != null) {
            val target = findClickableAncestor(minutesTab) ?: minutesTab
            XLog.i(TAG, "ensureFlipkartMinutesMode: tapping Minutes tab to switch mode")
            service.clickNode(target)
            try { Thread.sleep(800) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            return true
        }
        return false
    }

    private fun collectTopNodes(node: AccessibilityNodeInfo?, results: MutableList<AccessibilityNodeInfo>, maxY: Int) {
        if (node == null || !node.isVisibleToUser) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.top <= maxY) {
            results.add(node)
            for (i in 0 until node.childCount) {
                collectTopNodes(node.getChild(i), results, maxY)
            }
        }
    }

    private fun nodeInvalid(node: AccessibilityNodeInfo?): Boolean {
        return node == null || !node.isVisibleToUser
    }
}
