/*
 * Copyright 2026 LooseWireDev
 *
 * Long-take acceptance for the BrightHermes DictationTake port. The synthetic take is
 * sample-exact: words every 0.5 s, 28 s decode windows, 24 s stride, required seam anchors.
 */
package dev.loosewire.hermeslight.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RATE = 16_000
private const val CHUNK = RATE / 10
private const val WORD_STEP = RATE / 2
private const val PREVIEW_SAMPLES = RATE * 11 / 10

class DictationTakeTest {
    @Test
    fun `three minute take stitches overlapping windows into one continuous transcript`() {
        val harness = Harness(RATE * 180)
        harness.run()
        assertTrue(harness.take.isRecording)

        harness.finish()

        val expected = (0 until 360).map { "w%04d".format(it) }
        val final = harness.results.last()
        assertTrue(final.send)
        assertEquals(1, harness.results.count { it.send })
        assertTrue(harness.results.count { !it.send } > 0)
        assertEquals(expected.joinToString(" "), final.text)
        assertEquals(expected, final.text.split(' '))
        assertTrue(harness.take.isFinished)
        assertEquals(0, harness.take.retainedSamples)
    }

    @Test
    fun `explicit three hundred second cap stops capture and still sends once`() {
        val harness = Harness(RATE * 300)
        harness.run()
        assertFalse(harness.take.isRecording)
        assertEquals(0, harness.take.remaining)
        assertFailsWith<IllegalStateException> { harness.take.append(FloatArray(1), 1) }

        harness.finish()

        val expected = (0 until 600).map { "w%04d".format(it) }
        val final = harness.results.last()
        assertTrue(final.send)
        assertEquals(1, harness.results.count { it.send })
        assertEquals(expected.joinToString(" "), final.text)
        assertEquals(0, harness.take.retainedSamples)
    }

    @Test
    fun `wheel-turn cancel discards committed text and sends nothing`() {
        val take = DictationTake(RATE).apply { commit() }
        take.append(FloatArray(RATE * 28), RATE * 28)
        val window = assertNotNull(take.next())
        val tokens = arrayOf("▁w0000")
        val timestamps = floatArrayOf(0f)
        assertEquals("w0000", take.complete(window, tokens, timestamps)?.text)

        take.cancel()

        assertTrue(take.isFinished)
        assertFalse(take.isRecording)
        assertEquals(0, take.retainedSamples)
        assertEquals(0, take.remaining)
        assertNull(take.next())
        assertNull(take.complete(window, tokens, timestamps))
        take.append(FloatArray(1), 1)
        assertEquals(0, take.retainedSamples)
    }

    @Test
    fun `release without commit discards the take`() {
        val take = DictationTake(RATE)
        take.append(FloatArray(RATE * 2), RATE * 2)

        take.release()
        take.endCapture()

        assertNull(take.next())
        assertTrue(take.isFinished)
        assertEquals(0, take.retainedSamples)
    }

    @Test
    fun `retained audio is bounded and a stalled decoder cannot overrun the take`() {
        val take = DictationTake(RATE).apply { commit() }
        val oneSecond = FloatArray(RATE)
        repeat(56) { take.append(oneSecond, RATE) }
        assertEquals(RATE * 56, take.retainedSamples)
        assertEquals(0, take.remaining)
        assertFailsWith<IllegalStateException> { take.append(oneSecond, 1) }

        val window = take.next()
        assertNotNull(window)
        assertEquals(RATE * 28, window.audio.size)
        assertEquals(RATE * 32, take.retainedSamples)
        assertEquals(RATE * 24, take.remaining)

        take.cancel()
        assertEquals(0, take.retainedSamples)
    }

    @Test
    fun `required seam mismatch aborts instead of guessing`() {
        val harness =
            Harness(RATE * 52) { window, tokens ->
                if (window.required && window.start == RATE * 24) {
                    tokens.indices.forEach {
                        if (tokens[it] == "▁w0051" || tokens[it] == "▁w0052") tokens[it] = "▁changed$it"
                    }
                }
                tokens
            }

        val error = assertFailsWith<IllegalStateException> { harness.run() }

        assertTrue(error.message?.contains("reconcile") == true)
        assertNull(harness.take.next())
    }

    @Test
    fun `ambiguous repeated speech at a required seam aborts`() {
        val harness =
            Harness(RATE * 52) { window, tokens ->
                if (window.required && window.start == RATE * 24) {
                    val withheld = tokens.indexOf("▁w0052")
                    if (withheld >= 0) tokens[withheld] = "▁w0051"
                }
                tokens
            }

        val error = assertFailsWith<IllegalStateException> { harness.run() }

        assertTrue(error.message?.contains("Ambiguous") == true)
    }
}

/**
 * Feeds a take the way Listener's record loop does: 100 ms chunks, a preview at most every
 * 1.1 s, one decode at a time, and the decode tail's extra `next()`.
 */
private class Harness(
    private val total: Int,
    private val transform: (DictationTake.Window, Array<String>) -> Array<String> = { _, tokens -> tokens },
) {
    val take = DictationTake(RATE).apply { commit() }
    val results = mutableListOf<DictationTake.Result>()
    private val chunk = FloatArray(CHUNK)
    private var fed = 0
    private var sinceWindow = 0

    fun run() {
        while (fed < total) {
            val size = minOf(chunk.size, total - fed)
            for (i in 0 until size) chunk[i] = ((fed + i) % 97) / 970f
            take.append(chunk, size)
            fed += size
            sinceWindow += size
            var window = take.next(preview = sinceWindow >= PREVIEW_SAMPLES)
            while (window != null) {
                complete(window)
                window = take.next()
            }
            check(take.retainedSamples <= RATE * 56)
        }
    }

    fun finish() {
        take.release()
        take.endCapture()
        var window = take.next()
        while (window != null) {
            complete(window)
            window = take.next()
        }
    }

    private fun complete(window: DictationTake.Window) {
        val (tokens, timestamps) = windowTokens(window, fed)
        take.complete(window, transform(window, tokens), timestamps)?.let { results += it }
        sinceWindow = 0
    }
}

/** The words actually audible in [window], on the take's global 0.5 s grid. */
private fun windowTokens(
    window: DictationTake.Window,
    available: Int,
): Pair<Array<String>, FloatArray> {
    val end = minOf(available, window.start + window.audio.size)
    var index = (window.start + WORD_STEP - 1) / WORD_STEP
    val tokens = ArrayList<String>()
    val timestamps = ArrayList<Float>()
    while (index * WORD_STEP < end) {
        tokens += "▁w%04d".format(index)
        timestamps += (index * WORD_STEP - window.start).toFloat() / RATE
        index++
    }
    return tokens.toTypedArray() to timestamps.toFloatArray()
}
