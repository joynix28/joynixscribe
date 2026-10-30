package com.botillon.flowscribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.botillon.flowscribe.data.Provider
import com.botillon.flowscribe.data.ThemeMode
import com.botillon.flowscribe.ui.AppViewModel
import com.botillon.flowscribe.ui.PermKind
import com.botillon.flowscribe.ui.Tab
import com.botillon.flowscribe.ui.screens.DictionaryScreen
import com.botillon.flowscribe.ui.screens.HomeScreen
import com.botillon.flowscribe.ui.screens.KeysScreen
import com.botillon.flowscribe.ui.screens.OnboardingScreen
import com.botillon.flowscribe.ui.screens.PromptsScreen
import com.botillon.flowscribe.ui.screens.SettingsScreen
import com.botillon.flowscribe.ui.theme.JoynixScribeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val ready by vm.ready.collectAsStateWithLifecycle()

            val dark = when (settings.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            JoynixScribeTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when {
                        !ready -> Box(Modifier.fillMaxSize())
                        !settings.onboardingDone -> OnboardingHost()
                        else -> MainShell()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    // ---------------------------------------------------------------- onboarding

    @Composable
    private fun OnboardingHost() {
        val permissions by vm.permissions.collectAsStateWithLifecycle()
        val keys by vm.keys.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val micLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { vm.refreshPermissions() }
        val notificationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { vm.refreshPermissions() }

        fun request(kind: PermKind) {
            when (kind) {
                PermKind.MICROPHONE -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                PermKind.OVERLAY -> runCatching {
                    context.startActivity(Permissions.overlayIntent(context))
                }
                PermKind.ACCESSIBILITY -> runCatching {
                    context.startActivity(Permissions.accessibilityIntent())
                }
                PermKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching {
                        context.startActivity(Permissions.notificationSettingsIntent(context))
                    }
                }
                PermKind.BATTERY -> openBattery(context)
            }
        }

        OnboardingScreen(
            permissions = permissions,
            hasGeminiKey = keys[Provider.GEMINI].orEmpty().isNotEmpty(),
            onPermission = ::request,
            onAddGeminiKey = { secret ->
                vm.addKey(Provider.GEMINI, "Principale", secret)
            },
            onFinish = { vm.completeOnboarding() },
        )
    }

    // --------------------------------------------------------------------- main

    @Composable
    private fun MainShell() {
        val settings by vm.settings.collectAsStateWithLifecycle()
        val prompts by vm.prompts.collectAsStateWithLifecycle()
        val dictionary by vm.dictionary.collectAsStateWithLifecycle()
        val history by vm.history.collectAsStateWithLifecycle()
        val permissions by vm.permissions.collectAsStateWithLifecycle()
        val keys by vm.keys.collectAsStateWithLifecycle()
        val probes by vm.probes.collectAsStateWithLifecycle()
        val bubbleRunning by vm.bubbleRunning.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()

        val context = LocalContext.current
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

        val micLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { vm.refreshPermissions() }
        val notificationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { vm.refreshPermissions() }

        fun request(kind: PermKind) {
            when (kind) {
                PermKind.MICROPHONE -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                PermKind.OVERLAY -> runCatching {
                    context.startActivity(Permissions.overlayIntent(context))
                }
                PermKind.ACCESSIBILITY -> runCatching {
                    context.startActivity(Permissions.accessibilityIntent())
                }
                PermKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching {
                        context.startActivity(Permissions.notificationSettingsIntent(context))
                    }
                }
                PermKind.BATTERY -> openBattery(context)
            }
        }

        LaunchedEffect(message?.id) {
            val current = message ?: return@LaunchedEffect
            snackbar.showSnackbar(current.text)
            vm.consumeMessage(current.id)
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                MainNavigationBar(
                    selected = tab,
                    onSelect = { tab = it },
                )
            },
        ) { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(inner),
            ) {
                Crossfade(
                    targetState = tab,
                    animationSpec = tween(220),
                    label = "tab",
                ) { current ->
                    when (current) {
                        Tab.HOME -> HomeScreen(
                            settings = settings,
                            prompts = prompts,
                            history = history,
                            dictionary = dictionary,
                            permissions = permissions,
                            bubbleRunning = bubbleRunning,
                            hasAnyKey = keys.values.any { list -> list.isNotEmpty() },
                            onToggleBubble = { vm.toggleBubble() },
                            onPermission = ::request,
                            onSelectPrompt = { vm.selectPrompt(it) },
                            onOpenPrompts = { tab = Tab.PROMPTS },
                            onOpenKeys = { tab = Tab.KEYS },
                            onCopy = { copyText(context, it) },
                        )

                        Tab.KEYS -> KeysScreen(
                            keys = keys,
                            probes = probes,
                            onAdd = { provider, label, secret -> vm.addKey(provider, label, secret) },
                            onDelete = { provider, id -> vm.deleteKey(provider, id) },
                            onToggle = { provider, id, enabled -> vm.setKeyEnabled(provider, id, enabled) },
                            onTest = { provider, entry -> vm.testKey(provider, entry) },
                        )

                        Tab.PROMPTS -> PromptsScreen(
                            prompts = prompts,
                            activeId = settings.activePromptId,
                            onSelect = { vm.selectPrompt(it) },
                            onCreate = { name, description, instructions, packages ->
                                vm.createPrompt(name, description, instructions, packages)
                            },
                            onUpdate = { vm.updatePrompt(it) },
                            onDuplicate = { vm.duplicatePrompt(it) },
                            onDelete = { vm.deletePrompt(it) },
                            onReset = { vm.resetPrompts() },
                        )

                        Tab.DICTIONARY -> DictionaryScreen(
                            dictionary = dictionary,
                            onAdd = { term, replacement, caseSensitive ->
                                vm.addDictionaryEntry(term, replacement, caseSensitive)
                            },
                            onUpdate = { vm.updateDictionaryEntry(it) },
                            onDelete = { vm.deleteDictionaryEntry(it) },
                        )

                        Tab.SETTINGS -> SettingsScreen(
                            vm = vm,
                            settings = settings,
                            keys = keys,
                            historyCount = history.size,
                            permissions = permissions,
                            onPermission = ::request,
                            onExport = {
                                val json = vm.exportJson()
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "JoynixScribe — configuration")
                                    putExtra(Intent.EXTRA_TEXT, json)
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(intent, "Exporter la configuration"),
                                    )
                                }.onFailure {
                                    copyText(context, json)
                                    scope.launch { snackbar.showSnackbar("Configuration copiée.") }
                                }
                            },
                            onClearHistory = { vm.clearHistory() },
                            onOpenKeys = { tab = Tab.KEYS },
                            onOpenVendorSettings = {
                                val intent = com.botillon.flowscribe.device.DeviceCompat.autostartIntent(context)
                                    ?: com.botillon.flowscribe.device.DeviceCompat.autostartFallbackIntent(context)
                                runCatching { context.startActivity(intent) }
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MainNavigationBar(
        selected: Tab,
        onSelect: (Tab) -> Unit,
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Tab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            imageVector = tab.icon(),
                            contentDescription = tab.label,
                        )
                    },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Rounded.Home
    Tab.KEYS -> Icons.Rounded.VpnKey
    Tab.PROMPTS -> Icons.Rounded.Style
    Tab.DICTIONARY -> Icons.Rounded.MenuBook
    Tab.SETTINGS -> Icons.Rounded.Settings
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("JoynixScribe", text))
}

private fun openBattery(context: Context) {
    val direct = runCatching { context.startActivity(Permissions.batteryIntent(context)) }
    if (direct.isFailure) {
        runCatching { context.startActivity(Permissions.appDetailsIntent(context)) }
    }
}
