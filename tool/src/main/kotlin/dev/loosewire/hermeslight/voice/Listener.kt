/*
 * Copyright 2026 LooseWireDev
 *
 * Push-to-talk capture and windowed decode. The window/stitch policy lives in [DictationTake];
 * this class owns the microphone, the single-flight decode loop and the release/cancel lifecycle.
 * Public methods and callbacks run on the main thread; capture and decoding do not.
 */
package dev.loosewire.hermeslight.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val CAPTURE_RATE = 16_000

/** 100 ms of PCM16 audio per blocking read; also the reusable FloatArray size. */
internal const val CAPTURE_CHUNK = CAPTURE_RATE / 10

/** DictationTake caps a take at five minutes; keep the capture loop on the same clock. */
private const val CAPTURE_SAMPLES = CAPTURE_RATE * 300

/**
 * Holds the microphone open, feeds [DictationTake] with 16 kHz mono PCM16, and transcribes each
 * ready window with [transcribe] one at a time.
 *
 * [start] begins a take, [release] is the only commit point (it commits the take, stops capture and
 * drains the final window), and [cancel] discards everything. A completed take calls [onResult]
 * exactly once with nonempty text and never submits it; the caller owns that decision. Failures of
 * capture, network decoding or stitching discard the take and report [State.Error] with no result.
 *
 * The capture factory, dispatchers and main-thread [dispatch] are injectable so JVM tests can drive
 * audio and callbacks deterministically; production uses [AudioRecord] and the main [Looper].
 */
internal class Listener(
    private val scope: CoroutineScope,
    private val transcribe: suspend (FloatArray) -> String,
    private val onState: (State) -> Unit,
    private val onResult: (String) -> Unit,
    private val captureFactory: () -> PcmCapture = { AudioRecordCapture() },
    private val dispatch: (() -> Unit) -> Unit = { dispatchOnMain(it) },
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    sealed interface State {
        data object Idle : State

        data object Listening : State

        data object Transcribing : State

        data object NoSpeech : State

        data object Error : State
    }

    private val lock = Any()

    /** The take whose callbacks are allowed to land; null after cancel. */
    private var current: Session? = null

    /**
     * Must be called on the main thread. Starts a fresh capture unless one is still active; a
     * finished, failed or cancelled take is discarded first.
     */
    fun start() {
        val previous =
            synchronized(lock) {
                val active = current
                if (active != null && !active.cancelled && !active.failed && !active.terminal) return
                current = null
                active
            }
        previous?.let { shutdown(it) }

        val session = Session()
        synchronized(lock) { current = session }
        publish(session, State.Listening)
        session.decodeJob = scope.launch(decodeDispatcher) { decodeLoop(session) }
        session.captureJob = scope.launch(captureDispatcher) { captureLoop(session) }
    }

    /**
     * Final consent. Idempotent: only the first call after [start] commits, stops capture and lets
     * the final window decode. The state becomes [State.Transcribing] and never changes before the
     * result.
     *
     * The five-minute cap never ends the take: [DictationTake.next] would finalize it and could
     * produce a result before this call, so the cap only stops capture and this release remains the
     * single commit point.
     */
    fun release() {
        val session =
            synchronized(lock) {
                val active = current ?: return
                if (active.cancelled || active.failed || active.released || active.terminal) return
                active.released = true
                active.take.commit()
                active.take.release()
                active
            }
        val capture = synchronized(lock) { session.capture }
        publish(session, State.Transcribing)
        requestStop(capture)
        session.notifications.trySend(Unit)
        // Usually the capture loop calls endCapture() after its last in-flight chunk. When it had
        // already stopped (the 300 s cap), this release is the first chance to end the take.
        if (synchronized(lock) { session.captureDone }) {
            session.take.endCapture()
            session.notifications.trySend(Unit)
        }
    }

    /** Discards the take and stops every job. No state or result callback is emitted afterwards. */
    fun cancel() {
        val session =
            synchronized(lock) {
                val active = current ?: return
                current = null
                active.cancelled = true
                active
            }
        shutdown(session)
    }

    private inner class Session {
        val take = DictationTake(CAPTURE_RATE)
        val notifications = Channel<Unit>(Channel.CONFLATED)
        var capture: PcmCapture? = null

        @Volatile
        var captureJob: Job? = null

        @Volatile
        var decodeJob: Job? = null
        var released = false
        var cancelled = false
        var failed = false
        var terminal = false
        var captureDone = false
        var resultDelivered = false
    }

    private enum class Origin {
        CAPTURE,
        DECODER,
    }

    private suspend fun decodeLoop(session: Session) {
        try {
            while (true) {
                val signal = session.notifications.receiveCatching()
                if (signal.isClosed) return
                if (drain(session)) return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(session, Origin.DECODER)
        }
    }

    /** Drains every ready window, then reports the terminal state once the take has ended. */
    private suspend fun drain(session: Session): Boolean {
        while (true) {
            val window =
                synchronized(lock) {
                    if (!usable(session)) return true
                    session.take.next()
                } ?: break
            val transcript = if (window.audio.isEmpty()) "" else transcribe(window.audio)
            val result =
                synchronized(lock) {
                    if (!usable(session)) return true
                    session.take.complete(window, transcript)
                } ?: break
            if (result.send) {
                // endCapture() is only called after release, so a final window cannot leak early.
                if (result.text.isNotEmpty()) {
                    publishResult(session, result.text)
                    end(session, State.Idle)
                    return true
                }
            }
        }
        return finishIfEnded(session)
    }

    private fun finishIfEnded(session: Session): Boolean {
        val ended =
            synchronized(lock) {
                if (!usable(session) || !session.released) return false
                session.take.isFinished
            }
        if (!ended) return false
        val delivered = synchronized(lock) { session.resultDelivered }
        end(session, if (delivered) State.Idle else State.NoSpeech)
        return true
    }

    private fun end(
        session: Session,
        state: State,
    ) {
        val first =
            synchronized(lock) {
                if (!usable(session) || session.terminal) {
                    false
                } else {
                    session.terminal = true
                    true
                }
            }
        if (!first) return
        session.notifications.close()
        publish(session, state)
    }

    private fun fail(
        session: Session,
        origin: Origin,
    ) {
        val first =
            synchronized(lock) {
                if (!usable(session) || session.terminal) {
                    false
                } else {
                    session.failed = true
                    session.terminal = true
                    session.take.cancel()
                    true
                }
            }
        if (!first) return
        session.notifications.close()
        if (origin == Origin.CAPTURE) session.decodeJob?.cancel() else session.captureJob?.cancel()
        requestStop(synchronized(lock) { session.capture })
        publish(session, State.Error)
    }

    private suspend fun captureLoop(session: Session) {
        var capture: PcmCapture? = null
        try {
            if (!canCapture(session)) return
            val source =
                try {
                    captureFactory()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(session, Origin.CAPTURE)
                    return
                }
            capture = source
            val installed =
                synchronized(lock) {
                    if (usable(session) && !session.released) {
                        session.capture = source
                        true
                    } else {
                        false
                    }
                }
            if (!installed) return
            try {
                source.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(session, Origin.CAPTURE)
                return
            }

            val shorts = ShortArray(CAPTURE_CHUNK)
            val floats = FloatArray(CAPTURE_CHUNK)
            var fed = 0
            while (fed < CAPTURE_SAMPLES) {
                if (!canCapture(session)) break
                val read = source.read(shorts)
                val active = synchronized(lock) { usable(session) }
                if (!active) break
                val released = synchronized(lock) { session.released }
                if (read < 0) {
                    if (!released) fail(session, Origin.CAPTURE)
                    break
                }
                if (read == 0) {
                    if (released) break
                    delay(1)
                    continue
                }
                for (i in 0 until read) floats[i] = shorts[i] / 32768f
                val accepted = append(session, floats, read, fed)
                when (accepted) {
                    STOPPED, CAPPED -> break
                    OVERFLOW -> {
                        fail(session, Origin.CAPTURE)
                        break
                    }
                    else -> {
                        fed += accepted
                        session.notifications.trySend(Unit)
                        if (released || accepted < read || fed >= CAPTURE_SAMPLES) break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(session, Origin.CAPTURE)
        } finally {
            capture?.close()
            val finish =
                synchronized(lock) {
                    if (session.capture === capture) session.capture = null
                    session.captureDone = true
                    session.released && !session.failed && !session.cancelled
                }
            if (finish) {
                session.take.endCapture()
                session.notifications.trySend(Unit)
            }
        }
    }

    /**
     * Appends one whole chunk, or the part that fits before the 300 s cap. A shortfall caused by the
     * decoder backlog, rather than the cap, returns [OVERFLOW] so capture fails instead of truncating.
     */
    private fun append(
        session: Session,
        audio: FloatArray,
        size: Int,
        fed: Int,
    ): Int =
        synchronized(lock) {
            // After release the take is stopped by design; the capture loop still gets to append
            // its last in-flight chunk before it signals endCapture().
            when {
                !usable(session) -> STOPPED
                session.take.isFinished || fed >= CAPTURE_SAMPLES -> CAPPED
                !session.released && !session.take.isRecording -> CAPPED
                else -> {
                    val accepted = minOf(size, CAPTURE_SAMPLES - fed)
                    if (session.take.remaining < accepted) {
                        OVERFLOW
                    } else {
                        session.take.append(audio, accepted)
                        accepted
                    }
                }
            }
        }

    private fun canCapture(session: Session): Boolean = synchronized(lock) { usable(session) && !session.released }

    private fun usable(session: Session): Boolean = current === session && !session.cancelled && !session.failed

    private fun publish(
        session: Session,
        state: State,
    ) {
        if (!isCurrent(session)) return
        val informational = state == State.Listening || state == State.Transcribing
        dispatch {
            val allowed =
                synchronized(lock) {
                    isCurrentLocked(session) &&
                        (!informational || (!session.terminal && !session.failed))
                }
            if (allowed) onState(state)
        }
    }

    private fun publishResult(
        session: Session,
        text: String,
    ) {
        val first =
            synchronized(lock) {
                if (!isCurrentLocked(session) || session.resultDelivered) {
                    false
                } else {
                    session.resultDelivered = true
                    true
                }
            }
        if (!first) return
        dispatch { if (isCurrent(session)) onResult(text) }
    }

    private fun isCurrent(session: Session): Boolean = synchronized(lock) { isCurrentLocked(session) }

    private fun isCurrentLocked(session: Session): Boolean = current === session && !session.cancelled

    private fun shutdown(session: Session) {
        session.notifications.close()
        session.captureJob?.cancel()
        session.decodeJob?.cancel()
        requestStop(synchronized(lock) { session.capture })
        session.take.cancel()
    }

    /** AudioRecord.stop() can block, so it never runs on the caller's (main) thread. */
    private fun requestStop(capture: PcmCapture?) {
        val source = capture ?: return
        // Owner teardown cancels scope immediately after cancel(); stopping must still unblock read.
        scope.launch(Dispatchers.IO + NonCancellable) { source.stop() }
    }

    private companion object {
        const val STOPPED = -1
        const val CAPPED = -2
        const val OVERFLOW = -3
    }
}

/**
 * A blocking 16 kHz mono PCM16 source. [read] returns a positive sample count, 0 when no samples
 * are ready yet, or a negative value when the microphone has failed. [stop] must unblock a pending
 * [read] and be safe from another thread; [close] releases the device exactly once from any thread.
 */
internal interface PcmCapture {
    fun start()

    fun read(destination: ShortArray): Int

    fun stop()

    fun close()
}

/** Production source: Android's voice-recognition input at the rate the dashboard accepts. */
@SuppressLint("MissingPermission")
private class AudioRecordCapture : PcmCapture {
    private val lock = Any()
    private var recorder: AudioRecord? = create()
    private var stopped = false
    private var closed = false

    override fun start() {
        // Serialized with stop()/close(); the blocking read deliberately stays outside the lock.
        synchronized(lock) {
            if (closed || stopped) return
            val record = recorder ?: return
            check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone did not start" }
        }
    }

    override fun read(destination: ShortArray): Int {
        val record =
            synchronized(lock) {
                if (closed || stopped) return -1
                recorder
            } ?: return -1
        return record.read(destination, 0, destination.size)
    }

    override fun stop() {
        val record =
            synchronized(lock) {
                if (closed || stopped) return
                stopped = true
                recorder
            }
        if (record != null) runCatching { record.stop() }
    }

    override fun close() {
        val record =
            synchronized(lock) {
                if (closed) return
                closed = true
                stopped = true
                recorder.also { recorder = null }
            }
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun create(): AudioRecord {
        val minimum =
            AudioRecord.getMinBufferSize(
                CAPTURE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimum > 0) { "microphone unavailable" }
        val buffer = maxOf(minimum * 2, CAPTURE_CHUNK * 2 * 4)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            CAPTURE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buffer,
        )
    }
}

private object MainHandler {
    val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
}

private fun dispatchOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else MainHandler.handler.post(block)
}
