package dev.loosewire.hermeslight.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WavAudioTest {
    @Test
    fun `known PCM has canonical WAV header length and little endian payload`() {
        val pcm = floatArrayOf(-1f, -1f / 32768, 0f, 1f / 32768, 32767f / 32768)
        val wav = WavAudio.encode(pcm)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(10, header.getInt(40))
        assertContentEquals(
            byteArrayOf(0, -128, -1, -1, 0, 0, 1, 0, -1, 127),
            wav.copyOfRange(44, wav.size),
        )
    }

    @Test
    fun `data URL is unwrapped base64 of the entire WAV`() {
        val pcm = FloatArray(100) { it / 32768f }
        val url = WavAudio.dataUrl(pcm)
        assertTrue(url.startsWith("data:audio/wav;base64,"))
        assertFalse(url.contains('\n'))
        assertFalse(url.contains('\r'))
        assertContentEquals(WavAudio.encode(pcm), Base64.getDecoder().decode(url.substringAfter(',')))
    }
}
