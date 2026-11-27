/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Tests for foreground-only dialing and dial cancellation.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

class GatewayLifecycleTest {
    @Test
    fun `background cancels the credential fetch before a socket exists`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val gateway = Gateway(scope, NoopListener)
                gateway.connect(
                    connection(provider(entered, cancelled)),
                )
                withTimeout(TEST_TIMEOUT) { entered.await() }

                gateway.onForeground(false)

                withTimeout(TEST_TIMEOUT) { cancelled.await() }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a new connect cancels the previous credential fetch`() =
        runBlocking {
            val firstEntered = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val secondCancelled = CompletableDeferred<Unit>()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val gateway = Gateway(scope, NoopListener)
                gateway.connect(connection(provider(firstEntered, firstCancelled)))
                withTimeout(TEST_TIMEOUT) { firstEntered.await() }

                gateway.connect(connection(provider(secondEntered, secondCancelled)))
                withTimeout(TEST_TIMEOUT) { secondEntered.await() }
                withTimeout(TEST_TIMEOUT) { firstCancelled.await() }

                gateway.disconnect()
                withTimeout(TEST_TIMEOUT) { secondCancelled.await() }
            } finally {
                scope.cancel()
            }
        }

    private fun connection(credentialProvider: suspend () -> String?) =
        GatewayConnection(
            endpoint = Endpoint.parse("http://127.0.0.1:1/"),
            authParam = WsAuthParam.TOKEN,
            credential = "unused",
            credentialProvider = credentialProvider,
        )

    private fun provider(
        entered: CompletableDeferred<Unit>,
        cancelled: CompletableDeferred<Unit>,
    ): suspend () -> String? =
        {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

    private object NoopListener : Gateway.Listener {
        override fun onState(state: GatewayState) = Unit

        override fun onEvent(event: GatewayEvent) = Unit
    }

    private companion object {
        const val TEST_TIMEOUT: Long = 2_000L
    }
}
