package com.botillon.flowscribe.service

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.botillon.flowscribe.FlowScribeApp
import com.botillon.flowscribe.MainActivity
import com.botillon.flowscribe.Permissions
import com.botillon.flowscribe.ai.EngineInput
import com.botillon.flowscribe.ai.EngineResult
import com.botillon.flowscribe.ai.TranscriptionEngine
import com.botillon.flowscribe.audio.AudioRecorder
import com.botillon.flowscribe.data.ApiKeyStore
import com.botillon.flowscribe.data.AppSettings
import com.botillon.flowscribe.data.DictionaryEntry
import com.botillon.flowscribe.data.HistoryEntry
import com.botillon.flowscribe.data.InsertMode
import com.botillon.flowscribe.data.PromptPreset
import com.botillon.flowscribe.data.Provider
import com.botillon.flowscribe.data.SettingsStore
import com.botillon.flowscribe.text.PunctuationProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Bulle flottante : fenêtre overlay Compose + enregistrement + orchestration IA.
 * Le service tourne en premier plan (type microphone) mais n'ouvre le micro
 * qu'entre le tap de départ et le tap d'arrêt.
 */
class FloatingBubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = AudioRecorder()
    private val _state = MutableStateFlow(OverlayState())
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    private lateinit var windowManager: WindowManager
    private var overlay: BubbleOverlayView? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var settingsStore: SettingsStore
    private lateinit var keyStore: ApiKeyStore
    private lateinit var engine: TranscriptionEngine

    private var settings = AppSettings()
    private var prompts: List<PromptPreset> = emptyList()
    private var dictionary: List<DictionaryEntry> = emptyList()

    private var timerJob: Job? = null
    private var previewJob: Job? = null
    private var transcribeJob: Job? = null
    private var dismissJob: Job? = null
    private var observeJob: Job? = null

    @Volatile private var recording = false
    @Volatile private var previewInFlight = false
    private var lastWav: ByteArray? = null
    private val amplitudes = ArrayDeque<Float>()

    private var savedX = -1
    private var savedY = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        setRunning(true)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val app = application as FlowScribeApp
        settingsStore = app.settingsStore
        keyStore = app.keyStore
        engine = app.engine

        val prefs = getSharedPreferences("flowscribe_bubble", MODE_PRIVATE)
        savedX = prefs.getInt("x", -1)
        savedY = prefs.getInt("y", -1)

        Notifications.ensureChannels(this)
        val foregroundStarted = runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.SERVICE_ID,
                Notifications.bubble(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }.isSuccess

        if (!foregroundStarted) {
            Log.w(TAG, "Impossible de démarrer le service au premier plan")
            stopSelf()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Permission overlay absente")
            stopSelf()
            return
        }

        attachOverlay()
        observeSettings()
    }

    private fun attachOverlay() {
        val view = BubbleOverlayView(this, state, actions())
        overlay = view
        val layout = WindowManager.LayoutParams(
            collapsedSize(),
            collapsedSize(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val bounds = screenBounds()
            if (savedX < 0 || savedY < 0) {
                x = bounds.width() - collapsedSize() - dp(12)
                y = (bounds.height() * 0.34f).roundToInt()
            } else {
                x = savedX.coerceIn(0, (bounds.width() - collapsedSize()).coerceAtLeast(0))
                y = savedY.coerceIn(0, (bounds.height() - collapsedSize()).coerceAtLeast(0))
            }
            windowAnimations = 0
        }
        params = layout
        runCatching {
            windowManager.addView(view.view, layout)
            view.attach()
        }.onFailure {
            Log.e(TAG, "Ajout de la bulle impossible", it)
            stopSelf()
        }
    }

    private fun observeSettings() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                settingsStore.settings,
                settingsStore.prompts,
                settingsStore.dictionary,
            ) { s, p, d -> Triple(s, p, d) }.collect { (s, p, d) ->
                settings = s
                prompts = p
                dictionary = d
                _state.update {
                    it.copy(
                        settings = s,
                        prompts = p,
                        activePromptId = s.activePromptId,
                        accessibilityConnected = DictationAccessibilityService.isConnected,
                    )
                }
                if (_state.value.phase == BubblePhase.IDLE) {
                    resize(collapsedSize(), collapsedSize())
                }
            }
        }
    }

    private fun actions(): OverlayActions = OverlayActions(
        onTap = {
            when (_state.value.phase) {
                BubblePhase.IDLE -> startRecording()
                BubblePhase.RECORDING -> scope.launch { stopRecording() }
                BubblePhase.RESULT, BubblePhase.ERROR, BubblePhase.MENU -> dismiss()
                BubblePhase.PROCESSING -> Unit
            }
        },
        onLongPress = {
            when (_state.value.phase) {
                BubblePhase.IDLE -> openMenu()
                BubblePhase.MENU, BubblePhase.RESULT, BubblePhase.ERROR -> dismiss()
                else -> Unit
            }
        },
        onStop = { scope.launch { stopRecording() } },
        onCancel = { cancelDictation() },
        onDismiss = { dismiss() },
        onCopy = { copyResult() },
        onRetry = { retry() },
        onOpenApp = { openApp() },
        onCloseBubble = { stopSelf() },
        onSelectPrompt = { id ->
            scope.launch {
                settingsStore.setActivePrompt(id)
                _state.update { it.copy(activePromptId = id) }
            }
        },
        onDrag = { dx, dy -> drag(dx, dy) },
        onDragEnd = { snapToEdge() },
    )

    // ---------------------------------------------------------------- recording

    private fun startRecording() {
        if (recording) return
        if (!Permissions.hasMicrophone(this)) {
            showError("Autorise le micro dans FlowScribe pour dicter.")
            return
        }
        dismissJob?.cancel()
        _state.update {
            it.copy(
                phase = BubblePhase.RECORDING,
                elapsedMs = 0,
                level = 0f,
                amplitudes = emptyList(),
                partialText = "",
                resultText = "",
                errorText = "",
                insertionNote = "",
                copied = false,
            )
        }
        amplitudes.clear()
        expand()

        val started = recorder.start(scope)
        if (!started) {
            showError("Micro indisponible. Vérifie qu'aucune autre app ne l'utilise.")
            return
        }
        recording = true
        haptic(14)
        updateNotification("Enregistrement en cours", "Appuie sur ■ pour terminer")

        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && recording) {
                delay(90)
                val level = recorder.level.value
                amplitudes.addLast(level)
                while (amplitudes.size > 30) amplitudes.removeFirst()
                _state.update {
                    it.copy(
                        elapsedMs = recorder.elapsedMs,
                        level = level,
                        amplitudes = amplitudes.toList(),
                    )
                }
                if (recorder.elapsedMs >= settings.maxDurationSec * 1000L) {
                    stopRecording()
                    break
                }
            }
        }

        previewJob?.cancel()
        if (settings.livePreview) {
            previewJob = scope.launch {
                delay(settings.previewIntervalMs)
                while (isActive && recording) {
                    if (!previewInFlight) {
                        val wav = recorder.snapshotWav()
                        if (wav != null) {
                            previewInFlight = true
                            val result = runCatching {
                                engine.transcribe(
                                    EngineInput(
                                        wav = wav,
                                        settings = settings,
                                        prompts = prompts,
                                        dictionary = dictionary,
                                        preview = true,
                                    ),
                                )
                            }.getOrNull()
                            previewInFlight = false
                            if (result is EngineResult.Success && recording) {
                                _state.update {
                                    it.copy(partialText = PunctuationProcessor.preview(result.data.text))
                                }
                            }
                        }
                    }
                    delay(settings.previewIntervalMs)
                }
            }
        }
    }

    private suspend fun stopRecording() {
        if (!recording) return
        recording = false
        timerJob?.cancel()
        previewJob?.cancel()
        _state.update { it.copy(phase = BubblePhase.PROCESSING, partialText = "") }
        updateNotification("Transcription…", "Un seul appel, réponse rapide")
        haptic(18)

        val recording = withContext(Dispatchers.IO) { recorder.stop() }
        if (recording == null || recording.pcmBytes < 6_000) {
            _state.update { it.copy(phase = BubblePhase.IDLE) }
            updateNotification()
            showError("Dictée trop courte, réessaie en parlant un peu plus longtemps.")
            return
        }
        lastWav = recording.wav
        transcribe(recording.wav)
    }

    private fun transcribe(wav: ByteArray) {
        transcribeJob?.cancel()
        _state.update { it.copy(phase = BubblePhase.PROCESSING, errorText = "") }
        transcribeJob = scope.launch {
            val input = EngineInput(
                wav = wav,
                settings = settings,
                prompts = prompts,
                dictionary = dictionary,
            )
            when (val result = engine.transcribe(input)) {
                is EngineResult.Success -> onTranscribed(result)
                is EngineResult.Failure -> showError(result.message)
            }
        }
    }

    private suspend fun onTranscribed(result: EngineResult.Success) {
        val data = result.data
        val insertion = DictationAccessibilityService.instance?.insertAtCursor(data.text, settings.insertMode)
        var note: String
        var copied = false

        when (insertion) {
            is InsertionResult.Success -> note = "Inséré au curseur"
            InsertionResult.NoField -> {
                copyToClipboard(data.text)
                copied = true
                note = "Aucun champ actif · texte copié"
            }
            InsertionResult.SecureField -> {
                copyToClipboard(data.text)
                copied = true
                note = "Champ sécurisé · texte copié"
            }
            InsertionResult.NoService -> {
                copyToClipboard(data.text)
                copied = true
                note = "Accessibilité inactive · texte copié"
            }
            is InsertionResult.Failed -> {
                copyToClipboard(data.text)
                copied = true
                note = "Insertion refusée · texte copié"
            }
            null -> {
                copyToClipboard(data.text)
                copied = true
                note = "Accessibilité inactive · texte copié"
            }
        }

        _state.update {
            it.copy(
                phase = BubblePhase.RESULT,
                resultText = data.text,
                providerLabel = data.provider.shortLabel,
                model = data.model,
                latencyMs = data.latencyMs,
                insertionNote = note,
                copied = copied,
                accessibilityConnected = DictationAccessibilityService.isConnected,
            )
        }
        expand()
        haptic(if (copied) 10 else 22)
        updateNotification("FlowScribe est prêt", "Dernière dictée : ${data.latencyMs / 1000.0} s")
        if (copied) Notifications.result(this, data.text, copied = true)

        val app = application as FlowScribeApp
        app.appScope.launch {
            runCatching {
                settingsStore.addHistory(
                    HistoryEntry(
                        text = data.text,
                        provider = data.provider.id,
                        model = data.model,
                        latencyMs = data.latencyMs,
                        appPackage = DictationAccessibilityService.lastPackage,
                        injected = !copied,
                    ),
                )
            }
        }

        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(if (copied) 5_000L else 2_800L)
            dismiss()
        }
    }

    private fun showError(message: String) {
        _state.update {
            it.copy(phase = BubblePhase.ERROR, errorText = message, partialText = "")
        }
        expand()
        haptic(30)
        updateNotification("FlowScribe — erreur", message.take(120))
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(7_000L)
            if (_state.value.phase == BubblePhase.ERROR) dismiss()
        }
    }

    private fun retry() {
        val wav = lastWav ?: return
        transcribe(wav)
    }

    private fun cancelDictation() {
        recording = false
        timerJob?.cancel()
        previewJob?.cancel()
        transcribeJob?.cancel()
        recorder.cancel()
        _state.update { it.copy(phase = BubblePhase.IDLE, partialText = "") }
        updateNotification()
    }

    private fun dismiss() {
        if (_state.value.phase == BubblePhase.RECORDING) {
            cancelDictation()
            return
        }
        dismissJob?.cancel()
        _state.update {
            it.copy(
                phase = BubblePhase.IDLE,
                partialText = "",
                resultText = "",
                errorText = "",
                insertionNote = "",
                copied = false,
                latencyMs = 0,
                elapsedMs = 0,
            )
        }
        collapse()
    }

    private fun openMenu() {
        dismissJob?.cancel()
        _state.update {
            it.copy(phase = BubblePhase.MENU, accessibilityConnected = DictationAccessibilityService.isConnected)
        }
        expand()
    }

    private fun copyResult() {
        val text = _state.value.resultText
        if (text.isBlank()) return
        copyToClipboard(text)
        _state.update { it.copy(copied = true, insertionNote = "Texte copié") }
        Notifications.result(this, text, copied = true)
        haptic(10)
    }

    private fun copyToClipboard(text: String) = runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FlowScribe", text))
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { startActivity(intent) }
    }

    // ---------------------------------------------------------------- window

    private fun expand() {
        if (params == null) return
        val bounds = screenBounds()
        val width = dp(PANEL_WIDTH_DP)
        val current = params ?: return
        if (current.width != width) {
            current.width = width
            current.height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        val anchorX = if (savedX >= 0) savedX else current.x
        val anchorY = if (savedY >= 0) savedY else current.y
        val centerX = anchorX + collapsedSize() / 2
        current.x = (centerX - width / 2).coerceIn(dp(8), (bounds.width() - width - dp(8)).coerceAtLeast(dp(8)))
        current.y = anchorY.coerceIn(dp(12), (bounds.height() - dp(390)).coerceAtLeast(dp(12)))
        updateLayout()
    }

    private fun collapse() {
        if (params == null) return
        val bounds = screenBounds()
        val current = params ?: return
        current.width = collapsedSize()
        current.height = collapsedSize()
        current.x = savedX.coerceIn(0, (bounds.width() - collapsedSize()).coerceAtLeast(0))
        current.y = savedY.coerceIn(0, (bounds.height() - collapsedSize()).coerceAtLeast(0))
        if (savedX < 0 || savedY < 0) {
            current.x = bounds.width() - collapsedSize() - dp(12)
            current.y = (bounds.height() * 0.34f).roundToInt()
        }
        updateLayout()
    }

    private fun resize(width: Int, height: Int) {
        val current = params ?: return
        if (current.width == width && current.height == height) return
        current.width = width
        current.height = height
        updateLayout()
    }

    private fun drag(dx: Float, dy: Float) {
        if (_state.value.phase != BubblePhase.IDLE) return
        val current = params ?: return
        val bounds = screenBounds()
        val size = collapsedSize()
        current.x = (current.x + dx).roundToInt().coerceIn(0, (bounds.width() - size).coerceAtLeast(0))
        current.y = (current.y + dy).roundToInt().coerceIn(0, (bounds.height() - size - dp(8)).coerceAtLeast(0))
        savedX = current.x
        savedY = current.y
        updateLayout()
    }

    private fun snapToEdge() {
        val current = params ?: return
        val bounds = screenBounds()
        val targetX = if (current.x + collapsedSize() / 2 < bounds.width() / 2) dp(12) else bounds.width() - collapsedSize() - dp(12)
        savedX = targetX.coerceAtLeast(0)
        savedY = current.y
        val startX = current.x
        val animator = android.animation.ValueAnimator.ofInt(startX, savedX).apply {
            duration = 160L
            addUpdateListener { animation ->
                current.x = animation.animatedValue as Int
                updateLayout()
            }
        }
        animator.start()
        getSharedPreferences("flowscribe_bubble", MODE_PRIVATE)
            .edit()
            .putInt("x", savedX)
            .putInt("y", savedY)
            .apply()
    }

    private fun updateLayout() {
        val view = overlay?.view ?: return
        val current = params ?: return
        runCatching { windowManager.updateViewLayout(view, current) }
    }

    private fun collapsedSize(): Int = dp(settings.bubbleSizeDp + 8)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun screenBounds(): Rect = runCatching {
        windowManager.currentWindowMetrics.bounds
    }.getOrElse {
        Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    // ---------------------------------------------------------------- misc

    private fun updateNotification(
        title: String = getString(com.botillon.flowscribe.R.string.notification_service_title),
        text: String = getString(com.botillon.flowscribe.R.string.notification_service_text),
    ) {
        runCatching {
            NotificationManagerCompat.from(this).notify(
                Notifications.SERVICE_ID,
                Notifications.bubble(this, title, text, recording),
            )
        }
    }

    private fun haptic(durationMs: Long) {
        if (!settings.haptics) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        setRunning(false)
        observeJob?.cancel()
        timerJob?.cancel()
        previewJob?.cancel()
        transcribeJob?.cancel()
        dismissJob?.cancel()
        recorder.cancel()
        overlay?.let { view ->
            runCatching { windowManager.removeView(view.view) }
            view.destroy()
        }
        overlay = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlowScribeBubble"
        private const val PANEL_WIDTH_DP = 304

        const val ACTION_STOP = "com.botillon.flowscribe.STOP_BUBBLE"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        @Volatile
        var isRunning: Boolean = false
            private set

        fun setRunning(value: Boolean) {
            isRunning = value
            _running.value = value
        }
    }
}
