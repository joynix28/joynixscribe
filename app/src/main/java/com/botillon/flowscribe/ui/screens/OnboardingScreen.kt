package com.botillon.flowscribe.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.botillon.flowscribe.data.ApiKeyEntry
import com.botillon.flowscribe.ui.PermKind
import com.botillon.flowscribe.ui.PermissionState
import com.botillon.flowscribe.ui.components.DividerLine
import com.botillon.flowscribe.ui.components.FlowCard
import com.botillon.flowscribe.ui.components.GradientAction
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    permissions: PermissionState,
    hasGeminiKey: Boolean,
    onPermission: (PermKind) -> Unit,
    onAddGeminiKey: (String) -> Result<ApiKeyEntry>,
    onFinish: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF7C5CFF).copy(alpha = 0.30f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.08f),
                    radius = size.width * 0.85f,
                ),
                radius = size.width * 0.85f,
                center = Offset(size.width * 0.85f, size.height * 0.08f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF7DE2D1).copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.05f, size.height * 0.55f),
                    radius = size.width * 0.7f,
                ),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.05f, size.height * 0.55f),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            BrandRow()
            Spacer(Modifier.height(22.dp))
            StepDots(step = step, total = 3)
            Spacer(Modifier.height(18.dp))

            Crossfade(
                targetState = step,
                animationSpec = tween(260),
                label = "onboarding",
                modifier = Modifier.weight(1f),
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> PermissionStep(permissions = permissions, onPermission = onPermission)
                        else -> KeyStep(hasGeminiKey = hasGeminiKey, onAddGeminiKey = onAddGeminiKey)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            GradientAction(
                text = when (step) {
                    0 -> "Commencer"
                    1 -> "Continuer"
                    else -> "Terminer"
                },
                icon = if (step == 2) Icons.Rounded.Check else Icons.Rounded.ArrowForward,
                onClick = {
                    if (step < 2) {
                        step += 1
                    } else {
                        onFinish()
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step -= 1 }) { Text("Retour") }
                }
                if (step == 2 && !hasGeminiKey) {
                    TextButton(
                        onClick = {
                            scope.launch { onFinish() }
                        },
                    ) { Text("Plus tard") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BrandRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.GraphicEq,
                null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                "FlowScribe",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "La dictée qui va partout",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepDots(step: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (index == step) 26.dp else 10.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Text(
        "Ta voix,\nécrite partout.",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(14.dp))
    Text(
        "Une bulle flottante, un tap pour parler, un tap pour arrêter. Le texte s'insère dans le champ actif de n'importe quelle app, sans changer de clavier.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(22.dp))
    FeatureLine(
        icon = Icons.Rounded.Bolt,
        title = "Rapide",
        body = "Gemini Flash Lite en principal, Whisper turbo en secours. Un seul appel par dictée.",
    )
    FeatureLine(
        icon = Icons.Rounded.AutoAwesome,
        title = "À ton style",
        body = "Ponctuation parlée, styles personnalisés et dictionnaire de tes mots.",
    )
    FeatureLine(
        icon = Icons.Rounded.Lock,
        title = "Privé",
        body = "Clés chiffrées par le Keystore, historique local, zéro analytics.",
    )
    Spacer(Modifier.height(18.dp))
    FlowCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.Mic,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "L'audio n'est enregistré qu'entre le départ et l'arrêt, puis envoyé uniquement à Gemini ou Groq pour la transcription.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeatureLine(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionStep(
    permissions: PermissionState,
    onPermission: (PermKind) -> Unit,
) {
    Text(
        "Trois autorisations,\ndeux conforts.",
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Le micro et les fenêtres flottantes sont indispensables. L'accessibilité permet d'écrire dans les autres apps.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(18.dp))

    PermissionTile(
        icon = Icons.Rounded.Mic,
        title = "Microphone",
        body = "Enregistrer ta dictée, et rien d'autre.",
        granted = permissions.microphone,
        required = true,
        onClick = { onPermission(PermKind.MICROPHONE) },
    )
    Spacer(Modifier.height(10.dp))
    PermissionTile(
        icon = Icons.Rounded.WebAsset,
        title = "Fenêtres flottantes",
        body = "Afficher la bulle par-dessus les autres apps.",
        granted = permissions.overlay,
        required = true,
        onClick = { onPermission(PermKind.OVERLAY) },
    )
    Spacer(Modifier.height(10.dp))
    PermissionTile(
        icon = Icons.Rounded.Accessibility,
        title = "Accessibilité",
        body = "Insérer le texte dans le champ actif.",
        granted = permissions.accessibility,
        required = true,
        onClick = { onPermission(PermKind.ACCESSIBILITY) },
    )
    Spacer(Modifier.height(10.dp))
    PermissionTile(
        icon = Icons.Rounded.NotificationsActive,
        title = "Notifications",
        body = "Afficher l'état de la dictée pendant l'écoute.",
        granted = permissions.notifications,
        required = false,
        onClick = { onPermission(PermKind.NOTIFICATIONS) },
    )
    Spacer(Modifier.height(10.dp))
    PermissionTile(
        icon = Icons.Rounded.BatterySaver,
        title = "Batterie sans restriction",
        body = "Empêcher HyperOS de couper la bulle.",
        granted = permissions.battery,
        required = false,
        onClick = { onPermission(PermKind.BATTERY) },
    )

    Spacer(Modifier.height(16.dp))
    FlowCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.Lock,
                null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Aucune autre permission n'est demandée. La bulle se lance depuis l'app, jamais au démarrage du téléphone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionTile(
    icon: ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    required: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (granted) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (granted) MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    null,
                    tint = if (granted) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (!required) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "optionnel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    "Autorisé",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(21.dp),
                )
            } else {
                Text(
                    "Activer",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun KeyStep(
    hasGeminiKey: Boolean,
    onAddGeminiKey: (String) -> Result<ApiKeyEntry>,
) {
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(
        "Une clé, et c'est parti.",
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "FlowScribe utilise ta propre clé Gemini. Elle est stockée chiffrée sur cet appareil et n'est jamais partagée.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    if (hasGeminiKey) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Clé Gemini active",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Tu peux ajouter jusqu'à 9 clés plus tard dans l'onglet Clés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        FlowCard {
            OutlinedTextField(
                value = secret,
                onValueChange = {
                    secret = it.trim()
                    error = null
                },
                label = { Text("Clé Gemini") },
                placeholder = { Text("AIza…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = error != null,
                supportingText = {
                    Text(error ?: "Créée gratuitement dans Google AI Studio.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val result = onAddGeminiKey(secret)
                        saving = false
                        result.fold(
                            onSuccess = { secret = "" },
                            onFailure = { error = it.message ?: "Clé refusée." },
                        )
                    }
                },
                enabled = secret.length >= 8 && !saving,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Ajouter la clé")
                }
            }
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Rounded.Bolt,
                    null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "Tu peux aussi tout configurer plus tard. Sans clé, la bulle ne peut pas transcrire.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "Groq reste optionnel : ajoute une clé Whisper dans l'onglet Clés pour un secours ultra-rapide.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        textAlign = TextAlign.Start,
    )
}
