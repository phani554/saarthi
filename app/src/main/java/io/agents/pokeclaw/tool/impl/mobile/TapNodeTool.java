// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl.mobile;

import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tap a UI element by its node ID (e.g. "n3") from get_screen_info output.
 * Uses resilient ACTION_CLICK + ancestor fallback + gesture tap, returning rich, informative label feedback.
 */
public class TapNodeTool extends BaseTool {

    @Override
    public String getName() {
        return "tap_node";
    }

    @Override
    public String getDisplayName() {
        return "Tap Node";
    }

    @Override
    public String getDescriptionEN() {
        return "Tap a UI element by its node ID (e.g. \"n3\") from the screen info. More reliable than raw coordinates.";
    }

    @Override
    public String getDescriptionCN() {
        return "Tap a UI element by its node ID (e.g. \"n3\") from the screen info. More reliable than raw coordinates.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("node_id", "string", "Node ID from screen info, e.g. n3", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String nodeId = requireString(params, "node_id");
        if (nodeId == null || nodeId.isEmpty()) {
            return ToolResult.error("node_id is required");
        }
        // Normalize: strip brackets if user passes "[n3]"
        nodeId = nodeId.replace("[", "").replace("]", "").trim();

        ClawAccessibilityService.NodeMetadata meta = service.getNodeMetadata(nodeId);
        int[] coords = service.getNodeCoordinates(nodeId);
        if (coords == null && meta == null) {
            return ToolResult.error("Node " + nodeId + " not found. Call get_screen_info first to refresh node IDs.");
        }

        int x = meta != null ? meta.coords[0] : coords[0];
        int y = meta != null ? meta.coords[1] : coords[1];
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);

        if (meta != null && meta.node != null) {
            // Stale ID: the screen changed since get_screen_info (node gone, moved, or relabelled).
            // A coordinate fallback would hit whatever is there now, so refuse and ask for a re-read.
            io.agents.pokeclaw.a11y.StaleNodeCheck.Verdict verdict = service.checkNodeFresh(nodeId);
            if (verdict != io.agents.pokeclaw.a11y.StaleNodeCheck.Verdict.FRESH) {
                return ToolResult.error("Node " + nodeId + " is stale ("
                        + (verdict == null ? "not cached" : io.agents.pokeclaw.a11y.StaleNodeCheck.describe(verdict))
                        + "). Call get_screen_info first to refresh node IDs.");
            }
            // clickNode clicks the first clickable parent without re-checking it, so check ancestors here.
            if (BaseTool.isPaymentNodeOrAncestor(meta.node)) {
                return ToolResult.error("Blocked: node " + nodeId + " is (inside) a payment / place-order control. The user must complete payment themselves.");
            }
        }
        android.view.accessibility.AccessibilityNodeInfo activeRoot = service.getRootInActiveWindow();
        try {
            if (io.agents.pokeclaw.agent.PaymentGate.isPaymentAt(activeRoot, x, y)) {
                return ToolResult.error("Blocked: node " + nodeId + " is a payment / place-order control. The user must complete payment themselves.");
            }
            if ((meta != null && io.agents.pokeclaw.agent.OutboundConfirmGate.isDirectOutboundControl(meta.node))
                    || io.agents.pokeclaw.agent.OutboundConfirmGate.isDirectOutboundAt(activeRoot, x, y)) {
                return ToolResult.error(io.agents.pokeclaw.agent.OutboundConfirmGate.DIRECT_SEND_BLOCKED);
            }
        } finally {
            io.agents.pokeclaw.a11y.NodeRecycler.recycle(activeRoot);
        }

        service.dismissKeyboard();
        boolean success = service.clickNodeById(nodeId); // re-checks freshness right before the click

        String label = meta != null ? meta.getDisplayLabel() : nodeId;
        String cls = (meta != null && !meta.className.isEmpty()) ? " (" + meta.className.substring(meta.className.lastIndexOf('.') + 1) + ")" : "";

        if (!success) {
            // Hiding the keyboard (or an animation) can move the layout; say so instead of a vague failure.
            io.agents.pokeclaw.a11y.StaleNodeCheck.Verdict after = service.checkNodeFresh(nodeId);
            if (after != null && after != io.agents.pokeclaw.a11y.StaleNodeCheck.Verdict.FRESH) {
                return ToolResult.error("Did not tap node " + nodeId + ": it is "
                        + io.agents.pokeclaw.a11y.StaleNodeCheck.describe(after)
                        + ". Call get_screen_info again and use the new node ID.");
            }
        }
        return success
                ? ToolResult.success("Successfully tapped node " + nodeId + " '" + label + "'" + cls + " at (" + x + ", " + y + ")")
                : ToolResult.error("Failed to tap node " + nodeId + " '" + label + "' at (" + x + ", " + y + ")");
    }
}
