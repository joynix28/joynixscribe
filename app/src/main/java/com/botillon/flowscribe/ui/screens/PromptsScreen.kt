package com.botillon.flowscribe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.botillon.flowscribe.data.PromptPreset
import com.botillon.flowscribe.ui.components.FlowCard
import com.botillon.flowscribe.ui.components.ScreenHeader
import com.botillon.flowscribe.ui.components.SectionHeader
import com.botillon.flowscribe.ui.components.StatusPill

@Composable
fun PromptsScreen(
    prompts: List<PromptPreset>,
    activeId: String,
    onSelect: (String) -> Unit,
    onCreate: (String, String, String) -> Unit,
    onUpdate: (PromptPreset) -> Unit,
    onDuplicate: (PromptPreset) -> Unit,
    onDelete: (String) -> Unit,
    onReset: () -> Unit,
) {
    var editor by remember { mutableStateOf<PromptPreset?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteFor by remember { mutableStateOf<PromptPreset?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

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
                title = "Styles",
                subtitle = "Chaque style change réellement la sortie de l'IA.",
                trailing = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.clickable { creating = true },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Créer", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
            )
        }

        item {
            FlowCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Le style actif est envoyé comme consigne système à Gemini. Si Groq transcrit, un reformatage court est appliqué (désactivable dans Réglages).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "${prompts.size} styles",
                subtitle = "Touche un style pour l'activer",
                trailing = {
                    TextButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Rounded.RestartAlt, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Réinitialiser")
                    }
                },
            )
        }

        items(prompts, key = { it.id }) { prompt ->
            PromptCard(
                prompt = prompt,
                active = prompt.id == activeId,
                onSelect = { onSelect(prompt.id) },
                onEdit = { editor = prompt },
                onDuplicate = { onDuplicate(prompt) },
                onDelete = { deleteFor = prompt },
            )
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "Astuce : un style « Email pro » reformule, un style « Dictée fidèle » ne touche à rien. Pour un jargon précis, ajoute les mots dans le Dico.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (creating) {
        PromptEditorDialog(
            prompt = null,
            onDismiss = { creating = false },
            onSave = { name, description, instructions ->
                onCreate(name, description, instructions)
                creating = false
            },
        )
    }

    editor?.let { prompt ->
        PromptEditorDialog(
            prompt = prompt,
            onDismiss = { editor = null },
            onSave = { name, description, instructions ->
                onUpdate(
                    prompt.copy(
                        name = name,
                        description = description,
                        instructions = instructions,
                    ),
                )
                editor = null
            },
        )
    }

    deleteFor?.let { prompt ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Supprimer ce style ?") },
            text = { Text("« ${prompt.name} » sera définitivement retiré.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(prompt.id)
                        deleteFor = null
                    },
                ) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("Annuler") }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restaurer les styles d'origine ?") },
            text = {
                Text("Les styles intégrés retrouvent leurs consignes initiales. Tes styles personnalisés sont conservés.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReset()
                        confirmReset = false
                    },
                ) { Text("Restaurer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun PromptCard(
    prompt: PromptPreset,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FlowCard(
        containerColor = if (active) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = onSelect)
            Spacer(Modifier.width(2.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onSelect),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        prompt.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (prompt.builtIn) {
                        Spacer(Modifier.width(7.dp))
                        StatusPill(
                            text = "intégré",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            icon = Icons.Rounded.Style,
                        )
                    }
                }
                if (prompt.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        prompt.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (active) {
                Icon(
                    Icons.Rounded.Check,
                    "Actif",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "Masquer les consignes" else "Voir les consignes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.Edit,
                    "Modifier",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDuplicate, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    "Dupliquer",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!prompt.builtIn) {
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        "Supprimer",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                ) {
                    Text(
                        prompt.instructions,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptEditorDialog(
    prompt: PromptPreset?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(prompt?.name.orEmpty()) }
    var description by remember { mutableStateOf(prompt?.description.orEmpty()) }
    var instructions by remember { mutableStateOf(prompt?.instructions.orEmpty()) }
    val valid = name.isNotBlank() && instructions.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prompt == null) "Nouveau style" else "Modifier le style") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Nom") },
                    placeholder = { Text("Compte rendu, SMS, code…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(90) },
                    label = { Text("Description courte") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Consignes envoyées à l'IA") },
                    placeholder = { Text("Ex. Reformule en notes à puces, ton direct…") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Écris à l'impératif. Ces consignes ont priorité sur le style par défaut et s'appliquent à chaque dictée avec ce style.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (prompt?.builtIn == true) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Style intégré : il peut être modifié et restauré plus tard.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), description.trim(), instructions.trim()) },
                enabled = valid,
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
