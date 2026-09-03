// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.agent.knowledge.ContactAliasResolver;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.utils.ContactListUiUtils;
import io.agents.pokeclaw.utils.ContactMatchUtils;
import io.agents.pokeclaw.utils.XLog;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Generic high-level tool: sends a message to a contact in ANY messaging app.
 * Optimized with fast event-driven polling and zero artificial sleep delays (< 400ms total).
 */
public class SendMessageTool extends BaseTool {

    private static final String TAG = "SendMessageTool";

    @Override
    @NotNull
    public String getName() { return "send_message"; }

    @Override
    @NotNull
    public String getDisplayName() { return "Send Message"; }

    @Override
    @NotNull
    public String getDescriptionEN() {
        return "Send a text message to a contact via any messaging app (WhatsApp, Telegram, Messages, etc).";
    }

    @Override
    @NotNull
    public String getDescriptionCN() {
        return "Send a text message to a contact via any messaging app (WhatsApp, Telegram, Messages, etc).";
    }

    @Override
    @NotNull
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("contact", "string", "Contact name or phone number to message (e.g. 'Mom', '+1 604 555 1234')", true),
                new ToolParameter("message", "string", "The message text to send", true),
                new ToolParameter("app", "string", "Messaging app name (default: WhatsApp)", false)
        );
    }

    @Override
    public ToolResult execute(@NotNull Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String rawContact = requireString(params, "contact");
        String contact = ContactAliasResolver.INSTANCE.resolve(rawContact);
        String message = requireString(params, "message");
        Object appParam = params.get("app");
        String app = appParam != null ? appParam.toString() : "WhatsApp";

        XLog.i(TAG, "Sending '" + message + "' to " + contact + " (raw='" + rawContact + "') via " + app);

        try {
            // Step 1: Resolve and open the messaging app
            String packageName = OpenAppTool.resolveAppNameStatic(app);
            if (packageName == null) packageName = app;
            boolean opened = OpenAppTool.openAppWithInterceptHandling(service, packageName);
            if (!opened) {
                return ToolResult.error("Failed to open " + app + ". Is it installed?");
            }

            // Step 2: Event-driven wait for messaging app window
            if (!waitForActiveWindow(service, packageName, 1500)) {
                return ToolResult.error(app + " did not become active.");
            }

            // Step 3: Check if already in chatroom or search contact
            if (isAlreadyInChatWith(service, contact)) {
                XLog.i(TAG, "Step 3: Already in " + contact + "'s chatroom");
            } else {
                if (!ContactListUiUtils.prepareForContactLookup(service, packageName, 3, 300)) {
                    return ToolResult.error("Could not reach searchable " + app + " chat list.");
                }

                if (!findAndTapContact(service, contact)) {
                    return ToolResult.error("Could not find '" + contact + "' in " + app + " chat list.");
                }
            }

            // Step 4: Event-driven typing into bottom input field
            boolean typed = false;
            for (int poll = 0; poll < 10; poll++) {
                if (typeInBottomEditText(service, message)) {
                    typed = true;
                    break;
                }
                Thread.sleep(80);
            }
            if (!typed) {
                return ToolResult.error("Could not find message input field.");
            }

            // Step 4.5: Verify chat header matches target recipient before sending
            boolean headerVerified = verifyChatHeader(service, contact);
            if (!headerVerified) {
                XLog.w(TAG, "Header verification mismatch for '" + contact + "'. Re-navigating.");
                if (ContactListUiUtils.prepareForContactLookup(service, packageName, 3, 300) && findAndTapContact(service, contact)) {
                    typeInBottomEditText(service, message);
                    headerVerified = verifyChatHeader(service, contact);
                }
            }

            if (!headerVerified) {
                return ToolResult.error("Chat header verification failed for '" + contact + "'. Message blocked.");
            }

            // Step 5: Tap send button or press enter
            if (!tapSendOrEnter(service)) {
                return ToolResult.error("Could not find send button.");
            }
            XLog.i(TAG, "Step 5: Sent successfully!");
            return ToolResult.success("Sent '" + message + "' to " + contact + " via " + app);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted");
        } catch (Exception e) {
            XLog.e(TAG, "Failed", e);
            return ToolResult.error("Failed: " + e.getMessage());
        }
    }

    private boolean verifyChatHeader(ClawAccessibilityService service, String contact) {
        return isAlreadyInChatWith(service, contact);
    }

    private boolean isAlreadyInChatWith(ClawAccessibilityService service, String contact) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);

        List<AccessibilityNodeInfo> topNodes = new ArrayList<>();
        collectTextNodesInRegion(root, 0, 350, topNodes);
        for (AccessibilityNodeInfo node : topNodes) {
            if (ContactMatchUtils.matchesTarget(node.getText(), node.getContentDescription(), normalizedAliases, digitAliases)) {
                return true;
            }
        }
        return false;
    }

    private void collectTextNodesInRegion(AccessibilityNodeInfo node, int minY, int maxY, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top >= minY && bounds.bottom <= maxY && (node.getText() != null || node.getContentDescription() != null)) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTextNodesInRegion(child, minY, maxY, result);
        }
    }

    private boolean waitForActiveWindow(ClawAccessibilityService service, String packageName, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null && packageName.equals(root.getPackageName().toString())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private boolean findAndTapContact(ClawAccessibilityService service, String contact) throws InterruptedException {
        LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);
        return ContactListUiUtils.searchOrScrollAndFindAndClick(service, contact, normalizedAliases, digitAliases, 6, 200);
    }

    private boolean typeInBottomEditText(ClawAccessibilityService service, String message) throws InterruptedException {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        List<AccessibilityNodeInfo> editables = new ArrayList<>();
        collectEditTexts(root, editables);

        if (editables.isEmpty()) return false;

        AccessibilityNodeInfo best = null;
        int maxBottom = -1;
        for (AccessibilityNodeInfo editable : editables) {
            Rect bounds = new Rect();
            editable.getBoundsInScreen(bounds);
            if (bounds.bottom > maxBottom) {
                maxBottom = bounds.bottom;
                best = editable;
            }
        }

        if (best == null) return false;

        boolean success = service.setNodeText(best, message);
        if (!success) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message);
            success = best.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        return success;
    }

    private void collectEditTexts(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> results) {
        if (node == null || !node.isVisibleToUser()) return;
        if (node.isEditable() || (node.getClassName() != null && node.getClassName().toString().contains("EditText"))) {
            results.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectEditTexts(child, results);
        }
    }

    private boolean tapSendOrEnter(ClawAccessibilityService service) throws InterruptedException {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        List<AccessibilityNodeInfo> sendNodes = new ArrayList<>();
        collectSendButtons(root, sendNodes);

        for (AccessibilityNodeInfo sendNode : sendNodes) {
            if (sendNode.isVisibleToUser()) {
                boolean clicked = service.clickNode(sendNode);
                if (clicked) return true;
            }
        }

        return service.sendKeyEvent(KeyEvent.KEYCODE_ENTER);
    }

    private void collectSendButtons(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> results) {
        if (node == null || !node.isVisibleToUser()) return;
        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();
        CharSequence resId = node.getViewIdResourceName();

        String descStr = desc != null ? desc.toString().toLowerCase() : "";
        String textStr = text != null ? text.toString().toLowerCase() : "";
        String resIdStr = resId != null ? resId.toString().toLowerCase() : "";

        if (descStr.contains("send") || textStr.equals("send") || resIdStr.contains("send_button") || resIdStr.contains("send_btn") || descStr.contains("發送") || descStr.contains("发送")) {
            results.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectSendButtons(child, results);
        }
    }
}
