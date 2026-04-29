package net.alkalines.radiumcode.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.alkalines.radiumcode.agent.config.ProviderSettings
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlModelSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OpenRouterCatalogTest {

    @Test
    fun `declares settings fields required by the configuration UI`() {
        val provider = OpenRouterProvider()

        assertEquals(
            listOf(
                ProviderSettingKeys.API_KEY,
                ProviderSettingKeys.USE_CUSTOM_BASE_URL,
                ProviderSettingKeys.BASE_URL,
            ),
            provider.settingsFields.map { it.key },
        )
        assertEquals(ProviderSettingFieldKind.PASSWORD, provider.settingsFields[0].kind)
        assertEquals(ProviderSettingKeys.USE_CUSTOM_BASE_URL to "true", provider.settingsFields[2].visibleWhen)
    }

    @Test
    fun `parses context length max output tokens and pricing from catalog payload`() {
        val provider = OpenRouterProvider()

        val models = provider.parseCatalog(SAMPLE_CATALOG)

        assertEquals(2, models.size)
        val gpt = models.first { it.modelId == "openai/gpt-5.4" }
        assertEquals(1_050_000L, gpt.maxInputTokens)
        assertEquals(128_000L, gpt.maxOutputTokens)
        assertEquals(0.0000025, gpt.inputPricePerToken)
        assertEquals(0.000015, gpt.outputPricePerToken)
        assertEquals(0.00000025, gpt.cacheReadPricePerToken)
        assertNull(gpt.cacheWritePricePerToken)
        assertTrue(IlCapability.TOOL_CALLING in gpt.capabilities)
        assertTrue(IlCapability.THINKING in gpt.capabilities)
        assertEquals(IlModelSource.CATALOG, gpt.source)
    }

    @Test
    fun `derives capabilities from supported parameters`() {
        val provider = OpenRouterProvider()

        val models = provider.parseCatalog(SAMPLE_CATALOG)
        val basic = models.first { it.modelId == "vendor/basic" }

        assertTrue(IlCapability.TEXT in basic.capabilities)
        assertTrue(IlCapability.STREAMING in basic.capabilities)
        assertTrue(IlCapability.TOOL_CALLING !in basic.capabilities)
        assertTrue(IlCapability.THINKING !in basic.capabilities)
    }

    @Test
    fun `tolerates missing top_provider and missing pricing fields`() {
        val provider = OpenRouterProvider()

        val models = provider.parseCatalog(SAMPLE_CATALOG)
        val basic = models.first { it.modelId == "vendor/basic" }

        assertNull(basic.maxOutputTokens)
        assertNull(basic.inputPricePerToken)
        assertNull(basic.cacheReadPricePerToken)
        assertNotNull(basic.maxInputTokens)
    }

    @Test
    fun `fetches catalog over http via models endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(SAMPLE_CATALOG)
            )
            val provider = OpenRouterProvider(
                modelsUrlOverride = server.url("/api/v1/models"),
                apiKeyOverride = "test-key",
            )

            val result = provider.fetchAvailableModels(ProviderSettings("openrouter", apiKey = "test-key"))

            assertTrue(result.isSuccess)
            assertEquals(2, result.getOrThrow().size)
            val recorded = server.takeRequest()
            assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        }
    }

    @Test
    fun `custom base url appends models endpoint for catalog refresh`() = runBlocking {
        var capturedUrl: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedUrl = chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(SAMPLE_CATALOG.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val provider = OpenRouterProvider(httpClient = client)

        val result = provider.fetchAvailableModels(
            ProviderSettings(
                providerId = "openrouter",
                apiKey = "test-key",
                useCustomBaseUrl = true,
                baseUrl = "https://proxy.example/api/v1",
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("https://proxy.example/api/v1/models", capturedUrl)
    }

    @Test
    fun `custom base url appends responses endpoint for streaming`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n")
            )
            var capturedConfiguredModelId: String? = null
            val provider = OpenRouterProvider(
                settingsLookup = { configuredModelId ->
                    capturedConfiguredModelId = configuredModelId
                    ProviderSettings(
                        providerId = "openrouter",
                        configuredModelId = configuredModelId,
                        apiKey = "test-key",
                        useCustomBaseUrl = true,
                        baseUrl = server.url("/api/v1").toString().removeSuffix("/"),
                    )
                }
            )

            provider.stream(request()).collect { }

            assertEquals("/api/v1/responses", server.takeRequest().path)
            assertEquals("id", capturedConfiguredModelId)
        }
    }

    @Test
    fun `catalog http client uses finite read timeout`() {
        assertTrue(openRouterCatalogHttpClient().readTimeoutMillis > 0)
    }

    private companion object {
        val SAMPLE_CATALOG = """
            {
              "data": [
                {
                  "id": "openai/gpt-5.4",
                  "name": "OpenAI: GPT-5.4",
                  "context_length": 1050000,
                  "pricing": {
                    "prompt": "0.0000025",
                    "completion": "0.000015",
                    "input_cache_read": "0.00000025"
                  },
                  "top_provider": {
                    "context_length": 1050000,
                    "max_completion_tokens": 128000,
                    "is_moderated": false
                  },
                  "supported_parameters": ["tools", "tool_choice", "reasoning", "max_tokens"]
                },
                {
                  "id": "vendor/basic",
                  "name": "Vendor Basic",
                  "context_length": 32000,
                  "pricing": {},
                  "supported_parameters": ["max_tokens"]
                }
              ]
            }
        """.trimIndent()

        fun request() = net.alkalines.radiumcode.agent.il.IlGenerateRequest(
            model = net.alkalines.radiumcode.agent.il.IlModelDescriptor(
                id = "id",
                providerId = "openrouter",
                modelId = "openai/gpt-5.4",
                displayName = "GPT",
                maxInputTokens = null,
                maxOutputTokens = null,
                inputPricePerToken = null,
                outputPricePerToken = null,
                cacheReadPricePerToken = null,
                cacheWritePricePerToken = null,
                capabilities = setOf(IlCapability.TEXT, IlCapability.STREAMING),
                reasoningEffort = null,
                source = IlModelSource.MANUAL,
            ),
            input = listOf(net.alkalines.radiumcode.agent.il.IlConversationTurn.userText("user-1", "hello")),
            tools = emptyList(),
            toolChoice = net.alkalines.radiumcode.agent.il.IlToolChoice.None,
            allowParallelToolCalls = false,
            maxOutputTokens = null,
            temperature = null,
            topP = null,
            stopSequences = emptyList(),
            continuation = null,
            metadata = kotlinx.serialization.json.buildJsonObject { },
            providerOptions = kotlinx.serialization.json.buildJsonObject { },
        )
    }
}
