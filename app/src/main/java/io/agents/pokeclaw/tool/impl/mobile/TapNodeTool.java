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

        service.dismissKeyboard();
        boolean success = service.clickNodeById(nodeId);

        String label = meta != null ? meta.getDisplayLabel() : nodeId;
        String cls = (meta != null && !meta.className.isEmpty()) ? " (" + meta.className.substring(meta.className.lastIndexOf('.') + 1) + ")" : "";

        return success
                ? ToolResult.success("Successfully tapped node " + nodeId + " '" + label + "'" + cls + " at (" + x + ", " + y + ")")
                : ToolResult.error("Failed to tap node " + nodeId + " '" + label + "' at (" + x + ", " + y + ")");
    }
}
