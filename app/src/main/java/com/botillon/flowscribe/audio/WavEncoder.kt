package com.botillon.flowscribe.audio

import java.io.ByteArrayOutputStream

object WavEncoder {

    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_FRAME = CHANNELS * BITS_PER_SAMPLE / 8

    fun encode(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): ByteArray {
        val dataSize = pcm.size
        val out = ByteArrayOutputStream(44 + dataSize)
        val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
        val blockAlign = channels * BITS_PER_SAMPLE / 8

        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intLe(36 + dataSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(intLe(16))
        out.write(shortLe(1))
        out.write(shortLe(channels))
        out.write(intLe(sampleRate))
        out.write(intLe(byteRate))
        out.write(shortLe(blockAlign))
        out.write(shortLe(BITS_PER_SAMPLE))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(intLe(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Coupe le silence au début et à la fin (fenêtres de 20 ms) et garde une marge de 120 ms.
     * Évite d'envoyer 3 secondes de silence à l'API, ce qui dégrade la latence et la qualité.
     */
    fun trimSilence(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, threshold: Float = 0.012f): ByteArray {
        val frameSamples = sampleRate / 50
        val frameBytes = frameSamples * BYTES_PER_FRAME
        val frameCount = pcm.size / frameBytes
        if (frameCount < 3) return pcm

        var first = -1
        var last = -1
        for (i in 0 until frameCount) {
            if (frameRms(pcm, i * frameBytes, frameBytes) >= threshold) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) {
            // Aucune parole claire : on renvoie quand même l'audio (bruit faible, souffle...).
            return pcm
        }

        val margin = 6 // 120 ms
        val startFrame = (first - margin).coerceAtLeast(0)
        val endFrame = (last + margin).coerceAtMost(frameCount - 1)
        val start = startFrame * frameBytes
        val end = ((endFrame + 1) * frameBytes).coerceAtMost(pcm.size)
        return pcm.copyOfRange(start, end)
    }

    private fun frameRms(pcm: ByteArray, offset: Int, length: Int): Float {
        var sum = 0.0
        var count = 0
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val normalized = sample / 32768f
            sum += normalized * normalized
            count++
            i += 2
        }
        if (count == 0) return 0f
        return kotlin.math.sqrt(sum / count).toFloat()
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )
}
