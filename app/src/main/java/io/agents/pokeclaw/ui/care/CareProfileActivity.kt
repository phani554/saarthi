// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.care

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.care.*
import io.agents.pokeclaw.ui.ElderScaled
import io.agents.pokeclaw.ui.edgePadding
import io.agents.pokeclaw.ui.widthFraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Care profile: prescription upload (medicines, doctor, conditions), medical history, building watchman,
 * family doctor, chemist, emergency contacts, and the order SOS calls them in. Large text on purpose.
 */
class CareProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ElderScaled { MaterialTheme { CareProfileScreen(onBack = { finish() }) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CareProfileScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(CareProfileStore.get()) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PrescriptionExtractor.Extraction?>(null) }
    fun refresh() { profile = CareProfileStore.get() }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CALL_PHONE] != true) {
            Toast.makeText(context, "Without call permission, SOS can only open the dialer.", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        // Ask only for what is still missing, so the dialog doesn't reappear on every visit/rotation.
        val missing = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray())
    }
    // Two taps within 4 s (same guard as the chat screen), so a stray touch while editing never phones anyone.
    var sosArmedAt by remember { mutableStateOf(0L) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { PrescriptionExtractor.extract(context, uri) }
            busy = false
            result.onSuccess { pending = it }
                .onFailure { Toast.makeText(context, "Could not read it: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Care profile", fontSize = 22.sp) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = edgePadding()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (now - sosArmedAt < 4_000L) {
                                sosArmedAt = 0L
                                val appContext = context.applicationContext
                                scope.launch {
                                    // trigger() speaks through VoiceManager and may wait on it: not on main.
                                    val msg = withContext(Dispatchers.IO) {
                                        try {
                                            DistressEscalator.trigger(appContext, "button")
                                        } catch (e: Exception) {
                                            "SOS could not start: ${e.message}"
                                        }
                                    }
                                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                                }
                            } else {
                                sosArmedAt = now
                                Toast.makeText(context, "Tap SOS again to call for help", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        modifier = Modifier.weight(1f).height(widthFraction(0.18f, 64.dp, 96.dp))
                    ) { Text("SOS", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { DistressEscalator.cancel(context) }, modifier = Modifier.height(widthFraction(0.18f, 64.dp, 96.dp))) { Text("Stop SOS") }
                }
            }

            // Contacts Saathi heard about in conversation. Never called or texted by SOS until confirmed.
            val pendingContacts = profile.pendingContacts()
            if (pendingContacts.isNotEmpty()) {
                item { Section("New contacts to check") }
                item {
                    Text(
                        "Saathi heard these in conversation. Confirm to save them, or remove them. SOS never calls a contact until you confirm.",
                        fontSize = 14.sp,
                    )
                }
                items(pendingContacts.size) { i ->
                    val c = pendingContacts[i]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.label(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(CareRoles.displayName(c.role), c.phone.ifBlank { "no number yet" })
                                    .joinToString(" · "),
                                fontSize = 15.sp,
                            )
                            if (c.note.isNotBlank()) Text(c.note, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = { CareProfileStore.confirmContact(c); refresh() },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) { Text("Confirm", fontSize = 16.sp) }
                                OutlinedButton(
                                    onClick = { CareProfileStore.discardPendingContact(c); refresh() },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) { Text("Remove", fontSize = 16.sp) }
                            }
                        }
                    }
                }
            }

            item { Section("Prescription") }
            // Local mode keeps everything on the phone, except this photo, which the user chooses to send.
            if (io.agents.pokeclaw.agent.llm.ModelConfigRepository.isLocalActive()) {
                item {
                    Text(
                        "This photo is sent to ${PrescriptionExtractor.uploadTarget() ?: "OpenRouter"} to read it.",
                        fontSize = 16.sp
                    )
                }
            }
            item {
                Button(onClick = { picker.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (busy) "Reading prescription…" else "Upload prescription photo", fontSize = 18.sp)
                }
            }
            if (profile.medicines.isNotEmpty()) {
                items(profile.medicines.size) { i ->
                    val m = profile.medicines[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(listOf(m.dose, m.frequency, m.duration).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 15.sp)
                        }
                        IconButton(onClick = { CareProfileStore.update { p -> p.copy(medicines = p.medicines - m) }; refresh() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${m.name}")
                        }
                    }
                }
            }

            item { Section("Medical history") }
            item { HistoryEditor(profile.medicalHistory) { CareProfileStore.update { p -> p.copy(medicalHistory = it) }; refresh() } }

            item { Section("Important contacts") }
            listOf(CareRoles.WATCHMAN, CareRoles.FAMILY_DOCTOR, CareRoles.CHEMIST).forEach { role ->
                item(key = role) {
                    val existing = profile.first(role)
                    ContactEditor(
                        CareRoles.displayName(role), existing, showRelation = false,
                        onDelete = existing?.let { c ->
                            {
                                CareProfileStore.update { p -> p.copy(contacts = p.contacts - c, distressPriority = p.distressPriority - CareProfile.keyOf(c)) }
                                refresh()
                            }
                        }
                    ) { name, phone, _ ->
                        CareProfileStore.upsertContact(CareContact(role, name, phone)); refresh()
                    }
                }
            }
            item { Section("Emergency contacts (loved ones)") }
            // Pending (unconfirmed) loved ones are listed in "New contacts to check" above.
            val lovedOnes = profile.byRole(CareRoles.LOVED_ONE).filter { !it.pending }
            items(lovedOnes.size) { i ->
                val c = lovedOnes[i]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.label()}  ${c.phone}", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        CareProfileStore.update { p -> p.copy(contacts = p.contacts - c, distressPriority = p.distressPriority - CareProfile.keyOf(c)) }; refresh()
                    }) { Icon(Icons.Default.Delete, contentDescription = "Remove ${c.label()}") }
                }
            }
            item {
                ContactEditor("Add loved one", null, showRelation = true) { name, phone, relation ->
                    CareProfileStore.upsertContact(CareContact(CareRoles.LOVED_ONE, name, phone, relation)); refresh()
                }
            }

            item { Section("SOS call order") }
            item { Text("When SOS is pressed or she says \"bachao\" / \"help\", Saathi texts everyone, then calls top to bottom until someone answers.", fontSize = 14.sp) }
            val order = profile.distressOrder()
            items(order.size) { i ->
                val c = order[i]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${c.label()} · ${CareRoles.displayName(c.role)}", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(enabled = i > 0, onClick = { move(order, i, -1); refresh() }) { Icon(Icons.Default.ArrowUpward, "Move up") }
                    IconButton(enabled = i < order.size - 1, onClick = { move(order, i, +1); refresh() }) { Icon(Icons.Default.ArrowDownward, "Move down") }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    pending?.let { ex ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Prescription read") },
            text = {
                Column {
                    ex.doctor?.let { Text("Doctor: ${it.name}${if (it.note.isNotBlank()) " (${it.note})" else ""}") }
                    if (ex.date.isNotBlank()) Text("Date: ${ex.date}")
                    if (ex.conditions.isNotEmpty()) Text("Conditions: ${ex.conditions.joinToString(", ")}")
                    Spacer(Modifier.height(6.dp))
                    ex.medicines.forEach { Text("• ${it.name} ${it.dose} ${it.frequency} ${it.duration}".trim()) }
                    if (ex.warnings.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text("Check: ${ex.warnings.joinToString("; ")}", color = Color(0xFFB26A00)) }
                    Spacer(Modifier.height(6.dp))
                    Text("Please check the names against the paper before saving.", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CareProfileStore.addMedicines(ex.medicines)
                    CareProfileStore.addHistory(ex.conditions)
                    ex.doctor?.let { d ->
                        // Becomes the family doctor only if none is saved yet.
                        val role = if (CareProfileStore.get().first(CareRoles.FAMILY_DOCTOR) == null) CareRoles.FAMILY_DOCTOR else CareRoles.DOCTOR
                        CareProfileStore.upsertContact(d.copy(role = role))
                    }
                    pending = null; refresh()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Discard") } }
        )
    }
}

private fun move(order: List<CareContact>, i: Int, delta: Int) {
    val keys = order.map { CareProfile.keyOf(it) }.toMutableList()
    val j = i + delta
    if (j !in keys.indices) return
    keys[i] = keys[j].also { keys[j] = keys[i] }
    CareProfileStore.update { it.copy(distressPriority = keys) }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ContactEditor(
    title: String,
    existing: CareContact?,
    showRelation: Boolean,
    onDelete: (() -> Unit)? = null,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var relation by remember(existing) { mutableStateOf(existing?.relation.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (showRelation) OutlinedTextField(relation, { relation = it }, label = { Text("Relation (son, daughter…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(name.trim(), phone.trim(), relation.trim()); if (showRelation) { name = ""; phone = ""; relation = "" } },
                    enabled = phone.count { it.isDigit() } >= 7
                ) { Text("Save") }
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun HistoryEditor(items: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• $h", fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { onChange(items - h) }) { Icon(Icons.Default.Delete, contentDescription = "Remove $h") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, label = { Text("Add condition, allergy or surgery") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (text.isNotBlank()) { onChange(items + text.trim()); text = "" } }) { Text("Add") }
        }
    }
}
