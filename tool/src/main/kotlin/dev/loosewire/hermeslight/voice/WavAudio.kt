/*
 * Copyright 2026 LooseWireDev
 */
package dev.loosewire.hermeslight.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Dashboard wire audio: RIFF/WAVE, 16 kHz mono signed PCM16, no container metadata. */
internal object WavAudio {
    const val SAMPLE_RATE = 16_000
    const val MIME_TYPE = "audio/wav"

    fun encode(pcm: FloatArray): ByteArray {
        val dataSize = pcm.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46464952) // RIFF
        buffer.putInt(36 + dataSize)
        buffer.putInt(0x45564157) // WAVE
        buffer.putInt(0x20746D66) // fmt
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * 2)
        buffer.putShort(2) // block alignment
        buffer.putShort(16)
        buffer.putInt(0x61746164) // data
        buffer.putInt(dataSize)
        for (sample in pcm) {
            buffer.putShort((sample * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        }
        return buffer.array()
    }

    fun dataUrl(pcm: FloatArray): String = "data:$MIME_TYPE;base64,${Base64.getEncoder().encodeToString(encode(pcm))}"
}
