// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.utils.NodeFinder
import io.agents.pokeclaw.utils.XLog

/**
 * Ultra-fast search bar navigation service for WhatsApp, Blinkit, Amazon (IN/US), Flipkart, and generic apps.
 * Bypasses full accessibility tree parsing when searching for products/contacts with event-driven 30ms polling.
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
        "in.amazon.mShop.android.shopping:id/rs_search_src_text",
        "in.amazon.mShop.android.shopping:id/chrome_search_hint_view",
        "in.amazon.mShop.android.shopping:id/search_textbox",
        "in.amazon.mShop.android.shopping:id/iss_search_dropdown_item_text",
        "in.amazon.mShop.android.shopping:id/chrome_action_bar_search_type_search",
        "in.amazon.mShop.android.shopping:id/auto_complete_text_view",
        "com.amazon.mShop.android.shopping:id/rs_search_src_text",
        "com.amazon.mShop.android.shopping:id/chrome_search_hint_view",
        "com.amazon.mShop.android.shopping:id/search_textbox",
        "com.amazon.mShop.android.shopping:id/iss_search_dropdown_item_text",
        "com.amazon.mShop.android.shopping:id/chrome_action_bar_search_type_search",
        "com.amazon.mShop.android.shopping:id/auto_complete_text_view",
        "rs_search_src_text", "search_textbox", "chrome_search_hint_view",
        "auto_complete_text_view", "search_input", "chrome_search_entry",
        "nav_search_bar", "search_src_text"
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
            NodeFinder.ensureFlipkartMinutesMode(service)
            root = service.rootInActiveWindow ?: root
        }

        // 1. Package-specific ID lookup
        val targetIds = when {
            pkgName == "com.whatsapp" -> WHATSAPP_SEARCH_IDS
            pkgName == "com.grofers.customerapp" -> BLINKIT_SEARCH_IDS
            pkgName.contains("amazon") -> AMAZON_SEARCH_IDS
            pkgName == "com.flipkart.android" -> FLIPKART_SEARCH_IDS
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

        // 3. Fallback: if top bar was scrolled out of view, swipe down to restore
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        if (rootBounds.height() > 500) {
            val cx = rootBounds.centerX()
            val startY = rootBounds.top + (rootBounds.height() * 0.3f).toInt()
            val endY = rootBounds.top + (rootBounds.height() * 0.8f).toInt()
            XLog.i(TAG, "findSearchBar: search bar not visible, restoring top bar via scroll up")
            service.performSwipe(cx, startY, cx, endY, 200)

            val refreshedRoot = service.rootInActiveWindow ?: return null
            val restoredNode = findSearchNodeInTree(refreshedRoot)
            if (restoredNode != null) {
                return SearchBarResult(restoredNode, isNodeEditable(restoredNode), pkgName)
            }
        }

        return null
    }

    /**
     * Navigates directly to the search bar and inputs query without full tree re-parsing in < 80ms.
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

            // Event-driven 30ms poll for editable search field
            val deadline = System.currentTimeMillis() + 200L
            while (System.currentTimeMillis() < deadline) {
                result = findSearchBar(service)
                if (result != null && result.node != null && result.isEditable) {
                    searchNode = result.node
                    break
                }
                try { Thread.sleep(30L) } catch (_: InterruptedException) { break }
            }

            if (searchNode == null || !isNodeEditable(searchNode)) {
                val root = service.rootInActiveWindow
                searchNode = findFirstEditable(root)
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

            val root = service.rootInActiveWindow
            val pkg = root?.packageName?.toString().orEmpty().lowercase()

            if (pkg.contains("amazon")) {
                var suggestion: AccessibilityNodeInfo? = null
                val deadline = System.currentTimeMillis() + 180L
                while (System.currentTimeMillis() < deadline) {
                    val freshRoot = service.rootInActiveWindow
                    if (freshRoot != null) {
                        suggestion = NodeFinder.findNodeByTextContains(freshRoot, query.lowercase())
                        if (suggestion == null) {
                            suggestion = NodeFinder.findNodeByIdOrText(freshRoot,
                                "iss_search_dropdown_item_text", "sac-suggestion-row", "iss_suggestion_text"
                            )
                        }
                    }
                    if (suggestion != null) break
                    try { Thread.sleep(20L) } catch (_: Exception) { break }
                }

                if (suggestion != null) {
                    val clickable = NodeFinder.findClickableAncestor(suggestion) ?: suggestion
                    val clicked = service.clickNode(clickable)
                    XLog.i(TAG, "navigateAndType Amazon: clicked suggestion dropdown item, result=$clicked")
                    if (clicked) return true
                }

                val submitted = service.performImeAction(searchNode)
                XLog.i(TAG, "navigateAndType Amazon: performImeAction fallback result=$submitted")
                return true
            }

            try {
                service.sendKeyEvent(KeyEvent.KEYCODE_ENTER)
            } catch (_: Exception) {}
            if (!pkg.contains("amazon")) {
                service.dismissKeyboard()
            }
            return true
        }

        // Clipboard paste fallback if ACTION_SET_TEXT returned false
        XLog.w(TAG, "navigateAndType: ACTION_SET_TEXT returned false, falling back to clipboard paste")
        try {
            val ctx = ClawApplication.instance
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("search", query))
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
