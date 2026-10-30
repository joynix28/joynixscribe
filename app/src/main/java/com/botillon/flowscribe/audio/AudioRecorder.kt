package com.botillon.flowscribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Recording(
    val wav: ByteArray,
    val durationMs: Long,
    val pcmBytes: Int,
)

/**
 * Capture PCM 16 bits mono 16 kHz directement en mémoire : pas de fichier temporaire,
 * snapshot possible pendant l'enregistrement pour l'aperçu live, WAV construit à la fin.
 */
class AudioRecorder {

    private val pcm = ByteArrayOutputStream(16_000 * 2 * 20)
    private val lock = Any()

    private var record: AudioRecord? = null
    private var readJob: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile private var capturing = false
    private var startedAt = 0L

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    val isRecording: Boolean get() = capturing

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (capturing) return true
        val sampleRate = WavEncoder.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferBytes = max(minBuffer, sampleRate / 5 * 2)

        val candidates = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        var created: AudioRecord? = null
        for (source in candidates) {
            val candidate = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferBytes * 2)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord($source) impossible: ${e.message}")
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                created = candidate
                break
            }
            candidate?.release()
        }
        val recorder = created ?: return false

        synchronized(lock) { pcm.reset() }
        record = recorder
        this.scope = scope
        startedAt = System.currentTimeMillis()
        capturing = true

        val started = runCatching { recorder.startRecording() }.isSuccess
        if (!started) {
            capturing = false
            runCatching { recorder.release() }
            record = null
            return false
        }

        readJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(bufferBytes)
            var lastLevelAt = 0L
            var smoothed = 0f
            while (isActive && capturing) {
                val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(0)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                synchronized(lock) { pcm.write(buffer, 0, read) }

                val now = System.currentTimeMillis()
                if (now - lastLevelAt >= 55L) {
                    lastLevelAt = now
                    val instant = rms(buffer, read)
                    smoothed = if (instant > smoothed) {
                        smoothed * 0.35f + instant * 0.65f
                    } else {
                        smoothed * 0.72f + instant * 0.28f
                    }
                    _level.value = smoothed.coerceIn(0f, 1f)
                }
            }
            _level.value = 0f
        }
        return true
    }

    /**
     * WAV prêt à envoyer (silence coupé) pour l'aperçu live.
     * On ne renvoie que la fin de la captation : l'aperçu reste utile et coûte
     * beaucoup moins cher qu'un envoi de tout l'enregistrement à chaque cycle.
     */
    fun snapshotWav(windowSeconds: Int = 9): ByteArray? {
        if (!capturing) return null
        val copy = synchronized(lock) { pcm.toByteArray() }
        if (copy.size < WavEncoder.SAMPLE_RATE / 2) return null
        val maxBytes = windowSeconds.coerceIn(4, 20) * WavEncoder.SAMPLE_RATE * 2
        val tail = if (copy.size > maxBytes) copy.copyOfRange(copy.size - maxBytes, copy.size) else copy
        val evenSize = tail.size - (tail.size % 2)
        val trimmed = WavEncoder.trimSilence(tail.copyOf(evenSize))
        return WavEncoder.encode(trimmed)
    }

    suspend fun stop(): Recording? {
        val recorder = record ?: return null
        capturing = false
        runCatching { recorder.stop() }
        readJob?.cancelAndJoin()
        readJob = null
        runCatching { recorder.release() }
        record = null
        _level.value = 0f

        val copy = synchronized(lock) { pcm.toByteArray() }
        synchronized(lock) { pcm.reset() }
        val evenSize = copy.size - (copy.size % 2)
        if (evenSize < WavEncoder.SAMPLE_RATE / 4) return null
        val pcmAudio = copy.copyOf(evenSize)
        val duration = (pcmAudio.size.toLong() * 1000L) / (WavEncoder.SAMPLE_RATE * 2)
        val trimmed = WavEncoder.trimSilence(pcmAudio)
        return Recording(
            wav = WavEncoder.encode(trimmed),
            durationMs = max(duration, (System.currentTimeMillis() - startedAt)),
            pcmBytes = trimmed.size,
        )
    }

    fun cancel() {
        capturing = false
        val recorder = record
        record = null
        scope = null
        runCatching { recorder?.stop() }
        readJob?.cancel()
        readJob = null
        runCatching { recorder?.release() }
        synchronized(lock) { pcm.reset() }
        _level.value = 0f
    }

    val elapsedMs: Long
        get() = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    private fun rms(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        val usable = min(length, buffer.size) - 1
        while (i < usable) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            val normalized = sample / 32768f
            sum += normalized * normalized
            count++
            i += 2
        }
        if (count == 0) return 0f
        val value = sqrt(sum / count).toFloat()
        // Compression douce : la parole normale reste lisible sans saturer.
        return (value * 4.2f).coerceIn(0f, 1f)
    }

    private companion object {
        const val TAG = "FlowScribeAudio"
    }
}
