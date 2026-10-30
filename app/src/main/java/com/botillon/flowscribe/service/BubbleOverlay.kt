package com.botillon.flowscribe.service

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import com.botillon.flowscribe.data.AppSettings
import com.botillon.flowscribe.data.BubbleAccent
import com.botillon.flowscribe.data.BubbleGesture
import com.botillon.flowscribe.data.BubbleIcon
import com.botillon.flowscribe.data.PromptPreset
import com.botillon.flowscribe.ui.theme.JoynixScribeTheme
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class BubblePhase { IDLE, RECORDING, PROCESSING, RESULT, ERROR, MENU }

data class OverlayState(
    val phase: BubblePhase = BubblePhase.IDLE,
    val elapsedMs: Long = 0L,
    val level: Float = 0f,
    val amplitudes: List<Float> = emptyList(),
    val partialText: String = "",
    val resultText: String = "",
    val providerLabel: String = "",
    val latencyMs: Long = 0L,
    val model: String = "",
    val errorText: String = "",
    val insertionNote: String = "",
    val copied: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val prompts: List<PromptPreset> = emptyList(),
    val activePromptId: String = "",
    val accessibilityConnected: Boolean = true,
    val usageTokens: Long = 0L,
    val usageAudioSeconds: Double = 0.0,
    val usageNeurons: Double = 0.0,
    val appName: String = "",
    val appPackage: String = "",
    val promptName: String = "",
)

data class OverlayActions(
    val onTap: () -> Unit,
    val onLongPress: () -> Unit,
    val onPressStart: () -> Unit,
    val onPressEnd: (Boolean) -> Unit,
    val onStop: () -> Unit,
    val onCancel: () -> Unit,
    val onDismiss: () -> Unit,
    val onCopy: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenApp: () -> Unit,
    val onCloseBubble: () -> Unit,
    val onSelectPrompt: (String) -> Unit,
    val onDrag: (Float, Float) -> Unit,
    val onDragEnd: () -> Unit,
)

class BubbleOverlayView(
    context: Context,
    state: StateFlow<OverlayState>,
    private val actions: OverlayActions,
) {
    private val lifecycleOwner = OverlayLifecycleOwner()
    val view: ComposeView = ComposeView(context).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(lifecycleOwner)
        setContent {
            val overlay by state.collectAsState()
            JoynixScribeTheme(darkTheme = true, dynamicColor = overlay.settings.dynamicColor) {
                OverlayContent(overlay, actions)
            }
        }
    }

    fun attach() {
        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()
    }

    fun destroy() {
        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()
    }
}

private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun onCreate() {
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause() = registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop() = registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

@Composable
private fun OverlayContent(state: OverlayState, actions: OverlayActions) {
    // En mode « maintenir », la bulle reste visible pendant l'enregistrement :
    // sinon le relâchement du doigt serait perdu quand le panneau remplace la bulle.
    val holdRecording = state.settings.bubbleGesture == BubbleGesture.HOLD &&
        state.phase == BubblePhase.RECORDING
    val expanded = state.phase != BubblePhase.IDLE && !holdRecording
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            (fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.92f))
                .togetherWith(fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.94f))
        },
        label = "overlay",
    ) { isExpanded ->
        if (isExpanded) {
            Panel(state, actions)
        } else {
            Bubble(state, actions)
        }
    }
}

@Composable
private fun Bubble(state: OverlayState, actions: OverlayActions) {
    val size = state.settings.bubbleSizeDp.dp
    val opacity = state.settings.bubbleOpacity
    val listening = state.phase == BubblePhase.RECORDING
    val pulse = if (listening) {
        val infinite = rememberInfiniteTransition(label = "bubble")
        val animated by infinite.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulse",
        )
        animated
    } else {
        1f
    }
    val accent = state.settings.bubbleAccent
    val base = Color(accent.base)
    val deep = Color(accent.deep)
    val glow = Color(accent.glow)
    val hold = state.settings.bubbleGesture == BubbleGesture.HOLD
    val idleIcon = when (state.settings.bubbleIcon) {
        BubbleIcon.MIC -> Icons.Rounded.Mic
        BubbleIcon.SPARK -> Icons.Rounded.AutoAwesome
        BubbleIcon.WAVE -> Icons.Rounded.GraphicEq
        BubbleIcon.DOT -> Icons.Rounded.FiberManualRecord
    }

    Box(
        modifier = Modifier
            .size(size)
            .padding(4.dp)
            .shadow(if (listening) 20.dp else 10.dp, CircleShape, ambientColor = glow, spotColor = glow)
            .clip(CircleShape)
            .background(
                if (listening) {
                    Brush.radialGradient(
                        listOf(Color(0xFFFFB199), Color(0xFFE24B4B), Color(0xFF7A1530)),
                    )
                } else {
                    Brush.radialGradient(listOf(base, deep, deep.copy(alpha = 0.92f)))
                },
            )
            .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
            .alpha(opacity)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        actions.onDrag(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = { actions.onDragEnd() },
                    onDragCancel = { actions.onDragEnd() },
                )
            }
            .pointerInput(state.settings.bubbleGesture) {
                detectTapGestures(
                    onPress = {
                        if (hold) actions.onPressStart()
                        val released = tryAwaitRelease()
                        if (hold) actions.onPressEnd(released)
                    },
                    onTap = { if (!hold) actions.onTap() },
                    onLongPress = { if (!hold) actions.onLongPress() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (listening) {
            Box(
                Modifier
                    .size(size * pulse)
                    .border(1.5.dp, Color.White.copy(alpha = 0.20f), CircleShape),
            )
        }
        Icon(
            imageVector = when (state.phase) {
                BubblePhase.RECORDING -> Icons.Rounded.Stop
                BubblePhase.PROCESSING -> Icons.Rounded.GraphicEq
                BubblePhase.RESULT -> Icons.Rounded.Check
                BubblePhase.ERROR -> Icons.Rounded.ErrorOutline
                else -> idleIcon
            },
            contentDescription = "JoynixScribe",
            tint = Color.White,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
private fun Panel(state: OverlayState, actions: OverlayActions) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = scheme.surfaceContainerHigh.copy(alpha = 0.97f),
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        modifier = Modifier
            .width(304.dp)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (state.phase) {
                BubblePhase.RECORDING -> RecordingPanel(state, actions)
                BubblePhase.PROCESSING -> ProcessingPanel(state, actions)
                BubblePhase.RESULT -> ResultPanel(state, actions)
                BubblePhase.ERROR -> ErrorPanel(state, actions)
                BubblePhase.MENU -> MenuPanel(state, actions)
                BubblePhase.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun RecordingPanel(state: OverlayState, actions: OverlayActions) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF6B6B)),
        )
        Spacer(Modifier.width(8.dp))
        Text("Écoute…", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            formatDuration(state.elapsedMs),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            color = scheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        MiniAction(Icons.Rounded.Close, "Annuler", actions.onCancel)
    }

    Spacer(Modifier.height(12.dp))
    Waveform(state.amplitudes, state.level)
    Spacer(Modifier.height(12.dp))

    Text(
        state.partialText.ifBlank { "Parle naturellement. Appuie sur ■ pour terminer." },
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.partialText.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp),
    )

    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PanelButton(
            text = "Terminer",
            icon = Icons.Rounded.Check,
            filled = true,
            modifier = Modifier.weight(1f),
            onClick = actions.onStop,
        )
        PanelButton(
            text = "Annuler",
            icon = Icons.Rounded.Close,
            filled = false,
            modifier = Modifier.weight(1f),
            onClick = actions.onCancel,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "La dictée reste sur cet appareil jusqu'à l'envoi à l'IA.",
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
    )
}

@Composable
private fun ProcessingPanel(state: OverlayState, actions: OverlayActions) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = scheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Transcription…", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                state.settings.primary.shortLabel + " · " +
                    if (state.settings.hedgeEnabled) "repli anticipé" else "un seul appel",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        MiniAction(Icons.Rounded.Close, "Annuler", actions.onCancel)
    }
    Spacer(Modifier.height(14.dp))
    LinearShimmer()
    Spacer(Modifier.height(10.dp))
    Text(
        "Le premier modèle qui répond gagne. Le texte s'insère automatiquement au curseur.",
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
}

@Composable
private fun ResultPanel(state: OverlayState, actions: OverlayActions) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(scheme.secondary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, tint = scheme.secondary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (state.insertionNote.isBlank()) "Inséré au curseur" else state.insertionNote,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            Text(
                buildString {
                    append(state.providerLabel)
                    if (state.latencyMs > 0) append(" · ").append(formatLatency(state.latencyMs))
                    if (state.model.isNotBlank()) append(" · ").append(state.model)
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val usage = usageLabel(state)
            if (usage.isNotBlank() || state.appName.isNotBlank()) {
                Text(
                    buildString {
                        if (usage.isNotBlank()) append(usage)
                        if (state.appName.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(state.appName)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        state.resultText,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 272.dp),
    )
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelButton(
            text = if (state.copied) "Copié" else "Copier",
            icon = Icons.Rounded.ContentCopy,
            filled = false,
            modifier = Modifier.weight(1f),
            onClick = actions.onCopy,
        )
        PanelButton(
            text = "Fermer",
            icon = Icons.Rounded.Check,
            filled = true,
            modifier = Modifier.weight(1f),
            onClick = actions.onDismiss,
        )
    }
}

@Composable
private fun ErrorPanel(state: OverlayState, actions: OverlayActions) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.error, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text("Ça n'a pas marché", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        MiniAction(Icons.Rounded.Close, "Fermer", actions.onDismiss)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        state.errorText.ifBlank { "Erreur inconnue" },
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelButton(
            text = "Réessayer",
            icon = Icons.Rounded.Refresh,
            filled = true,
            modifier = Modifier.weight(1f),
            onClick = actions.onRetry,
        )
        PanelButton(
            text = "Fermer",
            icon = Icons.Rounded.Close,
            filled = false,
            modifier = Modifier.weight(1f),
            onClick = actions.onDismiss,
        )
    }
    if (state.resultText.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Audio conservé en mémoire pour un nouvel essai.",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MenuPanel(state: OverlayState, actions: OverlayActions) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("JoynixScribe", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        MiniAction(Icons.Rounded.Close, "Fermer le menu", actions.onDismiss)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Style de sortie",
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .heightIn(max = 190.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.prompts.forEach { prompt ->
            val active = prompt.id == state.activePromptId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHighest.copy(alpha = 0.5f),
                    )
                    .clickable { actions.onSelectPrompt(prompt.id) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(prompt.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (prompt.description.isNotBlank()) {
                        Text(
                            prompt.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (active) {
                    Icon(Icons.Rounded.Check, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelButton(
            text = "Ouvrir l'app",
            icon = Icons.Rounded.OpenInNew,
            filled = false,
            modifier = Modifier.weight(1f),
            onClick = actions.onOpenApp,
        )
        PanelButton(
            text = "Quitter",
            icon = Icons.Rounded.Close,
            filled = false,
            modifier = Modifier.weight(1f),
            onClick = actions.onCloseBubble,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (state.accessibilityConnected) {
            "Service d'accessibilité actif · insertion au curseur"
        } else {
            "Service d'accessibilité inactif · le texte sera copié"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (state.accessibilityConnected) scheme.onSurfaceVariant else scheme.error,
    )
}

@Composable
private fun Waveform(amplitudes: List<Float>, level: Float) {
    val scheme = MaterialTheme.colorScheme
    val animatedLevel by animateFloatAsState(level, tween(90), label = "level")
    val bars = 26
    val values = buildList {
        val history = amplitudes.takeLast(bars)
        repeat(bars - history.size) { add(0.06f) }
        addAll(history)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerLowest.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        val barWidth = size.width / (bars * 1.7f)
        val gap = (size.width - barWidth * bars) / (bars - 1).coerceAtLeast(1)
        val centerY = size.height / 2f
        values.forEachIndexed { index, value ->
            val boosted = (value * 0.75f + animatedLevel * 0.25f).coerceIn(0.04f, 1f)
            val barHeight = size.height * boosted
            val x = index * (barWidth + gap)
            drawLine(
                brush = Brush.verticalGradient(
                    listOf(scheme.secondary, scheme.primary, scheme.tertiary),
                ),
                start = Offset(x + barWidth / 2, centerY - barHeight / 2),
                end = Offset(x + barWidth / 2, centerY + barHeight / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun LinearShimmer() {
    val scheme = MaterialTheme.colorScheme
    val infinite = rememberInfiniteTransition(label = "shimmer")
    val progress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "progress",
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(scheme.surfaceContainerHighest),
    ) {
        val width = size.width * 0.4f
        val start = (size.width + width) * progress - width
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, scheme.primary, Color.Transparent),
                startX = start,
                endX = start + width,
            ),
            topLeft = Offset(start, 0f),
            size = androidx.compose.ui.geometry.Size(width, size.height),
        )
    }
}

@Composable
private fun PanelButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (filled) scheme.primary else scheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            modifier = Modifier.size(16.dp),
            tint = if (filled) scheme.onPrimary else scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) scheme.onPrimary else scheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MiniAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.FRANCE, "%d:%02d", minutes, seconds)
}

private fun formatLatency(ms: Long): String {
    val seconds = ms / 1000.0
    return String.format(Locale.FRANCE, "%.1f s", seconds)
}

private fun usageLabel(state: OverlayState): String = buildString {
    if (state.usageTokens > 0) append("${state.usageTokens} tokens")
    if (state.usageAudioSeconds > 0.0) {
        if (isNotEmpty()) append(" · ")
        append(String.format(Locale.FRANCE, "%.1f s audio", state.usageAudioSeconds))
    }
    if (state.usageNeurons > 0.0) {
        if (isNotEmpty()) append(" · ")
        append(String.format(Locale.FRANCE, "%.2f neurons", state.usageNeurons))
    }
}
