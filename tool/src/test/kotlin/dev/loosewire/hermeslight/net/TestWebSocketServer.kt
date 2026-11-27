/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Test support only.
 */
package dev.loosewire.hermeslight.net

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Loopback WebSocket fixture using only APIs on Android's JVM-test compile classpath.
 *
 * [handler] receives connections in accept order (1-based). Only the first [connections]
 * connections are scripted; later reconnects are accepted and closed so a test can assert that a
 * command was never replayed.
 */
internal class TestWebSocketServer(
    private val connections: Int = 1,
    private val handler: (TestWebSocket, Int) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val failure = AtomicReference<Throwable?>()
    private val accepted = AtomicInteger()
    private val workers =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "hermes-test-ws").apply { isDaemon = true }
        }

    init {
        workers.execute {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: SocketException) {
                        break
                    }
                sockets.add(socket)
                val index = accepted.incrementAndGet()
                workers.execute {
                    try {
                        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                        TestWebSocket.handshake(socket).use { webSocket ->
                            if (index <= connections) {
                                handler(webSocket, index)
                            } else {
                                webSocket.close()
                            }
                        }
                    } catch (_: IOException) {
                        // A canceled client closes its socket before the script finishes.
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    } finally {
                        sockets.remove(socket)
                    }
                }
            }
        }
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.localPort}/"

    override fun close() {
        server.close()
        synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } } }
        workers.shutdownNow()
        failure.get()?.let { throw AssertionError("Test WebSocket handler failed", it) }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 5_000
    }
}

/** One scripted server side of a WebSocket connection. */
internal class TestWebSocket private constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: OutputStream,
) : AutoCloseable {
    /** Read the next text message, answering pings, or null on close/timeout. */
    fun awaitText(timeoutMillis: Int = 2_000): String? {
        socket.soTimeout = timeoutMillis
        val buffer = StringBuilder()
        var started = false
        while (true) {
            val frame =
                try {
                    readFrame()
                } catch (_: SocketTimeoutException) {
                    return null
                } catch (_: IOException) {
                    return null
                } ?: return null
            when (frame.opcode) {
                OPCODE_TEXT -> {
                    buffer.append(frame.payload.decodeToString())
                    started = true
                }

                OPCODE_CONTINUATION -> buffer.append(frame.payload.decodeToString())

                OPCODE_PING -> runCatching { writeFrame(OPCODE_PONG, frame.payload) }

                OPCODE_CLOSE -> {
                    runCatching { writeFrame(OPCODE_CLOSE, EMPTY) }
                    return null
                }

                else -> Unit
            }
            if (started && frame.fin) return buffer.toString()
        }
    }

    fun sendText(text: String) = writeFrame(OPCODE_TEXT, text.encodeToByteArray())

    /** Wait without reading, so a frame sent meanwhile stays queued for [awaitText]. */
    fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            // Test teardown interrupts parked handlers.
        }
    }

    override fun close() {
        runCatching { writeFrame(OPCODE_CLOSE, EMPTY) }
        runCatching { socket.close() }
    }

    private fun readFrame(): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
            length = ((input.read() shl 8) or input.read()).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.read().toLong() }
        }
        require(length in 0..MAX_FRAME_BYTES) { "Test frame too large: $length" }
        val mask = if (masked) input.readNBytes(4) else null
        val payload = input.readNBytes(length.toInt())
        require(payload.size == length.toInt()) { "Truncated WebSocket frame" }
        if (mask != null && mask.size == 4) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
        }
        return Frame(fin, opcode, payload)
    }

    private fun writeFrame(
        opcode: Int,
        payload: ByteArray,
    ) {
        synchronized(output) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size < 65_536 -> {
                    output.write(126)
                    output.write((payload.size shr 8) and 0xFF)
                    output.write(payload.size and 0xFF)
                }

                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) {
                        output.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                    }
                }
            }
            output.write(payload)
            output.flush()
        }
    }

    private class Frame(
        val fin: Boolean,
        val opcode: Int,
        val payload: ByteArray,
    )

    companion object {
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val MAX_FRAME_BYTES = 1_000_000L
        private const val HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val EMPTY = ByteArray(0)

        fun handshake(socket: Socket): TestWebSocket {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: error("Missing WebSocket handshake")
            require(requestLine.startsWith("GET ")) { "Expected a WebSocket GET, got $requestLine" }
            var key: String? = null
            while (true) {
                val line = readLine(input) ?: error("Truncated WebSocket handshake")
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                require(separator > 0) { "Malformed handshake header" }
                if (line.substring(0, separator).equals("Sec-WebSocket-Key", ignoreCase = true)) {
                    key = line.substring(separator + 1).trim()
                }
            }
            val accept =
                Base64.getEncoder().encodeToString(
                    MessageDigest
                        .getInstance("SHA-1")
                        .digest((key.orEmpty() + HANDSHAKE_GUID).toByteArray(Charsets.ISO_8859_1)),
                )
            val output = socket.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
            )
            output.flush()
            return TestWebSocket(socket, input, output)
        }

        private fun readLine(input: InputStream): String? {
            val result = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return result.takeIf { it.isNotEmpty() }?.toString()
                if (byte == '\n'.code) return result.toString().trimEnd('\r')
                result.append(byte.toChar())
            }
        }
    }
}
