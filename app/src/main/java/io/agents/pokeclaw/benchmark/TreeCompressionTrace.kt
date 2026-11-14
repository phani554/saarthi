// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.benchmark

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Spelled-out tree compression for the demo: while a benchmark run records, the first few screens of
 * the run are traced stage by stage (what the raw accessibility tree had, what each rule dropped, with
 * real examples, and what the model was finally sent), plus each time old screens in the chat history
 * were shrunk. Written as a readable text file next to runs.jsonl and shown from the Benchmark screen.
 */
object TreeCompressionTrace {

    const val SCREENS_PER_RUN = 3
    private const val EXAMPLES = 3

    private class Screen {
        var keyboard = 0
        var invisible = 0
        var container = 0
        var kept = 0
        var truncated = 0
        var dupDesc = 0
        val keyboardEx = mutableListOf<String>()
        val invisibleEx = mutableListOf<String>()
        val containerEx = mutableListOf<String>()
        val keptEx = mutableListOf<Pair<String, String>>()
    }

    private val lock = Any()
    @Volatile private var screen: Screen? = null
    private val sections = mutableListOf<String>()
    private var screensThisRun = 0

    /** New run: drop the previous run's trace. */
    fun startRun() = synchronized(lock) { sections.clear(); screensThisRun = 0; screen = null }

    /** Arms tracing for the screen about to be built; false when this run already has enough screens. */
    @JvmStatic
    fun begin(): Boolean = synchronized(lock) {
        if (screensThisRun >= SCREENS_PER_RUN) { screen = null; return false }
        screen = Screen(); true
    }

    @JvmStatic fun active(): Boolean = screen != null

    @JvmStatic fun onKeyboard(n: AccessibilityNodeInfo) { screen?.let { it.keyboard++; ex(it.keyboardEx, n) } }
    @JvmStatic fun onInvisible(n: AccessibilityNodeInfo) { screen?.let { it.invisible++; ex(it.invisibleEx, n) } }
    @JvmStatic fun onContainer(n: AccessibilityNodeInfo) { screen?.let { it.container++; ex(it.containerEx, n) } }
    @JvmStatic fun onTruncated() { screen?.let { it.truncated++ } }
    @JvmStatic fun onDuplicateDesc() { screen?.let { it.dupDesc++ } }
    @JvmStatic fun onKept(n: AccessibilityNodeInfo, line: String) {
        val s = screen ?: return
        s.kept++
        // Prefer examples that carry text: they show the rewrite best.
        if (s.keptEx.size < EXAMPLES && !n.text.isNullOrEmpty()) s.keptEx += rawLine(n) to line.trim()
    }

    private fun ex(list: MutableList<String>, n: AccessibilityNodeInfo) { if (list.size < EXAMPLES) list += rawLine(n) }

    /** The node the way the full dump shows it. */
    fun rawLine(n: AccessibilityNodeInfo): String = buildString {
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        append('[').append(cls).append(']')
        n.text?.takeIf { it.isNotEmpty() }?.let { append(" text=\"").append(it.toString().take(60)).append('"') }
        n.contentDescription?.takeIf { it.isNotEmpty() }?.let { append(" desc=\"").append(it.toString().take(60)).append('"') }
        n.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { append(" id=\"").append(it).append('"') }
        n.packageName?.let { append(" pkg=\"").append(it).append('"') }
        if (n.isClickable) append(" [clickable]")
        if (n.isScrollable) append(" [scrollable]")
        if (n.isEditable) append(" [editable]")
        if (!n.isVisibleToUser) append(" [not visible]")
    }

    /** Screen finished: [fullDump] is the raw tree text, [pruned] what the model got. */
    @JvmStatic
    fun finishScreen(pkg: String?, rawNodes: Int, fullDump: String, prunedNodes: Int, pruned: String) {
        val s = synchronized(lock) { screen.also { screen = null } } ?: return
        val text = render(++screensThisRun, pkg, s, rawNodes, fullDump, prunedNodes, pruned)
        synchronized(lock) { sections += text }
    }

    /** Old tool results in the chat history were shortened before an LLM call. */
    @JvmStatic
    fun onHistoryCompressed(round: Int, charsBefore: Int, charsAfter: Int, example: String?) {
        if (charsBefore <= charsAfter) return
        synchronized(lock) {
            if (sections.count { it.startsWith("## History") } >= 3) return
            sections += buildString {
                append("## History compression before LLM call ").append(round).append("\n\n")
                append("Older screens and tool results already used by the model are replaced by one-line summaries; ")
                append("the latest screens stay whole.\n\n")
                append("- Prompt history: ").append(charsBefore).append(" -> ").append(charsAfter).append(" chars (")
                append(pct(charsBefore, charsAfter)).append("% smaller)\n")
                example?.let { append("- Example summary line: `").append(it.replace('\n', ' ').take(160)).append("`\n") }
                append('\n')
            }
        }
    }

    /** The whole run's trace, or null when nothing was traced. */
    fun takeRunText(header: String): String? = synchronized(lock) {
        if (sections.isEmpty()) return null
        val out = "# Tree compression trace\n\n$header\n\n" + sections.joinToString("\n")
        sections.clear()
        out
    }

    private fun render(n: Int, pkg: String?, s: Screen, rawNodes: Int, full: String, prunedNodes: Int, pruned: String): String = buildString {
        append("## Screen ").append(n).append(" (").append(pkg ?: "?").append(")\n\n")
        append("Stage 0. Raw accessibility tree: ").append(rawNodes).append(" nodes, ").append(full.length).append(" chars.\n\n")
        append("Stage 1. Keyboard windows removed: ").append(s.keyboard).append(" nodes.\n")
        s.keyboardEx.forEach { append("    dropped: ").append(it).append('\n') }
        append("Stage 2. Off-screen nodes skipped (their children are still checked): ").append(s.invisible).append(" nodes.\n")
        s.invisibleEx.forEach { append("    dropped: ").append(it).append('\n') }
        append("Stage 3. Empty layout containers flattened (no text, no description, not tappable/scrollable/editable): ")
            .append(s.container).append(" nodes; their children move up one level.\n")
        s.containerEx.forEach { append("    flattened: ").append(it).append('\n') }
        append("Stage 4. Kept nodes rewritten to one short line each: ").append(s.kept).append(" nodes. ")
        append("Class, package and false flags are dropped; the id loses its package prefix; ")
        append("each node gets a handle [nX] and its centre (x,y) so the model can tap it.\n")
        append("    texts cut to 120 chars: ").append(s.truncated).append(", descriptions dropped as same as text: ").append(s.dupDesc).append('\n')
        s.keptEx.forEach { (raw, line) ->
            append("    raw : ").append(raw).append('\n')
            append("    sent: ").append(line).append('\n')
        }
        append("\nResult: ").append(rawNodes).append(" -> ").append(prunedNodes).append(" nodes (")
            .append(pct(rawNodes, prunedNodes)).append("% fewer), ")
            .append(full.length).append(" -> ").append(pruned.length).append(" chars (")
            .append(pct(full.length, pruned.length)).append("% smaller).\n\n")
        append("First lines of the raw tree:\n```\n").append(full.lineSequence().take(15).joinToString("\n")).append("\n```\n")
        append("What the model received (first lines):\n```\n").append(pruned.lineSequence().take(15).joinToString("\n")).append("\n```\n")
    }

    private fun pct(raw: Int, out: Int): Int = if (raw <= 0) 0 else ((raw - out) * 100.0 / raw).toInt()
}
