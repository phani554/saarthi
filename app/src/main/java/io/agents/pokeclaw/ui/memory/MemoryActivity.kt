// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.memory

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.pokeclaw.agent.knowledge.FactExtractor
import io.agents.pokeclaw.agent.knowledge.MemoryFact
import io.agents.pokeclaw.agent.knowledge.MemoryFactStore

/**
 * "What Saathi remembers": facts grouped as People, Health, Preferences and Other, in large text.
 * Each fact can be edited, deleted or (when guessed) confirmed; "Forget everything" clears all of it.
 * The care profile (SOS contacts, medicines) is separate and is not touched here.
 */
class MemoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MemoryScreen(onBack = { finish() }) } }
    }
}

private val GROUPS = listOf("People", "Health", "Preferences", "Other")

private fun groupOf(f: MemoryFact): String = when (f.category) {
    "person", "profile", "contact" -> "People"
    "health" -> "Health"
    "pref", "app" -> "Preferences"
    else -> "Other"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf(MemoryFactStore.all()) }
    var editing by remember { mutableStateOf<MemoryFact?>(null) }
    var deleting by remember { mutableStateOf<MemoryFact?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    fun refresh() { facts = MemoryFactStore.all() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("What Saathi remembers", fontSize = 24.sp) },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back", fontSize = 20.sp) } }
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Saathi uses these to help you. Change or remove anything that is wrong. " +
                        "You can also say \"yeh bhool jao\" or \"forget that\".",
                    fontSize = 18.sp
                )
            }
            if (facts.isEmpty()) {
                item { Text("Nothing saved yet.", fontSize = 22.sp, modifier = Modifier.padding(vertical = 24.dp)) }
            }
            for (group in GROUPS) {
                val inGroup = facts.filter { groupOf(it) == group }
                    .sortedWith(compareByDescending<MemoryFact> { it.pending }.thenByDescending { it.updatedAt })
                if (inGroup.isEmpty()) continue
                item(key = "header_$group") {
                    Text(group, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                }
                items(inGroup, key = { it.id }) { f ->
                    FactCard(
                        f,
                        onConfirm = { MemoryFactStore.confirm(f.id); refresh() },
                        onEdit = { editing = f },
                        onDelete = { deleting = f },
                    )
                }
            }
            if (facts.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { confirmClearAll = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        modifier = Modifier.fillMaxWidth().height(64.dp)
                    ) { Text("Forget everything", fontSize = 22.sp) }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    editing?.let { f ->
        var text by remember(f.id) { mutableStateOf(f.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Change", fontSize = 24.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(labelOf(f), fontSize = 20.sp)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(fontSize = 22.sp),
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val saved = MemoryFactStore.edit(f.id, text)
                    if (saved == null && text.isNotBlank()) {
                        Toast.makeText(context, "PINs, OTPs, passwords and card numbers are never saved.", Toast.LENGTH_LONG).show()
                    }
                    editing = null
                    refresh()
                }, modifier = Modifier.height(56.dp)) { Text("Save", fontSize = 20.sp) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel", fontSize = 20.sp) }
            }
        )
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove this?", fontSize = 24.sp) },
            text = { Text(FactExtractor.render(f.copy(pending = false)), fontSize = 22.sp) },
            confirmButton = {
                Button(
                    onClick = { MemoryFactStore.delete(f.id); deleting = null; refresh() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    modifier = Modifier.height(56.dp)
                ) { Text("Remove", fontSize = 20.sp) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep it", fontSize = 20.sp) }
            }
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Forget everything?", fontSize = 24.sp) },
            text = {
                Text(
                    "Saathi will forget all ${facts.size} things on this screen. This cannot be undone. " +
                        "Your care profile and SOS contacts are not changed.",
                    fontSize = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        MemoryFactStore.clearAll()
                        confirmClearAll = false
                        refresh()
                        Toast.makeText(context, "Memory cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    modifier = Modifier.height(56.dp)
                ) { Text("Yes, forget all", fontSize = 20.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("No", fontSize = 20.sp) }
            }
        )
    }
}

/** "Son", "Usual milk", "Health"... the part before the value. */
private fun labelOf(f: MemoryFact): String {
    val line = FactExtractor.render(f.copy(pending = false))
    val idx = line.indexOf(": ")
    return if (idx > 0) line.substring(0, idx) else when (f.category) {
        "contact" -> "Often contacts"
        "app" -> "Uses app"
        else -> "Note"
    }
}

@Composable
private fun FactCard(f: MemoryFact, onConfirm: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (f.pending) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(labelOf(f), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(f.value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (f.count > 1) Text("${f.count} times", fontSize = 18.sp)
            if (f.pending) {
                Text("Saathi guessed this. Is it right?", fontSize = 18.sp, color = Color(0xFFE65100))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (f.pending) {
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f).height(56.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Yes", fontSize = 18.sp) }
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).height(56.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Change", fontSize = 18.sp, maxLines = 1) }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f).height(56.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Remove", fontSize = 18.sp, maxLines = 1) }
            }
        }
    }
}
