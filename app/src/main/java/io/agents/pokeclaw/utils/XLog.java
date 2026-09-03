// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.util.Log;

public class XLog {
    private static boolean DEBUG = true;

    public static void setDEBUG(boolean debug) {
        DEBUG = debug;
    }

    public static void i(String tag, String msg) {
        if (!DEBUG || msg == null) return;
        try { Log.i(tag, msg); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + msg); }
    }

    public static void i(String tag, String msg, Throwable tr) {
        if (!DEBUG) return;
        try { Log.i(tag, msg, tr); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + msg); }
    }

    public static void d(String tag, String msg) {
        if (!DEBUG || msg == null) return;
        try { Log.d(tag, msg); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + msg); }
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (!DEBUG) return;
        try { Log.d(tag, msg, tr); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + msg); }
    }

    public static void e(String tag, String msg) {
        if (msg == null) return;
        try { Log.e(tag, msg); } catch (Throwable ignored) { System.err.println("[" + tag + "] " + msg); }
    }

    public static void e(String tag, String msg, Throwable tr) {
        try { Log.e(tag, msg, tr); } catch (Throwable ignored) { System.err.println("[" + tag + "] " + msg); }
    }

    public static void e(String tag, Throwable tr) {
        try { Log.e(tag, "", tr); } catch (Throwable ignored) { System.err.println("[" + tag + "] " + tr); }
    }

    public static void w(String tag, String msg) {
        if (!DEBUG || msg == null) return;
        try { Log.w(tag, msg); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + msg); }
    }

    public static void w(String tag, String msg, Throwable tr) {
        if (!DEBUG) return;
        try { Log.w(tag, msg, tr); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + msg); }
    }

    public static void w(String tag, Throwable tr) {
        if (!DEBUG) return;
        try { Log.w(tag, tr); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + tr); }
    }

    public static void v(String tag, String msg) {
        if (!DEBUG || msg == null) return;
        try { Log.v(tag, msg); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + msg); }
    }

    public static void v(String tag, String msg, Throwable tr) {
        if (!DEBUG) return;
        try { Log.v(tag, msg, tr); } catch (Throwable ignored) { System.out.println("[" + tag + "] " + msg); }
    }

    public static void wtf(String tag, String msg) {
        if (!DEBUG) return;
        try { Log.wtf(tag, msg); } catch (Throwable ignored) { System.err.println("[" + tag + "] " + msg); }
    }

    public static void wtf(String tag, String msg, Throwable tr) {
        if (!DEBUG) return;
        try { Log.wtf(tag, msg, tr); } catch (Throwable ignored) { System.err.println("[" + tag + "] " + msg); }
    }

    public static void wtf(String tag, Throwable tr) {
        if (!DEBUG) return;
        try { Log.wtf(tag, tr); } catch (Throwable ignored) { System.err.println("[" + tag + "] " + tr); }
    }
}
