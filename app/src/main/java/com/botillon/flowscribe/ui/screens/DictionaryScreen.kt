package com.botillon.flowscribe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.botillon.flowscribe.data.DictionaryEntry
import com.botillon.flowscribe.ui.components.DividerLine
import com.botillon.flowscribe.ui.components.EmptyState
import com.botillon.flowscribe.ui.components.FlowCard
import com.botillon.flowscribe.ui.components.ScreenHeader
import com.botillon.flowscribe.ui.components.SectionHeader
import com.botillon.flowscribe.ui.components.SwitchRow

@Composable
fun DictionaryScreen(
    dictionary: List<DictionaryEntry>,
    onAdd: (String, String, Boolean) -> Unit,
    onUpdate: (DictionaryEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<DictionaryEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteFor by remember { mutableStateOf<DictionaryEntry?>(null) }

    val filtered = remember(dictionary, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) dictionary
        else dictionary.filter {
            it.term.lowercase().contains(needle) ||
                it.replacement.lowercase().contains(needle)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 14.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Dictionnaire",
                subtitle = "Tes noms propres, marques et jargon, toujours bien orthographiés.",
                trailing = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { creating = true },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Ajouter", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
            )
        }

        item {
            FlowCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Translate,
                            null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(
                            "Deux effets, un seul mot",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Les termes partent avec la requête pour guider la reconnaissance, puis une correction locale remplace les variantes ratées. Tout reste sur l'appareil.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Rechercher un terme") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, null)
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            SectionHeader(
                title = when {
                    dictionary.isEmpty() -> "Aucun terme"
                    query.isBlank() -> "${dictionary.size} terme${if (dictionary.size > 1) "s" else ""}"
                    else -> "${filtered.size} résultat${if (filtered.size > 1) "s" else ""}"
                },
                subtitle = if (dictionary.isEmpty()) null else "${dictionary.count { it.enabled }} actif${if (dictionary.count { it.enabled } > 1) "s" else ""}",
            )
        }

        if (dictionary.isEmpty()) {
            item {
                FlowCard {
                    EmptyState(
                        icon = Icons.Rounded.Spellcheck,
                        title = "Corrige les mots que l'IA écorche",
                        body = "Ajoute « Botillon », « HyperOS », un prénom ou une marque. La dictée les écrira correctement.",
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            item {
                FlowCard {
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = "Aucun résultat",
                        body = "Essaie un autre mot-clé.",
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { entry ->
                DictionaryRow(
                    entry = entry,
                    onToggle = { onUpdate(entry.copy(enabled = it)) },
                    onEdit = { editor = entry },
                    onDelete = { deleteFor = entry },
                )
            }
        }

        item {
            Text(
                "Astuce : laisse la correction vide pour imposer seulement la graphie exacte (accents, majuscules). Renseigne-la pour remplacer une mauvaise transcription récurrente.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }

    if (creating) {
        DictionaryEditorDialog(
            entry = null,
            existing = dictionary,
            onDismiss = { creating = false },
            onSave = { term, replacement, caseSensitive ->
                onAdd(term, replacement, caseSensitive)
                creating = false
            },
        )
    }

    editor?.let { entry ->
        DictionaryEditorDialog(
            entry = entry,
            existing = dictionary,
            onDismiss = { editor = null },
            onSave = { term, replacement, caseSensitive ->
                onUpdate(
                    entry.copy(
                        term = term,
                        replacement = replacement,
                        caseSensitive = caseSensitive,
                    ),
                )
                editor = null
            },
        )
    }

    deleteFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Supprimer ce terme ?") },
            text = { Text("« ${entry.term} » ne sera plus corrigé ni envoyé à l'IA.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(entry.id)
                        deleteFor = null
                    },
                ) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun DictionaryRow(
    entry: DictionaryEntry,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    FlowCard(contentPadding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Indicator(entry.enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.term,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.replacement.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "→ ${entry.replacement}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        "graphie exacte",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.caseSensitive) {
                    Text(
                        "sensible à la casse",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    "Modifier",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    "Supprimer",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Indicator(enabled: Boolean) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.Spellcheck,
            null,
            tint = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun DictionaryEditorDialog(
    entry: DictionaryEntry?,
    existing: List<DictionaryEntry>,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
) {
    var term by remember { mutableStateOf(entry?.term.orEmpty()) }
    var replacement by remember { mutableStateOf(entry?.replacement.orEmpty()) }
    var caseSensitive by remember { mutableStateOf(entry?.caseSensitive ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    val duplicate = existing.any {
        it.id != entry?.id && it.term.equals(term.trim(), ignoreCase = true)
    }
    val valid = term.isNotBlank() && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "Nouveau terme" else "Modifier le terme") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = term,
                    onValueChange = {
                        term = it.take(48)
                        error = null
                    },
                    label = { Text("Terme attendu") },
                    placeholder = { Text("Botillon, HyperOS, RDV…") },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = if (duplicate) {
                        { Text("Ce terme existe déjà.") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it.take(48) },
                    label = { Text("Correction (optionnel)") },
                    placeholder = { Text("Botiyon → Botillon") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (replacement.isBlank()) {
                                "Vide : le terme sert uniquement de vocabulaire à l'IA."
                            } else {
                                "Les occurrences seront remplacées par ce texte."
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    icon = Icons.Rounded.Spellcheck,
                    title = "Sensible à la casse",
                    subtitle = "Ne corrige que la graphie exacte",
                    checked = caseSensitive,
                    onChange = { caseSensitive = it },
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!valid) {
                        error = "Vérifie le terme."
                    } else {
                        onSave(term.trim(), replacement.trim(), caseSensitive)
                    }
                },
                enabled = valid,
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
