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
    // Runtime-permission "Allow" buttons are deliberately NOT here: open_app must never grant
    // permissions or confirm arbitrary dialogs ("Delete chat?", "Call?") on the user's behalf.
    private static final List<String> POSITIVE_BUTTON_IDS = Arrays.asList(
            "android:id/button1",
            "miuix.appcompat:id/button1"
    );
    /** Windows that show "app X wants to open app Y" chain-launch prompts. */
    private static final List<String> CHAIN_LAUNCH_PACKAGES = Arrays.asList(
            "android", "com.miui.securitycenter", "com.lbe.security.miui",
            "com.coloros.securitypermission", "com.huawei.systemmanager"
    );
    private static final String[] OPEN_PROMPT_WORDS = {
            "wants to open", "want to open", "open ", "打开", "启动", "跳转"
    };

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
        if (appName == null) return null;
        String q = appName.trim().toLowerCase();
        if (q.isEmpty()) return null;
        try {
            android.content.Context ctx = ClawApplication.Companion.getInstance();
            PackageManager pm = ctx.getPackageManager();

            // Known names first, so "Phone" can never become "PhonePe" and "Pay" no payment app.
            String known = knownPackage(ctx, pm, q);
            if (known != null) return known;

            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(mainIntent, 0);
            if (infos == null) return null;
            // Pass 1: exact label. Pass 2: label starts with the query as a whole word.
            for (ResolveInfo info : infos) {
                if (info.loadLabel(pm).toString().trim().equalsIgnoreCase(q)) return info.activityInfo.packageName;
            }
            for (ResolveInfo info : infos) {
                String label = info.loadLabel(pm).toString().trim().toLowerCase();
                if (label.startsWith(q + " ")) return info.activityInfo.packageName;
            }
            // Pass 3: substring, only when exactly one app matches.
            String only = null;
            for (ResolveInfo info : infos) {
                String label = info.loadLabel(pm).toString().toLowerCase();
                if (label.contains(q)) {
                    if (only != null && !only.equals(info.activityInfo.packageName)) return null;
                    only = info.activityInfo.packageName;
                }
            }
            return only;
        } catch (Exception ignored) {}
        return null;
    }

    private static String knownPackage(android.content.Context ctx, PackageManager pm, String q) {
        String pkg = null;
        switch (q) {
            case "whatsapp": pkg = "com.whatsapp"; break;
            case "whatsapp business": pkg = "com.whatsapp.w4b"; break;
            case "blinkit": case "grofers": pkg = "com.grofers.customerapp"; break;
            case "zepto": pkg = "com.zeptoconsumerapp"; break;
            case "flipkart": pkg = "com.flipkart.android"; break;
            case "amazon": pkg = "com.amazon.mShop.android.shopping"; break;
            case "phone": case "dialer": case "phone app": {
                android.content.pm.ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_DIAL), 0);
                if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                    pkg = ri.activityInfo.packageName;
                }
                break;
            }
            case "messages": case "sms": {
                pkg = android.provider.Telephony.Sms.getDefaultSmsPackage(ctx);
                break;
            }
            default: return null;
        }
        if (pkg == null) return null;
        return pm.getLaunchIntentForPackage(pkg) != null ? pkg : null;
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
                    try {
                        Thread.sleep(150L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
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
            if (root == null || root.getPackageName() == null) {
                try { Thread.sleep(80L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                continue;
            }
            String pkg = root.getPackageName().toString();
            // Only a system / security-center chain-launch prompt that is about opening an app.
            if (!CHAIN_LAUNCH_PACKAGES.contains(pkg)) {
                return;
            }
            if (NodeFinder.INSTANCE.findNodeByTextContains(root, OPEN_PROMPT_WORDS) == null) {
                return;
            }

            if (tapPositiveDialogButton(service)) {
                return;
            }

            for (String keyword : ALLOW_KEYWORDS) {
                List<AccessibilityNodeInfo> nodes = service.findNodesByText(keyword);
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence np = node.getPackageName();
                    if (node.isVisibleToUser() && node.isClickable() && np != null && pkg.equals(np.toString())) {
                        service.clickNode(node);
                        return;
                    }
                }
            }
            try { Thread.sleep(80L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
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
