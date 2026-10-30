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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.botillon.flowscribe.BuildConfig
import com.botillon.flowscribe.data.ApiKeyEntry
import com.botillon.flowscribe.data.AppSettings
import com.botillon.flowscribe.data.BubbleAccent
import com.botillon.flowscribe.data.BubbleGesture
import com.botillon.flowscribe.data.BubbleIcon
import com.botillon.flowscribe.data.InsertMode
import com.botillon.flowscribe.data.Provider
import com.botillon.flowscribe.data.ThemeMode
import com.botillon.flowscribe.ui.AppViewModel
import com.botillon.flowscribe.ui.PermKind
import com.botillon.flowscribe.ui.PermissionState
import com.botillon.flowscribe.ui.components.ChoiceChips
import com.botillon.flowscribe.ui.components.DividerLine
import com.botillon.flowscribe.ui.components.FlowCard
import com.botillon.flowscribe.ui.components.FlowSlider
import com.botillon.flowscribe.ui.components.ScreenHeader
import com.botillon.flowscribe.ui.components.SectionHeader
import com.botillon.flowscribe.ui.components.SettingRow
import com.botillon.flowscribe.ui.components.StatusPill
import com.botillon.flowscribe.ui.components.SwitchRow
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    settings: AppSettings,
    keys: Map<Provider, List<ApiKeyEntry>>,
    historyCount: Int,
    permissions: PermissionState,
    onPermission: (PermKind) -> Unit,
    onExport: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenKeys: () -> Unit,
    onOpenVendorSettings: () -> Unit,
) {
    var customModelFor by remember { mutableStateOf<Provider?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val geminiUsable = keys[Provider.GEMINI].orEmpty().count { it.enabled && !it.isCoolingDown }
    val groqUsable = keys[Provider.GROQ].orEmpty().count { it.enabled && !it.isCoolingDown }
    val cloudflareUsable = keys[Provider.CLOUDFLARE].orEmpty()
        .count { it.enabled && !it.isCoolingDown }
    val ecoReady = !settings.livePreview &&
        !settings.hedgeEnabled &&
        settings.geminiModel == Provider.GEMINI.defaultModel

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 14.dp,
            bottom = 30.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ScreenHeader(
                title = "Réglages",
                subtitle = "Vitesse, coût, confort. Tout est modifiable.",
            )
        }

        item {
            EcoCard(
                settings = settings,
                ecoReady = ecoReady,
                geminiUsable = geminiUsable,
                groqUsable = groqUsable,
                cloudflareUsable = cloudflareUsable,
                onApply = { vm.applyEcoPreset() },
            )
        }

        item {
            SettingsSection(title = "Moteur", subtitle = "Qui transcrit, avec quel modèle") {
                Text(
                    "Fournisseur principal",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ChoiceChips(
                    options = Provider.entries.filter { it.requiresKey },
                    selected = settings.primary,
                    label = { it.shortLabel },
                    onSelect = { vm.setPrimary(it) },
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                ModelDropdown(
                    provider = Provider.GEMINI,
                    current = settings.geminiModel,
                    onSelect = { vm.setGeminiModel(it) },
                    onCustom = { customModelFor = Provider.GEMINI },
                )
                ModelDropdown(
                    provider = Provider.GROQ,
                    current = settings.groqModel,
                    onSelect = { vm.setGroqModel(it) },
                    onCustom = { customModelFor = Provider.GROQ },
                )
                ModelDropdown(
                    provider = Provider.CLOUDFLARE,
                    current = settings.cloudflareModel,
                    onSelect = { vm.setCloudflareModel(it) },
                    onCustom = { customModelFor = Provider.CLOUDFLARE },
                )
                DividerLine()
                SwitchRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Reformatage des styles",
                    subtitle = "Applique le style actif quand Groq ou Cloudflare transcrit (petit modèle, coût minime)",
                    checked = settings.groqFormatting,
                    onChange = { vm.setGroqFormatting(it) },
                )
                DividerLine()
                DropdownField(
                    icon = Icons.Rounded.Language,
                    title = "Langue parlée",
                    subtitle = "Améliore la précision et réduit les erreurs",
                    value = languageLabel(settings.language),
                    options = LANGUAGES.map { it.second },
                    onSelect = { label ->
                        vm.setLanguage(LANGUAGES.first { it.second == label }.first)
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Astuce coût : gemini-3.5-flash-lite et whisper-large-v3-turbo offrent le meilleur rapport qualité/prix pour de la dictée courte.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(
                title = "Latence & consommation",
                subtitle = "Un seul appel par dictée par défaut",
            ) {
                SwitchRow(
                    icon = Icons.Rounded.Cloud,
                    title = "Repli automatique",
                    subtitle = "Si le principal échoue, l'autre fournisseur prend le relais",
                    checked = settings.fallbackEnabled,
                    onChange = { vm.setFallbackEnabled(it) },
                )
                if (settings.fallbackEnabled) {
                    FlowSlider(
                        title = "Délai avant repli",
                        valueLabel = formatSeconds(settings.fallbackTimeoutMs),
                        value = settings.fallbackTimeoutMs.toFloat(),
                        valueRange = 1000f..8000f,
                        steps = 6,
                        onChange = { vm.setFallbackTimeout(it.toLong()) },
                    )
                }
                DividerLine()
                SwitchRow(
                    icon = Icons.Rounded.Speed,
                    title = "Course « hedged »",
                    subtitle = "Lance les deux modèles en parallèle. Plus rapide, mais double la consommation.",
                    checked = settings.hedgeEnabled,
                    onChange = { vm.setHedgeEnabled(it) },
                )
                if (settings.hedgeEnabled) {
                    FlowSlider(
                        title = "Départ du second modèle",
                        valueLabel = "${settings.hedgeDelayMs} ms",
                        value = settings.hedgeDelayMs.toFloat(),
                        valueRange = 250f..2000f,
                        steps = 6,
                        onChange = { vm.setHedgeDelay(it.toLong()) },
                    )
                }
                DividerLine()
                DividerLine()
                SwitchRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Mode streaming (live)",
                    subtitle = "Transcrit au fil de la parole : le texte apparaît pendant que tu parles, sans attendre la fin",
                    checked = settings.streamingMode,
                    onChange = { vm.setStreamingMode(it) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Segmentation automatique sur les silences. Coût un peu plus élevé (plusieurs appels), mais retour immédiat. En cas de micro de mauvaise qualité, les segments non vocaux sont ignorés.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DividerLine()
                SwitchRow(
                    icon = Icons.Rounded.Cloud,
                    title = "Secours Aperture",
                    subtitle = "Sans clé : prend le relais si les fournisseurs à clé échouent",
                    checked = settings.apertureFallbackEnabled,
                    onChange = { vm.setApertureFallbackEnabled(it) },
                )
                if (settings.apertureFallbackEnabled) {
                    SwitchRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = "Aperture en streaming (improvisé)",
                        subtitle = "Chaque segment est envoyé à Aperture quand les autres échouent. Découpe locale sous 1 Mo.",
                        checked = settings.apertureStreamingEnabled,
                        onChange = { vm.setApertureStreamingEnabled(it) },
                    )
                }
                Text(
                    "Aperture Sciences transcrit sans clé. L'API limite le fichier à environ 1 Mo : l'app découpe l'audio en tranches de 20 s et recolle le texte. En mode normal, le secours part après l'arrêt ; en streaming, il s'applique aux segments.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DividerLine()
                FlowSlider(
                    title = "Durée maximale d'une dictée",
                    valueLabel = "${settings.maxDurationSec} s",
                    value = settings.maxDurationSec.toFloat(),
                    valueRange = 30f..300f,
                    steps = 8,
                    onChange = { vm.setMaxDuration(it.roundToInt()) },
                )
                Text(
                    "Le silence en début et fin de dictée est coupé avant l'envoi. Un enregistrement plus court = moins de tokens et une réponse plus rapide.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(title = "Texte & insertion", subtitle = "Ponctuation, vocabulaire, champ actif") {
                SwitchRow(
                    icon = Icons.Rounded.TextFields,
                    title = "Ponctuation automatique",
                    subtitle = "Virgules, points, questions selon l'intonation",
                    checked = settings.punctuation,
                    onChange = { vm.setPunctuation(it) },
                )
                SwitchRow(
                    icon = Icons.Rounded.KeyboardVoice,
                    title = "Commandes parlées",
                    subtitle = "« virgule », « à la ligne », « point d'interrogation »",
                    checked = settings.spokenCommands,
                    onChange = { vm.setSpokenCommands(it) },
                )
                SwitchRow(
                    icon = Icons.Rounded.Language,
                    title = "Typographie française",
                    subtitle = "Espaces insécables, guillemets « », apostrophes",
                    checked = settings.frenchTypography,
                    onChange = { vm.setFrenchTypography(it) },
                )
                SwitchRow(
                    icon = Icons.Rounded.TextFields,
                    title = "Majuscule automatique",
                    subtitle = "Début de phrase et noms propres du dictionnaire",
                    checked = settings.autoCapitalize,
                    onChange = { vm.setAutoCapitalize(it) },
                )
                SwitchRow(
                    icon = Icons.Rounded.TextFields,
                    title = "Espace automatique",
                    subtitle = "Ajoute l'espace manquant avant le texte inséré",
                    checked = settings.autoSpace,
                    onChange = { vm.setAutoSpace(it) },
                )
                DividerLine()
                Text(
                    "Méthode d'insertion",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                ChoiceChips(
                    options = InsertMode.entries,
                    selected = settings.insertMode,
                    label = { it.label },
                    onSelect = { vm.setInsertMode(it) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when (settings.insertMode) {
                        InsertMode.AUTO -> "Écrit directement dans le champ, puis colle si l'app refuse."
                        InsertMode.SET_TEXT -> "Remplace le contenu du champ actif. Très fiable, mais écrase le texte existant."
                        InsertMode.PASTE -> "Colle au curseur via le presse-papiers. Compatible avec plus d'apps."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(
                title = "Application active",
                subtitle = "Des styles qui s'adaptent au contexte",
            ) {
                SwitchRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Envoyer le contexte d'app",
                    subtitle = "Transmet le nom et le package de l'app active (ex. Discord, Gmail) au modèle",
                    checked = settings.sendAppContext,
                    onChange = { vm.setSendAppContext(it) },
                )
                DividerLine()
                SwitchRow(
                    icon = Icons.Rounded.KeyboardVoice,
                    title = "Styles automatiques par app",
                    subtitle = "Utilise le style dont le package correspond à l'app active",
                    checked = settings.appAwarePrompts,
                    onChange = { vm.setAppAwarePrompts(it) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Exemple : un style « Discord » avec le package com.discord sera appliqué dès que tu dictes dans Discord. Configure les packages depuis l'onglet Styles.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                FlowSlider(
                    title = "Plafond quotidien par clé",
                    valueLabel = if (settings.dailyKeyRequestLimit <= 0) "Illimité"
                    else "${settings.dailyKeyRequestLimit} requêtes",
                    value = settings.dailyKeyRequestLimit.toFloat(),
                    valueRange = 0f..200f,
                    steps = 0,
                    onChange = { vm.setDailyKeyRequestLimit(it.roundToInt()) },
                )
                Text(
                    "Garde-fou local : une clé qui atteint ce nombre de requêtes dans la journée est ignorée jusqu'au lendemain. 0 = pas de plafond. Les quotas réels restent ceux du compte chez le fournisseur.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(title = "Bulle", subtitle = "Présence discrète, gestes sûrs") {
                FlowSlider(
                    title = "Taille",
                    valueLabel = "${settings.bubbleSizeDp} dp",
                    value = settings.bubbleSizeDp.toFloat(),
                    valueRange = 46f..80f,
                    steps = 16,
                    onChange = { vm.setBubbleSize(it.roundToInt()) },
                )
                FlowSlider(
                    title = "Opacité",
                    valueLabel = "${(settings.bubbleOpacity * 100).roundToInt()} %",
                    value = settings.bubbleOpacity,
                    valueRange = 0.55f..1f,
                    steps = 8,
                    onChange = { vm.setBubbleOpacity(it) },
                )
                DividerLine()
                Text(
                    "Couleur de la bulle",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                ChoiceChips(
                    options = BubbleAccent.entries,
                    selected = settings.bubbleAccent,
                    label = { it.label },
                    onSelect = { vm.setBubbleAccent(it) },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Icône au repos",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ChoiceChips(
                    options = BubbleIcon.entries,
                    selected = settings.bubbleIcon,
                    label = { it.label },
                    onSelect = { vm.setBubbleIcon(it) },
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Text(
                    "Geste de dictée",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                ChoiceChips(
                    options = BubbleGesture.entries,
                    selected = settings.bubbleGesture,
                    label = { it.label },
                    onSelect = { vm.setBubbleGesture(it) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    settings.bubbleGesture.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                SwitchRow(
                    icon = Icons.Rounded.Tune,
                    title = "Retour haptique",
                    subtitle = "Vibration courte au départ et à l'arrivée",
                    checked = settings.haptics,
                    onChange = { vm.setHaptics(it) },
                )
                DividerLine()
                Text(
                    "Thème",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                ChoiceChips(
                    options = ThemeMode.entries,
                    selected = settings.theme,
                    label = { it.label },
                    onSelect = { vm.setTheme(it) },
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    icon = Icons.Rounded.Palette,
                    title = "Couleurs Material You",
                    subtitle = "Suit le fond d'écran du téléphone",
                    checked = settings.dynamicColor,
                    onChange = { vm.setDynamicColor(it) },
                )
            }
        }

        item {
            SettingsSection(title = "Autorisations", subtitle = "Nécessaires pour dicter partout") {
                PermissionSettingRow(
                    icon = Icons.Rounded.Mic,
                    title = "Microphone",
                    granted = permissions.microphone,
                    onClick = { onPermission(PermKind.MICROPHONE) },
                )
                PermissionSettingRow(
                    icon = Icons.Rounded.WebAsset,
                    title = "Fenêtres flottantes",
                    granted = permissions.overlay,
                    onClick = { onPermission(PermKind.OVERLAY) },
                )
                PermissionSettingRow(
                    icon = Icons.Rounded.Accessibility,
                    title = "Accessibilité (insertion)",
                    granted = permissions.accessibility,
                    onClick = { onPermission(PermKind.ACCESSIBILITY) },
                )
                PermissionSettingRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "Notifications",
                    granted = permissions.notifications,
                    onClick = { onPermission(PermKind.NOTIFICATIONS) },
                )
                PermissionSettingRow(
                    icon = Icons.Rounded.BatterySaver,
                    title = "Batterie sans restriction",
                    granted = permissions.battery,
                    onClick = { onPermission(PermKind.BATTERY) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sur HyperOS : autorise aussi « Afficher en fenêtre surgissante », le démarrage automatique et retire la restriction de batterie, sinon le système coupe la bulle.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Text(
                    "Compatibilité (${com.botillon.flowscribe.device.DeviceCompat.manufacturer()})",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                )
                val context = LocalContext.current
                com.botillon.flowscribe.device.DeviceCompat.guidanceLines(context).forEach { line ->
                    Text(
                        "• $line",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenVendorSettings) {
                        Text("Ouvrir les réglages fabricant")
                    }
                    TextButton(onClick = { onPermission(PermKind.BATTERY) }) {
                        Text("Batterie")
                    }
                }
            }
        }

        item {
            SettingsSection(title = "Données", subtitle = "Tout reste local, sauf les requêtes IA") {
                SettingRow(
                    icon = Icons.Rounded.VpnKey,
                    title = "Clés API",
                    subtitle = "${keys.values.sumOf { it.size }} clé${if (keys.values.sumOf { it.size } > 1) "s" else ""} · chiffrées par le Keystore",
                    onClick = onOpenKeys,
                    trailing = {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                DividerLine()
                SettingRow(
                    icon = Icons.Rounded.History,
                    title = "Historique",
                    subtitle = "$historyCount dictée${if (historyCount > 1) "s" else ""} conservée${if (historyCount > 1) "s" else ""}",
                    trailing = {
                        TextButton(onClick = { confirmClear = true }, enabled = historyCount > 0) {
                            Text("Effacer")
                        }
                    },
                )
                DividerLine()
                SettingRow(
                    icon = Icons.Rounded.Download,
                    title = "Exporter la configuration",
                    subtitle = "Styles et dictionnaire en JSON (sans les clés)",
                    onClick = onExport,
                    trailing = {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Les clés API ne sont jamais incluses dans l'export ni dans une sauvegarde.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(title = "À propos", subtitle = null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(16.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("JoynixScribe", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Version ${BuildConfig.VERSION_NAME} · open source",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        text = "local d'abord",
                        tint = MaterialTheme.colorScheme.secondary,
                        icon = Icons.Rounded.CheckCircle,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "JoynixScribe enregistre uniquement entre le tap de départ et le tap d'arrêt. L'audio est envoyé au fournisseur choisi (Gemini, Groq ou Cloudflare) pour transcription, puis oublié. Aucune analytics, aucun serveur intermédiaire. Le contenu de l'écran n'est jamais lu ni transmis : seuls le nom et le package de l'app active peuvent l'être, et tu peux couper ça ci-dessus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Text(
                "JoynixScribe ne remplace pas le clavier : il insère le texte dans le champ actif via le service d'accessibilité. Certaines apps (banques, champs sécurisés) refusent l'insertion ; le texte est alors copié.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    customModelFor?.let { provider ->
        CustomModelDialog(
            provider = provider,
            current = when (provider) {
                Provider.GEMINI -> settings.geminiModel
                Provider.GROQ -> settings.groqModel
                Provider.CLOUDFLARE -> settings.cloudflareModel
                Provider.APERTURE -> settings.cloudflareModel
            },
            onDismiss = { customModelFor = null },
            onSave = {
                when (provider) {
                    Provider.GEMINI -> vm.setGeminiModel(it)
                    Provider.GROQ -> vm.setGroqModel(it)
                    Provider.CLOUDFLARE -> vm.setCloudflareModel(it)
                    Provider.APERTURE -> Unit
                }
                customModelFor = null
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer l'historique ?") },
            text = { Text("Les $historyCount dictées enregistrées seront supprimées. Les styles et le dictionnaire sont conservés.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    },
                ) { Text("Effacer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun EcoCard(
    settings: AppSettings,
    ecoReady: Boolean,
    geminiUsable: Int,
    groqUsable: Int,
    cloudflareUsable: Int,
    onApply: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Bolt,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Consommation maîtrisée",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (ecoReady) {
                    StatusPill(
                        text = "actif",
                        tint = MaterialTheme.colorScheme.secondary,
                        icon = Icons.Rounded.CheckCircle,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            EcoLine("Un seul appel IA par dictée (pas de hedged, pas d'aperçu).")
            EcoLine("Modèle économique par défaut : gemini-3.5-flash-lite.")
            EcoLine("Silence coupé, sortie plafonnée selon la durée réelle.")
            EcoLine("$geminiUsable clé Gemini · $groqUsable clé Groq · $cloudflareUsable clé Cloudflare disponibles.")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ecoReady) "Réglage déjà optimal." else "Applique les trois réglages d'un coup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onApply) { Text("Optimiser") }
            }
        }
    }
}

@Composable
private fun EcoLine(text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    Column {
        SectionHeader(title = title, subtitle = subtitle)
        Spacer(Modifier.height(8.dp))
        FlowCard(contentPadding = 16) { content() }
    }
}

@Composable
private fun ModelDropdown(
    provider: Provider,
    current: String,
    onSelect: (String) -> Unit,
    onCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val icon = when (provider) {
        Provider.GEMINI -> Icons.Rounded.AutoAwesome
        Provider.GROQ -> Icons.Rounded.Bolt
        Provider.CLOUDFLARE -> Icons.Rounded.Cloud
        Provider.APERTURE -> Icons.Rounded.Cloud
    }
    val tint = when (provider) {
        Provider.GEMINI -> MaterialTheme.colorScheme.primary
        Provider.GROQ -> MaterialTheme.colorScheme.tertiary
        Provider.CLOUDFLARE -> MaterialTheme.colorScheme.secondary
        Provider.APERTURE -> MaterialTheme.colorScheme.secondary
    }
    Box {
        SettingRow(
            icon = icon,
            title = "Modèle ${provider.shortLabel}",
            subtitle = current,
            tint = tint,
            onClick = { expanded = true },
            trailing = {
                Icon(
                    Icons.Rounded.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            provider.models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (model == current) Icons.Rounded.CheckCircle else Icons.Rounded.AutoAwesome,
                                null,
                                tint = if (model == current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(model, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Modèle personnalisé…") },
                onClick = {
                    expanded = false
                    onCustom()
                },
            )
        }
    }
}

@Composable
private fun DropdownField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            onClick = { expanded = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    SettingRow(
        icon = icon,
        title = title,
        subtitle = if (granted) "Autorisé" else "À activer",
        tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
        onClick = onClick,
        trailing = {
            if (granted) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                TextButton(onClick = onClick) { Text("Activer") }
            }
        },
    )
}

@Composable
private fun CustomModelDialog(
    provider: Provider,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(if (current in provider.models) "" else current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modèle ${provider.shortLabel}") },
        text = {
            Column {
                Text(
                    "Indique l'identifiant exact du modèle. Il doit accepter l'audio en entrée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    label = { Text("Identifiant") },
                    placeholder = {
                        Text(
                            when (provider) {
                                Provider.GEMINI -> "gemini-3.5-flash-lite"
                                Provider.GROQ -> "whisper-large-v3-turbo"
                                Provider.CLOUDFLARE -> "@cf/openai/whisper-large-v3-turbo"
                                Provider.APERTURE -> "parakeet"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value) },
                enabled = value.isNotBlank(),
            ) { Text("Utiliser") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

private val LANGUAGES = listOf(
    "auto" to "Automatique",
    "fr" to "Français",
    "en" to "Anglais",
    "es" to "Espagnol",
    "de" to "Allemand",
    "it" to "Italien",
    "pt" to "Portugais",
    "nl" to "Néerlandais",
)

private fun languageLabel(code: String): String =
    LANGUAGES.firstOrNull { it.first == code }?.second ?: "Automatique"

private fun formatSeconds(ms: Long): String =
    if (ms % 1000L == 0L) "${ms / 1000} s" else String.format(java.util.Locale.FRANCE, "%.1f s", ms / 1000.0)

private fun Color.unused(): Color = this
