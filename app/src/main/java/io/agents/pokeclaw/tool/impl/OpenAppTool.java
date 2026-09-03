// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.utils.ContactListUiUtils;
import io.agents.pokeclaw.utils.NodeFinder;
import io.agents.pokeclaw.utils.XLog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class OpenAppTool extends BaseTool {

    private static final String TAG = "OpenAppTool";

    private static final List<String> ALLOW_KEYWORDS = Arrays.asList(
            "允许", "允许打开", "打开", "Allow", "ALLOW"
    );
    private static final List<String> POSITIVE_BUTTON_IDS = Arrays.asList(
            "android:id/button1",
            "miuix.appcompat:id/button1",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button"
    );

    @Override
    public String getName() {
        return "open_app";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_open_app);
    }

    @Override
    public String getDescriptionEN() {
        return "Open an application by its package name (e.g. 'com.android.settings'). Always resets app to clean Home page.";
    }

    @Override
    public String getDescriptionCN() {
        return "Open an app by package name (e.g. 'com.android.settings'). Always resets app to clean Home page.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("package_name", "string", "The package name of the app to open", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String packageName = params.containsKey("package_name")
                ? requireString(params, "package_name")
                : requireString(params, "app_name");

        if (!packageName.contains(".")) {
            String resolved = resolveAppNameStatic(packageName);
            if (resolved != null) {
                XLog.i(TAG, "Resolved app name '" + packageName + "' → '" + resolved + "'");
                packageName = resolved;
            }
        }

        boolean success = openAppWithInterceptHandling(service, packageName);
        if (!success) {
            return ToolResult.error("Failed to open app: " + packageName + ". Make sure the app is installed.");
        }

        return ToolResult.success("Opened app: " + packageName + " (reset to clean Home page)");
    }

    public static String resolveAppNameStatic(String appName) {
        try {
            PackageManager pm = ClawApplication.Companion.getInstance().getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(mainIntent, 0);
            if (infos != null) {
                for (ResolveInfo info : infos) {
                    String label = info.loadLabel(pm).toString();
                    if (label.equalsIgnoreCase(appName) || label.toLowerCase().contains(appName.toLowerCase())) {
                        return info.activityInfo.packageName;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Shared launch path used by tools that need reliable cross-app transitions.
     * Enforces activity task stack reset so apps always launch on their clean Home Page.
     */
    public static boolean openAppWithInterceptHandling(ClawAccessibilityService service, String packageName) {
        boolean success = service.openApp(packageName);
        if (!success) {
            return false;
        }
        dismissChainLaunchDialog(service);

        // Precaution: Ensure opened app is on its clean Home Screen (popping mounted sub-screens)
        ensureCleanAppHomeScreen(service, packageName);

        if ("com.flipkart.android".equals(packageName)) {
            NodeFinder.INSTANCE.ensureFlipkartMinutesMode(service);
        }

        return true;
    }

    private static void ensureCleanAppHomeScreen(ClawAccessibilityService service, String packageName) {
        try {
            for (int backCount = 0; backCount < 4; backCount++) {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root == null) break;

                boolean isSubScreen = false;

                if ("com.whatsapp".equals(packageName) && ContactListUiUtils.isOpenChatroom(root)) {
                    isSubScreen = true;
                } else if ("com.flipkart.android".equals(packageName)) {
                    AccessibilityNodeInfo prodDetail = NodeFinder.INSTANCE.findNodeByIdOrText(root,
                        "com.flipkart.android:id/product_details", "com.flipkart.android:id/search_auto_complete"
                    );
                    if (prodDetail != null) isSubScreen = true;
                } else if ("com.grofers.customerapp".equals(packageName)) {
                    AccessibilityNodeInfo pDetail = NodeFinder.INSTANCE.findNodeByIdOrText(root,
                        "com.grofers.customerapp:id/product_detail_container", "com.grofers.customerapp:id/et_search"
                    );
                    if (pDetail != null) isSubScreen = true;
                }

                if (isSubScreen) {
                    XLog.w(TAG, "ensureCleanAppHomeScreen: " + packageName + " is on a mounted sub-screen! Pressing Back to return to clean Home page (back " + (backCount + 1) + "/4).");
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    Thread.sleep(150L);
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            XLog.w(TAG, "ensureCleanAppHomeScreen exception: " + e.getMessage());
        }
    }

    private static void dismissChainLaunchDialog(ClawAccessibilityService service) {
        for (int attempt = 0; attempt < 3; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String pkg = root.getPackageName().toString();
                if (pkg.contains("whatsapp") || pkg.contains("flipkart") || pkg.contains("grofers") || pkg.contains("amazon") || pkg.contains("zepto")) {
                    return;
                }
            }

            if (tapPositiveDialogButton(service)) {
                return;
            }

            for (String keyword : ALLOW_KEYWORDS) {
                List<AccessibilityNodeInfo> nodes = service.findNodesByText(keyword);
                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isVisibleToUser() && node.isClickable()) {
                        service.clickNode(node);
                        return;
                    }
                }
            }
            try { Thread.sleep(80L); } catch (InterruptedException ignored) { break; }
        }
    }

    private static boolean tapPositiveDialogButton(ClawAccessibilityService service) {
        for (String id : POSITIVE_BUTTON_IDS) {
            List<AccessibilityNodeInfo> nodes = service.findNodesById(id);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isVisibleToUser() && node.isClickable()) {
                    service.clickNode(node);
                    return true;
                }
            }
        }
        return false;
    }
}
