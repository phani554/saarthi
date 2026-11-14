// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.benchmark

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import io.agents.pokeclaw.benchmark.BenchmarkUploader
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.agents.pokeclaw.benchmark.BenchmarkRecorder
import io.agents.pokeclaw.benchmark.CapabilityResult
import io.agents.pokeclaw.benchmark.DeviceCapabilityCheck
import io.agents.pokeclaw.benchmark.DeviceInfo
import io.agents.pokeclaw.benchmark.RealTestResult
import io.agents.pokeclaw.benchmark.RunSummary
import io.agents.pokeclaw.benchmark.Verdict
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Benchmark screen: Benchmark mode switch, device facts, "Can this phone run the local model?"
 * check with an optional real test, the last 20 recorded runs, Export and Clear. Large text on purpose.
 * Opened from Settings (class name is referenced there; keep it).
 */
class BenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BenchmarkScreen(onBack = { finish() }) } }
    }
}

private val BODY = 19.sp
private val TITLE = 24.sp
private val GREEN = Color(0xFF1B7F3B)
private val AMBER = Color(0xFFB26A00)
private val RED = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BenchmarkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(BenchmarkRecorder.isEnabled()) }
    var device by remember { mutableStateOf<DeviceInfo?>(null) }
    var capability by remember { mutableStateOf<CapabilityResult?>(null) }
    var runs by remember { mutableStateOf<List<RunSummary>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<RealTestResult?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var traceText by remember { mutableStateOf<String?>(null) }
    var askName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { DeviceCapabilityCheck.collectDeviceInfo(context) }.getOrNull() }
            val c = withContext(Dispatchers.IO) { runCatching { DeviceCapabilityCheck.check(context) }.getOrNull() }
            val r = withContext(Dispatchers.IO) { runCatching { BenchmarkRecorder.readRecent(context, 20) }.getOrDefault(emptyList()) }
            device = d
            capability = c
            runs = r
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Benchmark", fontSize = TITLE, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ---------- Benchmark mode ----------
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Benchmark mode", fontSize = TITLE, fontWeight = FontWeight.Bold)
                            Text(
                                if (enabled) "On: every task is measured and saved." else "Off: nothing is measured.",
                                fontSize = BODY,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                BenchmarkRecorder.setEnabled(it)
                            },
                        )
                    }
                }
            }

            // ---------- Device ----------
            item {
                SectionCard {
                    Text("This phone", fontSize = TITLE, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    val d = device
                    if (d == null) {
                        Text("Reading…", fontSize = BODY)
                    } else {
                        InfoLine("Phone", "${d.manufacturer.orEmpty()} ${d.model.orEmpty()}")
                        InfoLine("Chip", listOfNotNull(d.socManufacturer, d.socModel).joinToString(" ").ifBlank { d.hardware.orEmpty() })
                        InfoLine("Android", "${d.androidRelease} (SDK ${d.sdkInt})")
                        InfoLine("Memory", "${mbToGb(d.totalRamMb)} total, ${mbToGb(d.availRamMb)} free")
                        InfoLine("Processor cores", "${d.cpuCores ?: "?"}")
                        if (d.batteryPct != null) {
                            InfoLine("Battery", "${d.batteryPct}%" + (d.batteryTempC?.let { ", ${it}°C" } ?: ""))
                        }
                        if (d.thermalStatus != null) InfoLine("Heat", d.thermalStatus)
                    }
                }
            }

            // ---------- Capability ----------
            item {
                SectionCard {
                    Text("Can this phone run the offline model?", fontSize = TITLE, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val c = capability
                    if (c == null) {
                        Text("Checking…", fontSize = BODY)
                    } else {
                        val (label, color) = when (c.verdict) {
                            Verdict.YES -> "YES" to GREEN
                            Verdict.RISKY -> "RISKY" to AMBER
                            Verdict.NO -> "NO" to RED
                        }
                        Text(label, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
                        Text(c.modelName, fontSize = BODY)
                        Spacer(Modifier.height(8.dp))
                        c.reasons.forEach { r ->
                            val (mark, mc) = when (r.level) {
                                "ok" -> "✓" to GREEN
                                "warn" -> "!" to AMBER
                                "bad" -> "✗" to RED
                                else -> "•" to Color.Gray
                            }
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(mark, fontSize = BODY, color = mc, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                                Text(r.text, fontSize = BODY)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (!c.modelDownloaded) {
                            Text("The model is not downloaded, so the real test cannot run.", fontSize = BODY)
                        } else {
                            Button(
                                enabled = !testing,
                                onClick = {
                                    testing = true
                                    testResult = null
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            try {
                                                DeviceCapabilityCheck.runRealTest(context)
                                            } catch (e: Throwable) {
                                                RealTestResult(false, "Test failed: ${e.message?.take(160)}")
                                            }
                                        }
                                        testResult = res
                                        testing = false
                                        reload()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Text(if (testing) "Testing… (up to 3 min)" else "Run real test", fontSize = BODY)
                            }
                            if (testing) {
                                Spacer(Modifier.height(8.dp))
                                CircularProgressIndicator(Modifier.size(32.dp))
                            }
                        }
                        testResult?.let { t ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                t.message,
                                fontSize = BODY,
                                fontWeight = FontWeight.Bold,
                                color = if (t.ok) GREEN else RED,
                            )
                            t.backend?.let { InfoLine("Used", it + (t.mtp?.let { m -> if (m) " + MTP" else "" } ?: "")) }
                            t.loadMs?.let { InfoLine(if (t.engineWasAlreadyLoaded) "Load (earlier)" else "Load time", secs(it)) }
                            t.firstReplyMs?.let { InfoLine("Answer time", secs(it)) }
                            t.totalMs?.let { InfoLine("Total", secs(it)) }
                            t.reply?.let { InfoLine("Answer", "\"$it\"") }
                        }
                    }
                }
            }

            // ---------- Runs ----------
            item {
                SectionCard {
                    Text("Last 20 runs", fontSize = TITLE, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !busy && runs.isNotEmpty(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    val intent = withContext(Dispatchers.IO) { buildExportIntent(context) }
                                    busy = false
                                    if (intent == null) {
                                        Toast.makeText(context, "Nothing to export yet.", Toast.LENGTH_LONG).show()
                                    } else {
                                        try {
                                            context.startActivity(Intent.createChooser(intent, "Share benchmark"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "No app to share with.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text("Export", fontSize = BODY) }
                        OutlinedButton(
                            enabled = !busy && runs.isNotEmpty(),
                            onClick = { confirmClear = true },
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text("Clear", fontSize = BODY) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !busy && runs.isNotEmpty(),
                        onClick = {
                            if (!BenchmarkUploader.isConfigured()) {
                                Toast.makeText(context, "Sending is not set up in this build. Use Export.", Toast.LENGTH_LONG).show()
                            } else if (BenchmarkUploader.testerName().isBlank()) {
                                askName = true
                            } else {
                                busy = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { BenchmarkUploader.send(context) }
                                    busy = false
                                    val msg = when (r) {
                                        is BenchmarkUploader.Result.Sent -> "Report sent (${r.files} files)."
                                        is BenchmarkUploader.Result.Failed -> "Could not send: ${r.reason}"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(if (busy) "Sending…" else "Send report", fontSize = BODY) }
                    if (runs.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (enabled) "No runs yet. Give Saathi a task to measure it." else "No runs yet. Turn on Benchmark mode, then give Saathi a task.",
                            fontSize = BODY,
                        )
                    }
                }
            }
            items(runs) { r ->
                RunRow(r, onShowTrace = {
                    scope.launch {
                        traceText = withContext(Dispatchers.IO) { BenchmarkRecorder.readTrace(context, r.treeTraceFile) }
                            ?: "No trace for this run."
                    }
                })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    traceText?.let { text ->
        AlertDialog(
            onDismissRequest = { traceText = null },
            title = { Text("Tree compression", fontSize = TITLE) },
            text = {
                Text(
                    text,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { traceText = null }) { Text("Close", fontSize = BODY) } },
        )
    }

    if (askName) {
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text("Your name", fontSize = TITLE) },
            text = {
                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it.take(40) },
                    singleLine = true, label = { Text("So Phani knows whose report it is") })
            },
            confirmButton = {
                TextButton(enabled = nameDraft.isNotBlank(), onClick = {
                    BenchmarkUploader.setTesterName(nameDraft)
                    askName = false
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { BenchmarkUploader.send(context) }
                        busy = false
                        val msg = when (r) {
                            is BenchmarkUploader.Result.Sent -> "Report sent (${r.files} files)."
                            is BenchmarkUploader.Result.Failed -> "Could not send: ${r.reason}"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Send", fontSize = BODY) }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text("Cancel", fontSize = BODY) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all runs?", fontSize = TITLE) },
            text = { Text("All saved benchmark results will be deleted.", fontSize = BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { BenchmarkRecorder.clear(context) }
                        reload()
                    }
                }) { Text("Delete", fontSize = BODY, color = RED) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", fontSize = BODY) }
            },
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", fontSize = BODY, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = BODY)
    }
}

@Composable
private fun RunRow(r: RunSummary, onShowTrace: () -> Unit) {
    val color = when (r.outcome) {
        "success" -> GREEN
        "cancelled" -> AMBER
        else -> RED
    }
    val outcomeText = when (r.outcome) {
        "success" -> "Done"
        "cancelled" -> "Stopped"
        else -> "Failed"
    }
    SectionCard {
        Text(r.task.ifBlank { "(no text)" }, fontSize = BODY, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(r.model.ifBlank { "?" }, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(String.format("%.1f s", r.totalSec), fontSize = BODY)
            Text("${r.steps} steps", fontSize = BODY)
            r.treeReductionPct?.let { Text("tree −${it.toInt()}%", fontSize = BODY) }
            Text(outcomeText, fontSize = BODY, color = color, fontWeight = FontWeight.Bold)
        }
        Text(r.timestamp, fontSize = 14.sp, color = Color.Gray)
        if (r.treeTraceFile != null) {
            TextButton(onClick = onShowTrace) { Text("Show tree compression, step by step", fontSize = BODY) }
        }
    }
}

private fun mbToGb(mb: Long?): String = if (mb == null) "?" else String.format("%.1f GB", mb / 1024.0)

private fun secs(ms: Long): String = String.format("%.1f s", ms / 1000.0)

/**
 * Copies runs.jsonl and writes summary.csv into cacheDir/benchmark_export (covered by the
 * existing <cache-path name="cache" path="."/> FileProvider entry), then builds a share intent.
 */
private fun buildExportIntent(context: Context): Intent? {
    return try {
        val runs = BenchmarkRecorder.runsFile(context)
        if (!runs.exists() || runs.length() == 0L) return null
        val dir = File(context.cacheDir, "benchmark_export").apply { mkdirs() }
        val jsonl = File(dir, "runs.jsonl")
        runs.copyTo(jsonl, overwrite = true)
        val csv = File(dir, "summary.csv")
        BenchmarkRecorder.writeCsv(context, csv)
        val authority = context.packageName + ".fileprovider"
        val uris = arrayListOf<Uri>(
            FileProvider.getUriForFile(context, authority, jsonl),
            FileProvider.getUriForFile(context, authority, csv),
        )
        // Spelled-out tree compression of each traced run (for the demo / report).
        BenchmarkRecorder.tracesDir(context).listFiles()?.sortedBy { it.name }?.forEach { t ->
            val copy = File(dir, t.name)
            t.copyTo(copy, overwrite = true)
            uris += FileProvider.getUriForFile(context, authority, copy)
        }
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Saathi benchmark")
            clipData = ClipData.newUri(context.contentResolver, "runs.jsonl", uris[0]).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (e: Exception) {
        XLog.w("BenchmarkActivity", "export failed", e)
        null
    }
}
