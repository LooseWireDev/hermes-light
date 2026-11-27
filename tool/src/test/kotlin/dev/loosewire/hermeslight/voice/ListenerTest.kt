/*
 * Copyright 2026 LooseWireDev
 *
 * Deterministic JVM tests for the push-to-talk Listener: a fake blocking PCM source and direct
 * callbacks replace AudioRecord and the main Looper.
 */
package dev.loosewire.hermeslight.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ListenerTest {
    @Test
    fun `empty take is no speech and never a result`() =
        runBlocking {
            val events = Events()
            val listener = listener(this, events, { FakeCapture() }) { "unused" }

            listener.start()
            listener.release()

            await { events.states.any { it == Listener.State.NoSpeech } }
            assertTrue(events.results.isEmpty())
            assertEquals(0, events.transcribeCalls.get())
            assertTrue(events.states.none { it == Listener.State.Error })
            listener.cancel()
        }

    @Test
    fun `empty dashboard transcript after captured audio is no speech`() =
        runBlocking {
            val events = Events()
            val capture = FakeCapture(chunks(1.0))
            val listener = listener(this, events, { capture }) { "" }

            listener.start()
            assertTrue(capture.consumed.await(5, TimeUnit.SECONDS))
            listener.release()

            await { events.states.contains(Listener.State.NoSpeech) }
            assertEquals(1, events.transcribeCalls.get())
            assertTrue(events.results.isEmpty())
            assertTrue(events.states.none { it == Listener.State.Error })
            listener.cancel()
        }

    @Test
    fun `release commits once and delivers one result`() =
        runBlocking {
            val events = Events()
            val capture = FakeCapture(chunks(1.0))
            val listener = listener(this, events, { capture }) { "hello" }

            listener.start()
            assertTrue(capture.consumed.await(5, TimeUnit.SECONDS))
            listener.release()
            listener.release()

            await { events.results.isNotEmpty() }
            assertEquals(listOf("hello"), events.results)
            assertEquals(1, events.transcribeCalls.get())
            await { events.states.contains(Listener.State.Idle) }
            assertTrue(events.states.contains(Listener.State.Transcribing))
            assertTrue(events.states.none { it == Listener.State.NoSpeech })
            assertTrue(capture.closedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1, capture.closeCount())
            listener.cancel()
        }

    @Test
    fun `cancel drops the in-flight result and a restart ignores it`() =
        runBlocking {
            val events = Events()
            val gate = CompletableDeferred<String>()
            val calls = AtomicInteger()
            val captures = CopyOnWriteArrayList<FakeCapture>()
            val listener =
                Listener(
                    scope = this,
                    transcribe = {
                        if (calls.incrementAndGet() == 1) gate.await() else "fresh"
                    },
                    onState = { events.states += it },
                    onResult = { events.results += it },
                    captureFactory = { FakeCapture(chunks(1.0)).also { captures += it } },
                    dispatch = { it() },
                    captureDispatcher = Dispatchers.IO,
                    decodeDispatcher = Dispatchers.IO,
                )

            listener.start()
            await { captures.size == 1 }
            assertTrue(captures[0].consumed.await(5, TimeUnit.SECONDS))
            listener.release()
            await { calls.get() == 1 }

            listener.cancel()
            listener.start()
            await { captures.size == 2 && captures[1].consumed.count == 0L }
            listener.release()

            await { events.results.isNotEmpty() }
            assertEquals(listOf("fresh"), events.results)
            gate.complete("stale")
            Thread.sleep(100)
            assertEquals(listOf("fresh"), events.results)
            listener.cancel()
        }

    @Test
    fun `failed later window reports error without a partial result`() =
        runBlocking {
            val events = Events()
            val calls = AtomicInteger()
            val capture = FakeCapture(chunks(60.0), paceMs = 2)
            val listener =
                listener(this, events, { capture }) {
                    if (calls.incrementAndGet() == 1) "first" else error("dashboard down")
                }

            listener.start()
            await { events.states.any { it == Listener.State.Error } }

            assertEquals(2, calls.get())
            assertTrue(events.results.isEmpty())
            Thread.sleep(100)
            assertTrue(events.results.isEmpty())
            assertTrue(events.states.none { it == Listener.State.NoSpeech })
            listener.cancel()
        }

    @Test
    fun `decoder backlog overflow is an error instead of a truncated take`() =
        runBlocking {
            val events = Events()
            val gate = CompletableDeferred<String>()
            val capture = FakeCapture(chunks(90.0), paceMs = 2)
            val listener = listener(this, events, { capture }) { gate.await() }

            listener.start()
            await { events.states.any { it == Listener.State.Error } }

            assertTrue(events.results.isEmpty())
            Thread.sleep(100)
            assertTrue(events.results.isEmpty())
            listener.cancel()
        }

    private fun listener(
        scope: CoroutineScope,
        events: Events,
        captureFactory: () -> PcmCapture,
        transcribe: suspend (FloatArray) -> String,
    ): Listener =
        Listener(
            scope = scope,
            transcribe = { audio ->
                events.transcribeCalls.incrementAndGet()
                transcribe(audio)
            },
            onState = { events.states += it },
            onResult = { events.results += it },
            captureFactory = captureFactory,
            dispatch = { it() },
            captureDispatcher = Dispatchers.IO,
            decodeDispatcher = Dispatchers.IO,
        )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timed out")
    }
}

private class Events {
    val states = CopyOnWriteArrayList<Listener.State>()
    val results = CopyOnWriteArrayList<String>()
    val transcribeCalls = AtomicInteger()
}

/** A blocking microphone: returns queued chunks, then waits until stopped or closed. */
private class FakeCapture(
    private val chunks: List<ShortArray> = emptyList(),
    private val paceMs: Long = 0,
) : PcmCapture {
    private val lock = Object()
    private var index = 0
    private var stopped = false
    private var closedFlag = false
    private var closedCount = 0
    val consumed = CountDownLatch(if (chunks.isEmpty()) 0 else 1)
    val closedLatch = CountDownLatch(1)

    fun closeCount(): Int = synchronized(lock) { closedCount }

    override fun start() = Unit

    override fun read(destination: ShortArray): Int {
        synchronized(lock) {
            while (!stopped && !closedFlag) {
                if (index < chunks.size) {
                    val chunk = chunks[index++]
                    chunk.copyInto(destination, 0, 0, minOf(chunk.size, destination.size))
                    if (index == chunks.size) consumed.countDown()
                    if (paceMs > 0) Thread.sleep(paceMs)
                    return chunk.size
                }
                lock.wait()
            }
        }
        return -1
    }

    override fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closedFlag) return
            closedFlag = true
            stopped = true
            closedCount++
            lock.notifyAll()
        }
        closedLatch.countDown()
    }
}

private fun chunks(seconds: Double): List<ShortArray> {
    val count = (seconds * CAPTURE_RATE / CAPTURE_CHUNK).toInt()
    val chunk = ShortArray(CAPTURE_CHUNK) { 1024 }
    return List(count) { chunk }
}
