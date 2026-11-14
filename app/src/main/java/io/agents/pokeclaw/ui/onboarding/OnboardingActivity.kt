// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.onboarding

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.pokeclaw.agent.knowledge.MemoryFactStore
import io.agents.pokeclaw.agent.knowledge.OnboardingStore
import io.agents.pokeclaw.ui.ElderScaled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time setup a family member fills in: her name, her family and what she calls them, the
 * watchman, health and usual products. Shown on first launch; reopen from Settings to add more.
 */
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ElderScaled { MaterialTheme { OnboardingScreen(onDone = { finish() }) } } }
    }
}

private class PersonRow {
    var name by mutableStateOf("")
    var relation by mutableStateOf("son")
    var nicknames by mutableStateOf("")
    var phone by mutableStateOf("")
    var sos by mutableStateOf(true)
}

private class ProductRow {
    var item by mutableStateOf("")
    var product by mutableStateOf("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var herName by remember { mutableStateOf(MemoryFactStore.get("profile:name")?.value.orEmpty()) }
    var callHer by remember { mutableStateOf(MemoryFactStore.get("profile:call_me")?.value.orEmpty()) }
    var language by remember { mutableStateOf("auto") }
    val people = remember { mutableStateListOf(PersonRow()) }
    var watchmanName by remember { mutableStateOf("") }
    var watchmanPhone by remember { mutableStateOf("") }
    var health by remember { mutableStateOf("") }
    val products = remember { mutableStateListOf(ProductRow()) }
    var saving by remember { mutableStateOf(false) }

    fun skip() { OnboardingStore.markDone(); onDone() }

    Scaffold(topBar = { TopAppBar(title = { Text("Set up Saathi") }) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Fill this once so Saathi knows the family. Leave out anything you don't know; you can add more later in Settings.", fontSize = 15.sp)
            }
            item { Section("About her") }
            item { Field("Her name", herName) { herName = it } }
            item { Field("What Saathi should call her (Amma, Dadi…)", callHer) { callHer = it } }
            item {
                Text("Language Saathi speaks", fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("auto" to "Auto", "hi-IN" to "हिंदी", "mr-IN" to "मराठी", "te-IN" to "తెలుగు", "en-IN" to "English").forEach { (code, label) ->
                        FilterChip(selected = language == code, onClick = { language = code }, label = { Text(label) })
                    }
                }
            }

            item { Section("Family and close people") }
            item { Text("Children first. Add every name she uses for them, so \"Munna ko call karo\" works.", fontSize = 14.sp) }
            items(people.size) { i ->
                val p = people[i]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Person ${i + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (people.size > 1) IconButton(onClick = { people.removeAt(i) }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                        Field("Name (as saved in contacts)", p.name) { p.name = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            listOf("son", "daughter", "grandson", "granddaughter").forEach { r ->
                                FilterChip(selected = p.relation == r, onClick = { p.relation = r }, label = { Text(r) })
                            }
                        }
                        Field("Relation (if other: brother, sister, friend…)", p.relation) { p.relation = it }
                        Field("What she calls them (Munna, Chhotu…)", p.nicknames) { p.nicknames = it }
                        Field("Phone (optional, found from contacts if empty)", p.phone, KeyboardType.Phone) { p.phone = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = p.sos, onCheckedChange = { p.sos = it })
                            Text("Call and text on SOS")
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { people.add(PersonRow().apply { relation = "" }) }) { Text("Add another person") } }

            item { Section("Building watchman") }
            item { Field("Name", watchmanName) { watchmanName = it } }
            item { Field("Phone", watchmanPhone, KeyboardType.Phone) { watchmanPhone = it } }

            item { Section("Health (optional)") }
            item { Field("Conditions, comma separated (sugar, BP…)", health) { health = it } }

            item { Section("Usual products") }
            item { Text("What she usually orders, so \"garam masala\" means her brand.", fontSize = 14.sp) }
            items(products.size) { i ->
                val r = products[i]
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(r.item, { r.item = it }, label = { Text("Item") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(r.product, { r.product = it }, label = { Text("Brand / product") }, singleLine = true, modifier = Modifier.weight(1.4f))
                }
            }
            item { OutlinedButton(onClick = { products.add(ProductRow()) }) { Text("Add another product") } }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                    Button(enabled = !saving, onClick = {
                        saving = true
                        val answers = OnboardingStore.Answers(
                            herName = herName, callHer = callHer, language = language,
                            people = people.map { OnboardingStore.Person(it.name, it.relation, it.nicknames, it.phone, it.sos) },
                            watchmanName = watchmanName, watchmanPhone = watchmanPhone, health = health,
                            usualProducts = products.map { it.item to it.product },
                        )
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { OnboardingStore.save(answers) }
                            Toast.makeText(context, "Saved $n things Saathi will remember.", Toast.LENGTH_LONG).show()
                            onDone()
                        }
                    }) { Text(if (saving) "Saving…" else "Save") }
                    TextButton(onClick = { skip() }) { Text("Skip for now") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth()
    )
}
