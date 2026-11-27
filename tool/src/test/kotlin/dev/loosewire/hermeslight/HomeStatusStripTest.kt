package dev.loosewire.hermeslight

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.ui.HomeStatusStrip
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeStatusStripTest {
    @Test
    fun `idle strip emits no layout nodes so it occupies zero height`() {
        // Observe actual composition, not just the model's null flag. Any Spacer, label or wrapper
        // would emit a node (or require a UI applier) and fail this Android-free regression test.
        var emittedNodes = 0
        val applier =
            object : AbstractApplier<Any>(Unit) {
                override fun insertTopDown(
                    index: Int,
                    instance: Any,
                ) {
                    emittedNodes++
                }

                override fun insertBottomUp(
                    index: Int,
                    instance: Any,
                ) {
                    emittedNodes++
                }

                override fun move(
                    from: Int,
                    to: Int,
                    count: Int,
                ) = error("Idle strip must not move layout nodes")

                override fun remove(
                    index: Int,
                    count: Int,
                ) = error("Idle strip must not remove layout nodes")

                override fun onClear() {
                    emittedNodes = 0
                }
            }
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(applier, recomposer)
        try {
            val running = AppState(liveSessions = setOf("a"))
            val pending = reduceGatewayEvent(AppState(), GatewayEvent.ApprovalPending("a"))
            val idleStates =
                listOf(
                    AppState(),
                    reduceGatewayEvent(running, GatewayEvent.Done("a")),
                    reduceGatewayEvent(pending, GatewayEvent.ApprovalCancelled("a")),
                )
            for (state in idleStates) {
                for (focused in listOf(false, true)) {
                    composition.setContent {
                        HomeStatusStrip(state.statusStripUi(), focused = focused) { error("Idle strip cannot activate") }
                    }
                    assertEquals(0, emittedNodes, "Idle strip must reserve no layout height even with stale focus")
                }
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }
}
