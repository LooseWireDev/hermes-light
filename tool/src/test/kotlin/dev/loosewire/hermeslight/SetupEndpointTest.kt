package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.FileProfileStorage
import dev.loosewire.hermeslight.data.KeystoreCipher
import dev.loosewire.hermeslight.data.ProfileStore
import dev.loosewire.hermeslight.net.Endpoint
import dev.loosewire.hermeslight.ui.SetupUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SetupEndpointTest {
    @Test
    fun `untouched first run submits a valid displayed endpoint without saving it`() =
        runBlocking {
            val directory = createTempDirectory("hermes-setup").toFile()
            val store = ProfileStore(FileProfileStorage(directory), KeystoreCipher())
            val controller = AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            try {
                controller.resume()
                val state = withTimeout(2_000) { controller.state.first { it.initialized } }
                val form = SetupUi(endpoint = state.endpoint)
                assertEquals("http://127.0.0.1:9121/", Endpoint.parse(form.endpoint).baseUrl)
                assertFalse(state.configured)
                assertNull(store.load())
            } finally {
                controller.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun `Setup default is an editable address and explicit deletion is still invalid`() {
        val form = SetupUi()
        assertEquals("http://127.0.0.1:9121/", Endpoint.parse(form.endpoint).baseUrl)
        val edited = form.copy(endpoint = "https://hermes.example/dashboard")
        assertEquals("https://hermes.example/dashboard/", Endpoint.parse(edited.endpoint).baseUrl)
        assertFailsWith<IllegalArgumentException> { Endpoint.parse(form.copy(endpoint = "").endpoint) }
    }
}
