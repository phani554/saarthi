// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.utils.XLog

/**
 * Fast search bar navigation service for WhatsApp, Blinkit, Amazon, Flipkart, and generic apps.
 * Bypasses full accessibility tree parsing when searching for products/contacts.
 */
object SearchBarService {

    private const val TAG = "SearchBarService"

    private val WHATSAPP_SEARCH_IDS = arrayOf(
        "com.whatsapp:id/search_src_text",
        "com.whatsapp:id/menuitem_search",
        "com.whatsapp:id/search_holder",
        "com.whatsapp:id/search_icon"
    )

    private val BLINKIT_SEARCH_IDS = arrayOf(
        "com.grofers.customerapp:id/search_text",
        "com.grofers.customerapp:id/search_bar",
        "com.grofers.customerapp:id/fyt_search_bar",
        "com.grofers.customerapp:id/et_search",
        "com.grofers.customerapp:id/search_text_view",
        "com.grofers.customerapp:id/search_container"
    )

    private val AMAZON_SEARCH_IDS = arrayOf(
        "com.amazon.mShop.android.shopping:id/rs_search_src_text",
        "com.amazon.mShop.android.shopping:id/chrome_search_hint_view",
        "com.amazon.mShop.android.shopping:id/search_textbox",
        "com.amazon.mShop.android.shopping:id/iss_search_dropdown_item_text"
    )

    private val FLIPKART_SEARCH_IDS = arrayOf(
        "com.flipkart.android:id/search_auto_complete",
        "com.flipkart.android:id/search_widget",
        "com.flipkart.android:id/et_search_view",
        "com.flipkart.android:id/root_search_container",
        "com.flipkart.android:id/search_icon",
        "com.flipkart.android:id/search_text"
    )

    data class SearchBarResult(
        val node: AccessibilityNodeInfo?,
        val isEditable: Boolean,
        val appPackage: String
    )

    /**
     * Fast search bar lookup for current active window.
     */
    fun findSearchBar(service: ClawAccessibilityService): SearchBarResult? {
        var root = service.rootInActiveWindow ?: return null
        val pkgName = root.packageName?.toString().orEmpty()

        if (pkgName == "com.flipkart.android") {
            io.agents.pokeclaw.utils.NodeFinder.ensureFlipkartMinutesMode(service)
            root = service.rootInActiveWindow ?: root
        }

        // 1. Package-specific ID lookup
        val targetIds = when (pkgName) {
            "com.whatsapp" -> WHATSAPP_SEARCH_IDS
            "com.grofers.customerapp" -> BLINKIT_SEARCH_IDS
            "com.amazon.mShop.android.shopping" -> AMAZON_SEARCH_IDS
            "com.flipkart.android" -> FLIPKART_SEARCH_IDS
            else -> emptyArray()
        }

        for (viewId in targetIds) {
            val matches = service.findNodesById(viewId)
            for (node in matches) {
                if (node.isVisibleToUser) {
                    XLog.i(TAG, "findSearchBar: found target ID $viewId in $pkgName")
                    return SearchBarResult(node, isNodeEditable(node), pkgName)
                }
            }
        }

        // 2. Fast structural search field scan
        val searchNode = findSearchNodeInTree(root)
        if (searchNode != null) {
            XLog.i(TAG, "findSearchBar: found structural search node in $pkgName")
            return SearchBarResult(searchNode, isNodeEditable(searchNode), pkgName)
        }

        // 3. Fallback: if top bar was scrolled out of view, swipe down (scroll up) to restore top search bar
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        if (rootBounds.height() > 500) {
            val cx = rootBounds.centerX()
            val startY = rootBounds.top + (rootBounds.height() * 0.3f).toInt()
            val endY = rootBounds.top + (rootBounds.height() * 0.8f).toInt()
            XLog.i(TAG, "findSearchBar: search bar not visible, restoring top bar via scroll up")
            service.performSwipe(cx, startY, cx, endY, 300)
            try { Thread.sleep(400) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

            val refreshedRoot = service.rootInActiveWindow ?: return null
            val restoredNode = findSearchNodeInTree(refreshedRoot)
            if (restoredNode != null) {
                return SearchBarResult(restoredNode, isNodeEditable(restoredNode), pkgName)
            }
        }

        return null
    }

    /**
     * Navigates directly to the search bar and inputs query without full tree re-parsing.
     */
    fun navigateAndType(
        service: ClawAccessibilityService,
        query: String
    ): Boolean {
        var result = findSearchBar(service)
        if (result == null || result.node == null) {
            XLog.w(TAG, "navigateAndType: search bar not found")
            return false
        }

        var searchNode = result.node

        // If search bar is a placeholder trigger (non-editable), tap it first
        if (!result.isEditable) {
            XLog.i(TAG, "navigateAndType: tapping search trigger button")
            service.clickNode(searchNode)
            try { Thread.sleep(600) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

            // Re-find search field on the newly opened search screen
            result = findSearchBar(service)
            if (result == null || result.node == null) {
                // Fallback: get focused editable
                val root = service.rootInActiveWindow
                searchNode = findFirstEditable(root)
            } else {
                searchNode = result.node
            }
        }

        if (searchNode == null) {
            XLog.w(TAG, "navigateAndType: failed to locate editable search field")
            return false
        }

        // Focus & set search query directly in a single atomic step
        searchNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val textArgs = Bundle()
        textArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        val success = searchNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)

        if (success) {
            XLog.i(TAG, "navigateAndType: query '$query' set cleanly via ACTION_SET_TEXT")
            try {
                service.sendKeyEvent(android.view.KeyEvent.KEYCODE_ENTER)
            } catch (_: Exception) {}
            service.dismissKeyboard()
            return true
        }

        // Only fall back to clipboard paste if ACTION_SET_TEXT returned false
        XLog.w(TAG, "navigateAndType: ACTION_SET_TEXT returned false, falling back to clipboard paste")
        try {
            val ctx = io.agents.pokeclaw.ClawApplication.instance
            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("search", query))
                return searchNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "navigateAndType: paste fallback failed", e)
        }

        return false
    }

    private fun isNodeEditable(node: AccessibilityNodeInfo): Boolean {
        val editable = node.isEditable
        val cn = node.className?.toString().orEmpty()
        return editable || cn.contains("EditText") || cn.contains("AutoCompleteTextView")
    }

    private fun findSearchNodeInTree(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null

        val text = node.text?.toString().orEmpty().lowercase()
        val desc = node.contentDescription?.toString().orEmpty().lowercase()
        val hint = node.hintText?.toString().orEmpty().lowercase()
        val idName = node.viewIdResourceName?.lowercase().orEmpty()

        val isSearchHint = text.contains("search") || desc.contains("search") || hint.contains("search") || idName.contains("search")

        if (isSearchHint && (isNodeEditable(node) || node.isClickable)) {
            return node
        }

        // Check if node is top EditText field on search screen
        if (isNodeEditable(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.top < 400 && bounds.height() > 50) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val childMatch = findSearchNodeInTree(node.getChild(i))
            if (childMatch != null) return childMatch
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (isNodeEditable(node)) return node
        for (i in 0 until node.childCount) {
            val child = findFirstEditable(node.getChild(i))
            if (child != null) return child
        }
        return null
    }
}
