package net.alkalines.radiumcode.agent.providers

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlFinishReason
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingBlock
import net.alkalines.radiumcode.agent.il.IlThinkingVisibility
import net.alkalines.radiumcode.agent.il.IlTurnError
import net.alkalines.radiumcode.agent.il.IlTurnStatus
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import net.alkalines.radiumcode.agent.il.StreamError
import net.alkalines.radiumcode.agent.il.TurnCompleted
import net.alkalines.radiumcode.agent.il.TurnStarted

class OpenRouterProviderTest {

    @Test
    fun `uses an sse http client without read timeout`() {
        val client = openRouterHttpClient()

        assertEquals(0, client.readTimeoutMillis)
    }

    @Test
    fun `returns stream error without http when api key is missing`() = runBlocking {
        val provider = OpenRouterProvider(
            baseUrl = "https://openrouter.ai/api/v1/responses".toHttpUrl(),
            apiKeyOverride = "",
        )

        val events = provider.stream(request()).toList()

        assertTrue(events.first() is TurnStarted)
        assertTrue(events.last() is StreamError)
    }

    @Test
    fun `sends full history with assistant id status reasoning enabled and captures generation id`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .addHeader("X-Generation-Id", "gen-123")
                    .setBody(
                        """
                        : OPENROUTER PROCESSING
                        data: {"type":"response.created","response":{"id":"resp_1"}}

                        data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[{"type":"output_text","text":""}]}}

                        data: {"type":"response.output_text.delta","output_index":0,"item_id":"msg_1","content_index":0,"delta":"Hello"}

                        data: {"type":"response.output_text.done","output_index":0,"item_id":"msg_1","content_index":0,"text":"Hello"}

                        data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[{"id":"msg_1","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}],"usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()
            val recorded = server.takeRequest()
            val requestJson = Json.parseToJsonElement(recorded.body.readUtf8()).toString()

            assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
            assertContains(requestJson, "\"model\":\"z-ai/glm-4.5-air:free\"")
            assertContains(requestJson, "\"reasoning\":{\"enabled\":true}")
            assertContains(requestJson, "\"status\":\"completed\"")
            assertContains(requestJson, "\"id\":\"assistant-1\"")
            assertTrue(events.any { it is net.alkalines.radiumcode.agent.il.TurnStarted })
            assertTrue(events.any { event ->
                event.meta.providerExtras.toString().contains("gen-123")
            })
        }
    }

    @Test
    fun `maps incomplete as non error and ignores sse comments`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        : OPENROUTER PROCESSING
                        data: {"type":"response.created","response":{"id":"resp_2"}}

                        data: {"type":"response.incomplete","response":{"id":"resp_2","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()

            val completed = events.filterIsInstance<TurnCompleted>().single()

            assertEquals(IlFinishReason.MAX_TOKENS, completed.finishReason)
            assertFalse(events.any { it is StreamError })
        }
    }

    @Test
    fun `uses raw http error body when structured error message is missing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"detail":"rate limit exceeded for this model"}""")
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()
            val error = events.filterIsInstance<StreamError>().single()

            assertContains(error.message, "rate limit exceeded")
        }
    }

    @Test
    fun `uses raw stream payload when sse error message is missing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"type":"response.created","response":{"id":"resp_3"}}

                        data: {"type":"error","detail":"upstream overloaded"}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()
            val error = events.filterIsInstance<StreamError>().single()

            assertContains(error.message, "upstream overloaded")
        }
    }

    @Test
    fun `streams first events before terminal event arrives`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .throttleBody(128, 1, TimeUnit.SECONDS)
                    .setBody(
                        """
                        data: {"type":"response.created","response":{"id":"resp_stream"}}

                        data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_stream","type":"message","status":"in_progress","role":"assistant","content":[{"type":"output_text","text":""}]}}

                        data: {"type":"response.output_text.delta","output_index":0,"item_id":"msg_stream","content_index":0,"delta":"Hi"}

                        data: {"type":"response.completed","response":{"id":"resp_stream","status":"completed","output":[{"id":"msg_stream","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Hi"}]}]}}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val firstEvents = withTimeout(2600) {
                provider.stream(request()).take(1).toList()
            }

            assertTrue(firstEvents.first() is TurnStarted)
        }
    }

    @Test
    fun `ignores reasoning items when reconciling completed output`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"type":"response.created","response":{"id":"resp_reasoning"}}

                        data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_reasoning","type":"message","status":"in_progress","role":"assistant","content":[]}}

                        data: {"type":"response.output_text.delta","output_index":1,"item_id":"msg_reasoning","content_index":0,"delta":"Oi"}

                        data: {"type":"response.output_text.done","output_index":1,"item_id":"msg_reasoning","content_index":0,"text":"Oi"}

                        data: {"type":"response.completed","response":{"id":"resp_reasoning","status":"completed","output":[{"id":"rs_1","type":"reasoning","status":"completed","content":[{"type":"reasoning_text","text":"hidden"}]},{"id":"msg_reasoning","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Oi"}]}]}}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()

            assertTrue(events.any { it is TurnCompleted })
            assertFalse(events.any { it is StreamError })
        }
    }

    @Test
    fun `ignores non object content parts when reconciling completed output`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"type":"response.created","response":{"id":"resp_mixed_content"}}

                        data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_mixed","type":"message","status":"in_progress","role":"assistant","content":[]}}

                        data: {"type":"response.output_text.delta","output_index":0,"item_id":"msg_mixed","content_index":0,"delta":"Oi"}

                        data: {"type":"response.output_text.done","output_index":0,"item_id":"msg_mixed","content_index":0,"text":"Oi"}

                        data: {"type":"response.completed","response":{"id":"resp_mixed_content","status":"completed","output":[{"id":"rs_1","type":"reasoning","status":"completed","content":[{"type":"reasoning_text","text":"hidden"},[]]},{"id":"msg_mixed","type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Oi"}]}]}}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()

            assertTrue(events.any { it is TurnCompleted })
            assertFalse(events.any { it is StreamError })
        }
    }

    @Test
    fun `skips sse payloads that are not json objects without aborting the stream`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"type":"response.created","response":{"id":"resp_skip"}}

                        data: [DONE]

                        data: {"type":"response.completed","response":{"id":"resp_skip","status":"completed","output":[]}}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()

            assertTrue(events.any { it is TurnCompleted })
            assertFalse(events.any { it is StreamError })
        }
    }

    @Test
    fun `tolerates response completed payload with non object usage`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"type":"response.created","response":{"id":"resp_bad_usage"}}

                        data: {"type":"response.completed","response":{"id":"resp_bad_usage","status":"completed","output":[],"usage":[]}}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()

            assertTrue(events.any { it is TurnCompleted })
            assertFalse(events.any { it is StreamError })
        }
    }

    @Test
    fun `tolerates response incomplete payload with non object incomplete_details`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"type":"response.created","response":{"id":"resp_bad_incomplete"}}

                        data: {"type":"response.incomplete","response":{"id":"resp_bad_incomplete","status":"incomplete","incomplete_details":[]}}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()
            val completed = events.filterIsInstance<TurnCompleted>().single()

            assertEquals(IlFinishReason.OTHER, completed.finishReason)
            assertFalse(events.any { it is StreamError })
        }
    }

    @Test
    fun `tolerates error payload where error field is an array`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"type":"response.created","response":{"id":"resp_bad_error"}}

                        data: {"type":"error","error":[]}

                        """.trimIndent()
                    )
            )

            val provider = OpenRouterProvider(
                baseUrl = server.url("/api/v1/responses"),
                apiKeyOverride = "test-key",
            )

            val events = provider.stream(request()).toList()

            assertTrue(events.any { it is StreamError })
        }
    }

    @Test
    fun `does not serialize assistant thinking blocks back into request history`() {
        val provider = OpenRouterProvider(
            baseUrl = "https://openrouter.ai/api/v1/responses".toHttpUrl(),
            apiKeyOverride = "test-key",
        )

        val request = request(
            assistantBlocks = listOf(
                IlThinkingBlock(
                    id = "thinking-1",
                    text = "internal reasoning",
                    visibility = IlThinkingVisibility.FULL,
                    referencePayload = JsonArray(emptyList()),
                    status = net.alkalines.radiumcode.agent.il.IlBlockStatus.COMPLETED,
                    meta = IlMeta.openrouter("thinking"),
                ),
                IlTextBlock.completed(id = "text-1", text = "Previous")
            )
        )

        val body = provider.buildRequestBody(request).toString()

        assertContains(body, "\"id\":\"assistant-1\"")
        assertContains(body, "\"status\":\"completed\"")
        assertContains(body, "\"type\":\"output_text\"")
        assertFalse(body.contains("\"type\":\"reasoning\""))
    }

    @Test
    fun `skips failed assistant turns with empty content when serializing history`() {
        val provider = OpenRouterProvider(
            baseUrl = "https://openrouter.ai/api/v1/responses".toHttpUrl(),
            apiKeyOverride = "test-key",
        )

        val request = net.alkalines.radiumcode.agent.il.IlGenerateRequest(
            providerId = "openrouter",
            modelId = "z-ai/glm-4.5-air:free",
            input = listOf(
                IlConversationTurn.userText(id = "user-1", text = "first"),
                IlConversationTurn(
                    id = "assistant-error",
                    role = IlRole.ASSISTANT,
                    blocks = emptyList(),
                    status = IlTurnStatus.FAILED,
                    usage = null,
                    finish = null,
                    willContinue = false,
                    meta = IlMeta.openrouter("provider.exception"),
                    error = IlTurnError("timeout")
                ),
                IlConversationTurn.userText(id = "user-2", text = "second"),
            ),
            tools = emptyList(),
            toolChoice = net.alkalines.radiumcode.agent.il.IlToolChoice.Auto,
            allowParallelToolCalls = true,
            maxOutputTokens = 128,
            temperature = null,
            topP = null,
            stopSequences = emptyList(),
            continuation = null,
            metadata = buildJsonObject { put("source", "test") },
            providerOptions = buildJsonObject { }
        )

        val body = provider.buildRequestBody(request).toString()

        assertContains(body, "\"text\":\"first\"")
        assertContains(body, "\"text\":\"second\"")
        assertFalse(body.contains("assistant-error"))
        assertFalse(body.contains("\"content\":[]"))
    }

    private fun request(
        assistantBlocks: List<net.alkalines.radiumcode.agent.il.IlBlock> = listOf(IlTextBlock.completed(id = "text-1", text = "Previous"))
    ) = net.alkalines.radiumcode.agent.il.IlGenerateRequest(
        providerId = "openrouter",
        modelId = "z-ai/glm-4.5-air:free",
        input = listOf(
            IlConversationTurn.userText(id = "user-1", text = "Hello"),
            IlConversationTurn(
                id = "assistant-1",
                role = IlRole.ASSISTANT,
                blocks = assistantBlocks,
                status = net.alkalines.radiumcode.agent.il.IlTurnStatus.COMPLETED,
                usage = null,
                finish = null,
                willContinue = false,
                meta = IlMeta.openrouter("history")
            )
        ),
        tools = emptyList(),
        toolChoice = net.alkalines.radiumcode.agent.il.IlToolChoice.Auto,
        allowParallelToolCalls = true,
        maxOutputTokens = 128,
        temperature = null,
        topP = null,
        stopSequences = emptyList(),
        continuation = null,
        metadata = buildJsonObject { put("source", "test") },
        providerOptions = buildJsonObject { }
    )
}
