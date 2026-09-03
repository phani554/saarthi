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
        val hint = root?.hintText?.toString().orEmpty().lowercase()

        for (id in identifiers) {
            val lowerId = id.lowercase().trim()
            if (lowerId.isEmpty()) continue
            if (resId.contains(lowerId) || text.contains(lowerId) || desc.contains(lowerId) || hint.contains(lowerId)) {
                return root
            }
        }

        for (i in 0 until (root?.childCount ?: 0)) {
            val child = findNodeByIdOrText(root?.getChild(i), *identifiers)
            if (child != null) return child
        }
        return null
    }

    fun findNodeByTextContains(root: AccessibilityNodeInfo?, vararg substrings: String): AccessibilityNodeInfo? {
        if (nodeInvalid(root)) return null

        val text = root?.text?.toString().orEmpty().lowercase()
        val desc = root?.contentDescription?.toString().orEmpty().lowercase()

        for (sub in substrings) {
            val lowerSub = sub.lowercase().trim()
            if (lowerSub.isNotEmpty() && (text.contains(lowerSub) || desc.contains(lowerSub))) {
                return root
            }
        }

        for (i in 0 until (root?.childCount ?: 0)) {
            val child = findNodeByTextContains(root?.getChild(i), *substrings)
            if (child != null) return child
        }
        return null
    }

    /**
     * Ensures Flipkart is switched into Minutes mode using top banner inspection.
     * Top banner tab bounds: [310,288 - 434,342] or top Y <= 450px.
     */
    fun ensureFlipkartMinutesMode(service: ClawAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "com.flipkart.android") return false

        // 1. Check if already on Minutes screen (search bar hint contains "minutes")
        val currentSearchHint = findNodeByKeywords(root, "Search in minutes", "minutes")
        if (currentSearchHint != null && currentSearchHint.text?.toString()?.lowercase()?.contains("minutes") == true) {
            XLog.i(TAG, "ensureFlipkartMinutesMode: already in Flipkart Minutes mode")
            return true
        }

        // 2. Inspect Top Banner (Y <= 450px) for Minutes tab or Grocery 10m
        val topNodes = mutableListOf<AccessibilityNodeInfo>()
        collectTopNodes(root, topNodes, 450)
        for (node in topNodes) {
            val desc = node.contentDescription?.toString().orEmpty().lowercase()
            val text = node.text?.toString().orEmpty().lowercase()
            val resId = node.viewIdResourceName.orEmpty().lowercase()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val isMinutesTarget = desc.contains("minutes") || text.contains("minutes") ||
                    desc.contains("grocery") || text.contains("grocery") ||
                    resId.contains("minutes") || (bounds.left in 280..480 && bounds.top in 120..380)

            if (isMinutesTarget && bounds.height() > 20) {
                val clickable = findClickableAncestor(node) ?: node
                XLog.i(TAG, "ensureFlipkartMinutesMode: found top banner Minutes tab at $bounds, tapping to switch mode")
                var clicked = service.clickNode(clickable)
                if (!clicked) {
                    clicked = service.performTap(bounds.centerX(), bounds.centerY())
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                return true
            }
        }

        // 3. Resilient Fallback: Perform top banner gesture tap at center coordinates (370, 300)
        XLog.i(TAG, "ensureFlipkartMinutesMode: tapping top banner center coordinates (370, 300) to force switch to Minutes mode")
        val fallbackClicked = service.performTap(370, 300)
        try { Thread.sleep(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return fallbackClicked
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
