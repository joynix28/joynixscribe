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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.botillon.flowscribe.data.ApiKeyEntry
import com.botillon.flowscribe.data.Provider
import com.botillon.flowscribe.ui.KeyProbe
import com.botillon.flowscribe.ui.components.DividerLine
import com.botillon.flowscribe.ui.components.FlowCard
import com.botillon.flowscribe.ui.components.ScreenHeader
import com.botillon.flowscribe.ui.components.SectionHeader
import com.botillon.flowscribe.ui.components.StatusPill
import com.botillon.flowscribe.ui.components.formatCooldown

@Composable
fun KeysScreen(
    keys: Map<Provider, List<ApiKeyEntry>>,
    probes: Map<String, KeyProbe>,
    onAdd: (Provider, String, String) -> Result<ApiKeyEntry>,
    onDelete: (Provider, String) -> Unit,
    onToggle: (Provider, String, Boolean) -> Unit,
    onTest: (Provider, ApiKeyEntry) -> Unit,
) {
    var addFor by remember { mutableStateOf<Provider?>(null) }
    var deleteFor by remember { mutableStateOf<Pair<Provider, ApiKeyEntry>?>(null) }
    val total = keys.values.sumOf { it.size }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 14.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = "Clés API",
                subtitle = "Jusqu'à 9 clés par fournisseur, rotation automatique.",
                trailing = {
                    StatusPill(
                        text = "$total clé${if (total > 1) "s" else ""}",
                        tint = MaterialTheme.colorScheme.primary,
                        icon = Icons.Rounded.Key,
                    )
                },
            )
        }

        item {
            FlowCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Les clés sont chiffrées par le Keystore Android et ne quittent jamais l'appareil, sauf vers leur fournisseur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(Provider.entries.filter { it.requiresKey }, key = { it.id }) { provider ->
            ProviderCard(
                provider = provider,
                keys = keys[provider].orEmpty(),
                probes = probes,
                onAdd = { addFor = provider },
                onDelete = { entry -> deleteFor = provider to entry },
                onToggle = onToggle,
                onTest = onTest,
            )
        }

        item {
            CloudflareGuideCard()
        }

        item {
            SectionHeader(title = "Comment ça marche")
            Spacer(Modifier.height(2.dp))
            FlowCard {
                Text(
                    "La première clé disponible est utilisée en priorité. En cas d'erreur 401, 403, 429 ou 5xx, elle est mise au repos quelques minutes et la suivante prend le relais.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Groq : 20 requêtes/min, 2 000 requêtes/jour, 7 200 s d'audio/heure et 28 800 s/jour sur le free tier. Ces limites sont partagées par le compte : plusieurs clés du même compte ne les contournent pas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cloudflare Workers AI : 10 000 neurons gratuits par jour et par compte. Une minute de whisper-turbo coûte environ 0,0005 $ (≈ 1,4 neuron pour 2 s). Le quota est aussi partagé par le compte.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "JoynixScribe ne fait qu'un appel par dictée par défaut. Le mode « repli anticipé » (deux appels en parallèle) est désactivé pour maîtriser la consommation. Un plafond quotidien par clé est réglable dans Réglages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    addFor?.let { provider ->
        AddKeyDialog(
            provider = provider,
            existing = keys[provider].orEmpty().size,
            onDismiss = { addFor = null },
            onAdd = { label, secret ->
                val result = onAdd(provider, label, secret)
                if (result.isSuccess) addFor = null
                result.exceptionOrNull()?.message
            },
        )
    }

    deleteFor?.let { (provider, entry) ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Supprimer cette clé ?") },
            text = {
                Text("${entry.label} (${entry.masked}) sera retirée de ${provider.shortLabel}.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(provider, entry.id)
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
private fun ProviderCard(
    provider: Provider,
    keys: List<ApiKeyEntry>,
    probes: Map<String, KeyProbe>,
    onAdd: () -> Unit,
    onDelete: (ApiKeyEntry) -> Unit,
    onToggle: (Provider, String, Boolean) -> Unit,
    onTest: (Provider, ApiKeyEntry) -> Unit,
) {
    val accent = when (provider) {
        Provider.GEMINI -> MaterialTheme.colorScheme.primary
        Provider.GROQ -> MaterialTheme.colorScheme.tertiary
        Provider.CLOUDFLARE -> MaterialTheme.colorScheme.secondary
        Provider.APERTURE -> MaterialTheme.colorScheme.secondary
    }
    FlowCard(contentPadding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (provider) {
                        Provider.GEMINI -> Icons.Rounded.AutoAwesome
                        Provider.GROQ -> Icons.Rounded.Bolt
                        Provider.CLOUDFLARE -> Icons.Rounded.Cloud
                        Provider.APERTURE -> Icons.Rounded.Cloud
                    },
                    null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (provider) {
                        Provider.GEMINI -> "gemini-3.5-flash-lite · économique et rapide"
                        Provider.GROQ -> "whisper-large-v3-turbo · 0,04 $/heure"
                        Provider.CLOUDFLARE -> "whisper-large-v3-turbo · 10 000 neurons/jour offerts"
                        Provider.APERTURE -> "parakeet · sans clé"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = "${keys.size}/${com.botillon.flowscribe.data.ApiKeyStore.MAX_KEYS}",
                tint = accent,
            )
        }

        Spacer(Modifier.height(10.dp))

        if (keys.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAdd)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.VpnKey,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Ajouter une clé ${provider.shortLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.Add,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            keys.forEachIndexed { index, entry ->
                if (index > 0) DividerLine(Modifier.padding(vertical = 2.dp))
                KeyRow(
                    provider = provider,
                    entry = entry,
                    probe = probes[entry.id],
                    onDelete = { onDelete(entry) },
                    onToggle = { onToggle(provider, entry.id, it) },
                    onTest = { onTest(provider, entry) },
                )
            }
            if (keys.size < com.botillon.flowscribe.data.ApiKeyStore.MAX_KEYS) {
                TextButton(onClick = onAdd, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ajouter une clé")
                }
            }
        }

        val usable = keys.count { it.enabled && !it.isCoolingDown }
        if (keys.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val tokens = keys.sumOf { it.totalTokens }
            val today = keys.sumOf { it.requestsToday }
            val audio = keys.sumOf { it.totalAudioSeconds }
            val neurons = keys.sumOf { it.totalNeurons }
            Text(
                buildString {
                    append("$usable clé${if (usable > 1) "s" else ""} prête${if (usable > 1) "s" else ""} à répondre")
                    append(" · $today requête${if (today > 1) "s" else ""} aujourd'hui")
                    if (tokens > 0) append(" · $tokens tokens")
                    if (audio > 0) append(" · ").append(String.format(java.util.Locale.FRANCE, "%.0f s audio", audio))
                    if (neurons > 0) append(" · ").append(String.format(java.util.Locale.FRANCE, "%.1f neurons", neurons))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyRow(
    provider: Provider,
    entry: ApiKeyEntry,
    probe: KeyProbe?,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Key,
                null,
                tint = if (entry.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.masked,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.hasUsage) {
                    Text(
                        buildString {
                            append("${entry.requestsToday} req aujourd'hui")
                            if (entry.tokensToday > 0) append(" · ${entry.tokensToday} tokens")
                            else if (entry.totalTokens > 0) append(" · ${entry.totalTokens} tokens")
                            if (entry.audioSecondsToday > 0.0) {
                                append(" · ").append(
                                    String.format(
                                        java.util.Locale.FRANCE,
                                        "%.1f s audio",
                                        entry.audioSecondsToday,
                                    ),
                                )
                            }
                            if (entry.neuronsToday > 0.0) {
                                append(" · ").append(
                                    String.format(
                                        java.util.Locale.FRANCE,
                                        "%.2f neurons",
                                        entry.neuronsToday,
                                    ),
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onTest, enabled = !(probe?.running ?: false)) {
                if (probe?.running == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Science,
                        "Tester",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    "Supprimer",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val status = when {
            entry.isCoolingDown -> "Au repos · ${formatCooldown(entry.cooldownUntil)}"
            !entry.enabled -> "Désactivée"
            entry.lastError != null -> "Dernière erreur : ${entry.lastError}"
            entry.failCount == 0 -> null
            else -> "Échecs consécutifs : ${entry.failCount}"
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.isCoolingDown || entry.lastError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp, top = 2.dp),
            )
        }

        probe?.message?.let { message ->
            Row(
                Modifier.padding(start = 28.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (probe.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    null,
                    tint = if (probe.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (probe.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CloudflareGuideCard() {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    FlowCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Cloud,
                    null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Configurer Cloudflare Workers AI", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Gratuit : 10 000 neurons par jour. Aucune carte bancaire.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Masquer" else "Voir")
            }
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            GuideStep("1", "Crée un compte sur dash.cloudflare.com, puis ouvre « Workers AI » dans le menu AI.")
            GuideStep("2", "Clique sur « Get Account ID » : garde l'identifiant à 32 caractères, il n'est pas secret.")
            GuideStep("3", "Crée un token API avec la permission « Workers AI : Read/Edit » (ou « Account / Workers AI / Edit »).")
            GuideStep("4", "Dans JoynixScribe → Clés → Cloudflare, colle `accountId|token` (une barre verticale entre les deux).")
            GuideStep("5", "Clique sur la fiole pour tester. Le test lit le catalogue de modèles : aucun crédit audio consommé.")
            Spacer(Modifier.height(6.dp))
            Text(
                "Le token reste chiffré sur le téléphone. Ne le colle jamais dans un dépôt Git, une capture ou un message public.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    uriHandler.openUri("https://developers.cloudflare.com/workers-ai/get-started/rest-api/")
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("Ouvrir la documentation Cloudflare →")
            }
        }
    }
}

@Composable
private fun GuideStep(number: String, text: String) {
    Row(
        Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddKeyDialog(
    provider: Provider,
    existing: Int,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> String?,
) {
    var label by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clé ${provider.shortLabel}") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                Text(
                    when (provider) {
                        Provider.GEMINI ->
                            "Crée une clé dans Google AI Studio. Les modèles Flash Lite sont les plus économiques."
                        Provider.GROQ ->
                            "Crée une clé dans la console Groq. Whisper turbo coûte 0,04 $ par heure d'audio."
                        Provider.CLOUDFLARE ->
                            "Colle `accountId|token`. Le compte Cloudflare est gratuit : 10 000 neurons par jour, whisper-turbo inclus."
                        Provider.APERTURE ->
                            "Aucune clé requise. Aperture est utilisé comme secours sans compte."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { uriHandler.openUri(provider.docsUrl) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("Ouvrir ${provider.shortLabel} →")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(28) },
                    label = { Text("Nom (optionnel)") },
                    placeholder = { Text("Perso, travail…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        secret = it.trim()
                        error = null
                    },
                    label = { Text(if (provider == Provider.CLOUDFLARE) "accountId|token" else "Clé API") },
                    placeholder = { Text(provider.keyHint) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                null,
                            )
                        }
                    },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${existing + 1} clé sur ${com.botillon.flowscribe.data.ApiKeyStore.MAX_KEYS} maximum.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val message = onAdd(label, secret)
                    if (message != null) error = message
                },
                enabled = secret.length >= 8 &&
                    (provider != Provider.CLOUDFLARE || secret.contains('|')),
            ) { Text("Ajouter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
