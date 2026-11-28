/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Test support only.
 */
package dev.loosewire.hermeslight.net

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.Collections
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Loopback HTTP fixture using only APIs on Android's JVM-test compile classpath. */
internal class TestHttpServer(
    private val handler: (TestRequest) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val failure = AtomicReference<Throwable?>()
    private val workers =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "hermes-test-http").apply { isDaemon = true }
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
                workers.execute {
                    try {
                        socket.use {
                            it.soTimeout = 2_000
                            handler(TestRequest.read(it))
                        }
                    } catch (_: IOException) {
                        // A canceled client/watchdog closes its socket before a response.
                    } catch (error: InterruptedException) {
                        if (!server.isClosed) failure.compareAndSet(null, error)
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
        synchronized(sockets) { sockets.forEach { it.close() } }
        workers.shutdownNow()
        failure.get()?.let { throw AssertionError("Test dashboard handler failed", it) }
    }
}

internal class TestHeaders {
    private val values = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)

    fun add(
        name: String,
        value: String,
    ) {
        values[name] = value
    }

    fun getFirst(name: String): String? = values[name]
}

internal class TestRequest private constructor(
    val method: String,
    val requestURI: URI,
    val requestHeaders: TestHeaders,
    private val body: String,
    private val socket: Socket,
) {
    fun bodyText(): String = body

    fun respondJson(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) = respond(code, body, "application/json", headers)

    fun respondHtml(
        code: Int,
        body: String,
    ) = respond(code, body, "text/html", emptyMap())

    fun respondEmpty(code: Int) = respond(code, "", "text/plain", emptyMap())

    private fun respond(
        code: Int,
        body: String,
        contentType: String,
        headers: Map<String, String>,
    ) {
        val bytes = body.encodeToByteArray()
        val response =
            buildString {
                append("HTTP/1.1 $code Test\r\n")
                append("Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
        socket.getOutputStream().apply {
            write(response.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    companion object {
        fun read(socket: Socket): TestRequest {
            val input = socket.getInputStream().buffered()
            val first = readLine(input).split(' ')
            require(first.size >= 2) { "Missing request target" }
            val headers = TestHeaders()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                require(split > 0) { "Malformed request header" }
                headers.add(line.substring(0, split), line.substring(split + 1).trim())
            }
            val size = headers.getFirst("Content-Length")?.toInt() ?: 0
            val bytes = input.readNBytes(size)
            require(bytes.size == size) { "Truncated request body" }
            return TestRequest(first[0], URI(first[1]), headers, bytes.decodeToString(), socket)
        }

        private fun readLine(input: InputStream): String {
            val result = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0 || byte == '\n'.code) return result.toString().trimEnd('\r')
                result.append(byte.toChar())
            }
        }
    }
}

internal fun queryParameters(uri: java.net.URI): Map<String, String> =
    uri.rawQuery
        ?.split('&')
        ?.filter { it.isNotEmpty() }
        ?.associate { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) {
                java.net.URLDecoder.decode(pair, Charsets.UTF_8) to ""
            } else {
                java.net.URLDecoder.decode(pair.substring(0, separator), Charsets.UTF_8) to
                    java.net.URLDecoder.decode(pair.substring(separator + 1), Charsets.UTF_8)
            }
        }.orEmpty()
