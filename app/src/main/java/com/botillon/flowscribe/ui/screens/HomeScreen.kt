package com.botillon.flowscribe.ui.screens

import com.botillon.flowscribe.BuildConfig

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.botillon.flowscribe.data.AppSettings
import com.botillon.flowscribe.data.DictionaryEntry
import com.botillon.flowscribe.data.HistoryEntry
import com.botillon.flowscribe.data.PromptDefaults
import com.botillon.flowscribe.data.PromptPreset
import com.botillon.flowscribe.ui.PermKind
import com.botillon.flowscribe.ui.PermissionState
import com.botillon.flowscribe.ui.components.DividerLine
import com.botillon.flowscribe.ui.components.EmptyState
import com.botillon.flowscribe.ui.components.FlowCard
import com.botillon.flowscribe.ui.components.SectionHeader
import com.botillon.flowscribe.ui.components.StatusPill
import com.botillon.flowscribe.ui.components.formatLatency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    prompts: List<PromptPreset>,
    history: List<HistoryEntry>,
    dictionary: List<DictionaryEntry>,
    permissions: PermissionState,
    bubbleRunning: Boolean,
    hasAnyKey: Boolean,
    onToggleBubble: () -> Unit,
    onPermission: (PermKind) -> Unit,
    onSelectPrompt: (String) -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenKeys: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val activePrompt = prompts.firstOrNull { it.id == settings.activePromptId }
        ?: prompts.firstOrNull()
    var showHistory by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { TopBar() }

        item {
            HeroCard(
                settings = settings,
                activePrompt = activePrompt,
                bubbleRunning = bubbleRunning,
                lastLatency = history.firstOrNull()?.latencyMs ?: 0L,
                onToggleBubble = onToggleBubble,
            )
        }

        if (!hasAnyKey) {
            item { MissingKeysCard(onOpenKeys = onOpenKeys) }
        }

        item {
            PermissionCard(
                permissions = permissions,
                onPermission = onPermission,
            )
        }

        item {
            SectionHeader(
                title = "Style de dictée",
                subtitle = "Ce que l'IA fait de ta voix",
                trailing = {
                    TextButton(onClick = onOpenPrompts) {
                        Text("Gérer", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(prompts, key = { it.id }) { prompt ->
                    FilterChip(
                        selected = prompt.id == activePrompt?.id,
                        onClick = { onSelectPrompt(prompt.id) },
                        label = { Text(prompt.name, maxLines = 1) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = prompt.id == activePrompt?.id,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                }
            }
        }

        item {
            FlowCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Layers,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            activePrompt?.name ?: "Dictée fidèle",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            activePrompt?.description ?: "Transcription exacte, ponctuation comprise.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = onOpenPrompts),
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Dernières dictées",
                subtitle = if (dictionary.isEmpty()) {
                    "Le vocabulaire personnel s'applique automatiquement"
                } else {
                    "${dictionary.count { it.enabled }} terme${if (dictionary.count { it.enabled } > 1) "s" else ""} personnalisé${if (dictionary.count { it.enabled } > 1) "s" else ""} actif${if (dictionary.count { it.enabled } > 1) "s" else ""}"
                },
                trailing = if (history.isNotEmpty()) {
                    {
                        TextButton(onClick = { showHistory = true }) {
                            Text("Tout voir", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else null,
            )
        }

        if (history.isEmpty()) {
            item {
                FlowCard {
                    EmptyState(
                        icon = Icons.Rounded.History,
                        title = "Rien encore dicté",
                        body = "Lance la bulle, parle, appuie sur stop. Le texte apparaît dans le champ actif.",
                    )
                }
            }
        } else {
            items(history.take(3), key = { it.id }) { entry ->
                HistoryRow(entry = entry, onCopy = { onCopy(entry.text) })
            }
        }

        item {
            Text(
                "Les clés et l'audio ne quittent l'appareil que pour rejoindre Gemini, Groq ou Cloudflare. Rien d'autre n'est envoyé.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }

    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 18.dp),
            ) {
                Text("Historique", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${history.size} dictée${if (history.size > 1) "s" else ""} · stockées uniquement sur cet appareil",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 30.dp),
                ) {
                    items(history, key = { it.id }) { entry ->
                        HistoryRow(entry = entry, onCopy = { onCopy(entry.text) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF9E86FF), Color(0xFF6B4CF0), Color(0xFF3B247F)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "JoynixScribe",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
            )
            Text(
                "Dictée flottante, partout",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(
            text = "v" + BuildConfig.VERSION_NAME,
            tint = MaterialTheme.colorScheme.secondary,
            icon = Icons.Rounded.Bolt,
        )
    }
}

@Composable
private fun HeroCard(
    settings: AppSettings,
    activePrompt: PromptPreset?,
    bubbleRunning: Boolean,
    lastLatency: Long,
    onToggleBubble: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF241653), Color(0xFF4B2FBF), Color(0xFF7C5CFF)),
                ),
            ),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                color = Color(0xFF7DE2D1).copy(alpha = 0.16f),
                radius = size.width * 0.42f,
                center = Offset(size.width * 0.92f, size.height * 0.08f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = size.width * 0.34f,
                center = Offset(size.width * 0.05f, size.height * 0.94f),
            )
            val bars = 16
            val barWidth = size.width * 0.012f
            val gap = size.width * 0.017f
            var x = size.width - (bars * (barWidth + gap)) - 18.dp.toPx()
            repeat(bars) { index ->
                val h = size.height * (0.06f + 0.05f * ((index * 7) % 5) / 4f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.20f),
                    topLeft = Offset(x, size.height - h - 22.dp.toPx()),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
                x += barWidth + gap
            }
        }

        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (bubbleRunning) Color(0xFF7DE2D1) else Color.White.copy(alpha = 0.55f)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (bubbleRunning) "Bulle active" else "Bulle en veille",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "0,9 s visé",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Ta voix,\nau curseur.",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                lineHeight = 38.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (bubbleRunning) {
                    "Touche la bulle pour parler. Le texte se pose là où tu écris."
                } else {
                    "Une bulle discrète sur tous tes écrans. Aucun clavier à remplacer."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f),
            )

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroStat(
                    label = "DERNIÈRE",
                    value = if (lastLatency > 0) formatLatency(lastLatency) else "—",
                )
                HeroSeparator()
                HeroStat(
                    label = "MOTEUR",
                    value = settings.primary.shortLabel,
                )
                HeroSeparator()
                HeroStat(
                    label = "STYLE",
                    value = activePrompt?.name ?: "Fidèle",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                contentColor = Color(0xFF2A1B6B),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clickable(onClick = onToggleBubble),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (bubbleRunning) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (bubbleRunning) "Arrêter la bulle" else "Lancer la bulle",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroSeparator() {
    Box(
        Modifier
            .padding(horizontal = 12.dp)
            .width(1.dp)
            .height(26.dp)
            .background(Color.White.copy(alpha = 0.16f)),
    )
}

@Composable
private fun MissingKeysCard(onOpenKeys: () -> Unit) {
    FlowCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.WarningAmber,
                null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Aucune clé API", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ajoute une clé Gemini, Groq ou Cloudflare pour activer la dictée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenKeys) { Text("Ajouter") }
        }
    }
}

@Composable
private fun PermissionCard(
    permissions: PermissionState,
    onPermission: (PermKind) -> Unit,
) {
    FlowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Configuration", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${permissions.configured} sur 3 essentiels",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (permissions.configured == 3) {
                StatusPill(
                    text = "Prêt",
                    tint = MaterialTheme.colorScheme.secondary,
                    icon = Icons.Rounded.CheckCircle,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { permissions.configured / 3f },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Spacer(Modifier.height(8.dp))

        PermissionRow(
            icon = Icons.Rounded.Mic,
            title = "Microphone",
            description = "Capte ta voix pendant la dictée",
            granted = permissions.microphone,
            onClick = { onPermission(PermKind.MICROPHONE) },
        )
        PermissionRow(
            icon = Icons.Rounded.WebAsset,
            title = "Fenêtres flottantes",
            description = "Affiche la bulle par-dessus les apps",
            granted = permissions.overlay,
            onClick = { onPermission(PermKind.OVERLAY) },
        )
        PermissionRow(
            icon = Icons.Rounded.Accessibility,
            title = "Accessibilité",
            description = "Insère le texte au curseur, sans lire le contenu",
            granted = permissions.accessibility,
            onClick = { onPermission(PermKind.ACCESSIBILITY) },
        )
        PermissionRow(
            icon = Icons.Rounded.BatterySaver,
            title = "Batterie sans restriction",
            description = "Évite que HyperOS coupe la bulle",
            granted = permissions.battery,
            onClick = { onPermission(PermKind.BATTERY) },
            essential = false,
        )
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit,
    essential: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (!essential) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "optionnel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Icon(
                Icons.Rounded.CheckCircle,
                null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            TextButton(onClick = onClick) {
                Text("Activer", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onCopy: () -> Unit) {
    val relative = remember(entry.at) {
        DateUtils.getRelativeTimeSpanString(
            entry.at,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    FlowCard(contentPadding = 15) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.text.replace("\n", " ").trim(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(entry.provider.replaceFirstChar { it.uppercase() })
                        if (entry.latencyMs > 0) append(" · ").append(formatLatency(entry.latencyMs))
                        append(" · ").append(relative)
                        if (!entry.injected) append(" · copié")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    "Copier",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
