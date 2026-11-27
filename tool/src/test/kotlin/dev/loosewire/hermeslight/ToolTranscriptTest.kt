package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.Api
import dev.loosewire.hermeslight.net.Endpoint
import dev.loosewire.hermeslight.net.TestHttpServer
import dev.loosewire.hermeslight.net.newHttpClient
import dev.loosewire.hermeslight.ui.ThreadMessageKind
import dev.loosewire.hermeslight.ui.toolSummary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolTranscriptTest {
    @Test
    fun `REST tool rows render names and outcomes from raw result metadata`() =
        runBlocking {
            TestHttpServer { exchange ->
                exchange.respondJson(
                    200,
                    """
                    {"messages":[
                      {"id":1,"role":"assistant","content":"Reading",
                       "tool_calls":[{"id":"call_read","function":{"name":"read_file"}}]},
                      {"id":2,"role":"tool","tool_call_id":"call_read","content":"{\"ok\":true}"},
                      {"id":3,"role":"tool","tool_call_id":"call_bash","tool_name":"","name":"bash",
                       "display_content":"preview hides the failure","content":{"exit_code":2,"stderr":"not found"}},
                      {"id":4,"role":"tool","tool_call_id":"call_old",
                       "content":{"name":"search","result":{"success":false}}},
                      {"id":5,"role":"tool","tool_call_id":"call_unknown","content":"plain output"},
                      {"id":6,"role":"tool","name":"write_file","content":{"error":"permission denied"}},
                      {"id":7,"role":"tool","name":"read_file",
                       "content":{"is_error":true,"result":{"success":true}}},
                      {"id":8,"role":"tool","name":"bash","content":null},
                      {"id":9,"role":"tool","name":"read_file","content":{"result":null}}
                    ],"total":9}
                    """.trimIndent(),
                )
            }.use { server ->
                val page = Api(newHttpClient()).messages(Endpoint.parse(server.baseUrl), "session")
                val ui = AppState(messages = page.messages).threadUi()
                assertEquals(
                    listOf(
                        "· read_file — ok",
                        "· bash — fail",
                        "· search — fail",
                        "· call_unknown — ok",
                        "· write_file — fail",
                        "· read_file — ok",
                        "· bash — unknown",
                        "· read_file — unknown",
                    ),
                    ui.messages.filter { it.kind == ThreadMessageKind.Tool }.map { it.toolSummary() },
                )
            }
        }

    @Test
    fun `around paging resolves names when the calling assistant arrives in an older page`() =
        runBlocking {
            TestHttpServer { exchange ->
                val messages =
                    if (exchange.requestURI.path.endsWith("/around")) {
                        """[{"id":10,"role":"assistant","content":"Calling",
                          "tool_calls":[{"id":"call_1","function":{"name":"read_file"}}]}]"""
                    } else {
                        """[{"id":11,"role":"tool","tool_call_id":"call_1","content":{"ok":true}}]"""
                    }
                exchange.respondJson(200, """{"messages":$messages}""")
            }.use { server ->
                val api = Api(newHttpClient())
                val endpoint = Endpoint.parse(server.baseUrl)
                val latest = api.messages(endpoint, "session")
                val initial = AppState(messages = latest.messages).threadUi()
                assertEquals("· call_1 — ok", initial.messages.single().toolSummary())
                val older = api.messagesAround(endpoint, "session", rowId = 11)
                val restored = AppState(messages = older.messages + latest.messages).threadUi()
                assertEquals("· read_file — ok", restored.messages.last().toolSummary())
            }
        }
}
