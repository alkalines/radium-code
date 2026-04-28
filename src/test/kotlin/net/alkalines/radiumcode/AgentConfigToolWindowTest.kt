package net.alkalines.radiumcode

import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import net.alkalines.radiumcode.agent.config.AgentModelConfigStore
import net.alkalines.radiumcode.agent.config.ProviderApiKeyStore
import net.alkalines.radiumcode.agent.config.ProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlModelSource
import net.alkalines.radiumcode.agent.il.IlReasoningEffort

class AgentConfigToolWindowTest {

    @Test
    fun `matches query case insensitively against display name and model id`() {
        val model = descriptor(modelId = "minimax/minimax-m2.7", displayName = "MiniMax M2.7")

        assertTrue(AgentConfigToolWindowContentModel.matchesQuery(model, "MINI"))
        assertTrue(AgentConfigToolWindowContentModel.matchesQuery(model, "m2.7"))
        assertFalse(AgentConfigToolWindowContentModel.matchesQuery(model, "claude"))
    }

    @Test
    fun `offers custom entry when query has no exact catalog match`() {
        val catalog = listOf(descriptor(modelId = "openai/gpt-5.4"))

        val filtered = catalog.filter { AgentConfigToolWindowContentModel.matchesQuery(it, "minimax") }

        assertTrue(AgentConfigToolWindowContentModel.shouldOfferCustomEntry("minimax", filtered))
    }

    @Test
    fun `does not offer custom entry when query exactly matches a catalog model id`() {
        val catalog = listOf(descriptor(modelId = "openai/gpt-5.4"))

        val filtered = catalog.filter { AgentConfigToolWindowContentModel.matchesQuery(it, "openai/gpt-5.4") }

        assertFalse(AgentConfigToolWindowContentModel.shouldOfferCustomEntry("openai/gpt-5.4", filtered))
    }

    @Test
    fun `model form derives capabilities from supports tools and supports reasoning checkboxes`() {
        val state = ModelFormState.empty(defaultProviderId = "openrouter").copy(
            modelId = "openai/gpt-5.4",
            displayName = "GPT 5.4",
            supportsTools = true,
            supportsReasoning = true,
            reasoningEffort = IlReasoningEffort.HIGH,
        )

        val descriptor = state.toDescriptor()

        assertTrue(IlCapability.TOOL_CALLING in descriptor.capabilities)
        assertTrue(IlCapability.THINKING in descriptor.capabilities)
        assertEquals(IlReasoningEffort.HIGH, descriptor.reasoningEffort)
    }

    @Test
    fun `model form converts edited per million prices into descriptor per token prices`() {
        val state = ModelFormState.empty(defaultProviderId = "openrouter").copy(
            modelId = "minimax/minimax-m2.7",
            inputPrice = "0.3",
            outputPrice = "1.2",
            cacheReadPrice = "0.059",
            cacheWritePrice = "",
        )

        val descriptor = state.toDescriptor()

        assertEquals(0.0000003, descriptor.inputPricePerToken)
        assertEquals(0.0000012, descriptor.outputPricePerToken)
        assertEquals(0.000000059, descriptor.cacheReadPricePerToken)
        assertEquals(null, descriptor.cacheWritePricePerToken)
    }

    @Test
    fun `model form displays descriptor per token prices as per million prices`() {
        val state = ModelFormState.from(
            descriptor(modelId = "minimax/minimax-m2.7").copy(
                inputPricePerToken = 0.0000003,
                outputPricePerToken = 0.0000012,
                cacheReadPricePerToken = 0.000000059,
            )
        )

        assertEquals("0.3", state.inputPrice)
        assertEquals("1.2", state.outputPrice)
        assertEquals("0.059", state.cacheReadPrice)
    }

    @Test
    fun `catalog model selection displays per token prices as per million prices`() {
        val selected = descriptor(modelId = "minimax/minimax-m2.7").copy(
            inputPricePerToken = 0.0000003,
            outputPricePerToken = 0.0000012,
            cacheReadPricePerToken = 0.000000059,
        )
        val state = ModelFormState.empty(defaultProviderId = "openrouter")

        val updated = state.withCatalogSelection(selected)

        assertEquals("0.3", updated.inputPrice)
        assertEquals("1.2", updated.outputPrice)
        assertEquals("0.059", updated.cacheReadPrice)
    }

    @Test
    fun `model form drops reasoning effort when supports reasoning is unchecked`() {
        val state = ModelFormState.empty(defaultProviderId = "openrouter").copy(
            modelId = "openai/gpt-5.4",
            supportsReasoning = false,
            reasoningEffort = IlReasoningEffort.HIGH,
        )

        val descriptor = state.toDescriptor()

        assertEquals(null, descriptor.reasoningEffort)
        assertFalse(IlCapability.THINKING in descriptor.capabilities)
    }

    @Test
    fun `model form is invalid without provider or model id`() {
        val state = ModelFormState.empty(defaultProviderId = "")
        assertFalse(state.isValid())

        val withoutProvider = state.copy(modelId = "openai/gpt-5.4")
        assertFalse(withoutProvider.isValid())

        val withoutModel = state.copy(providerId = "openrouter")
        assertFalse(withoutModel.isValid())

        val complete = state.copy(providerId = "openrouter", modelId = "openai/gpt-5.4")
        assertTrue(complete.isValid())
    }

    @Test
    fun `programmatic model id updates do not reopen model dropdown`() {
        assertFalse(
            AgentConfigToolWindowContentModel.shouldExpandModelDropdown(
                previousText = "",
                nextText = "openai/gpt-5.4",
                changeOrigin = ModelSearchTextChangeOrigin.PROGRAMMATIC,
            )
        )
    }

    @Test
    fun `typed model id updates open model dropdown`() {
        assertTrue(
            AgentConfigToolWindowContentModel.shouldExpandModelDropdown(
                previousText = "",
                nextText = "openai",
                changeOrigin = ModelSearchTextChangeOrigin.USER,
            )
        )
    }

    @Test
    fun `catalog refresh ignores stale results`() {
        val tracker = CatalogRefreshTracker()
        val first = tracker.nextRequest()
        val second = tracker.nextRequest()

        assertFalse(tracker.accepts(first))
        assertTrue(tracker.accepts(second))
    }

    @Test
    fun `provider settings save runs on supplied background dispatcher`() {
        val tmpDir = Files.createTempDirectory("radium-provider-save-test")
        val apiKeyStore = RecordingProviderApiKeyStore()
        val store = AgentModelConfigStore(
            databasePath = tmpDir.resolve("db.sql"),
            apiKeyStore = apiKeyStore,
            initializeSynchronously = true,
        )
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "provider-save-background")
        }
        val dispatcher = executor.asCoroutineDispatcher()

        try {
            runBlocking {
                launchProviderSettingsSave(
                    scope = this,
                    store = store,
                    settings = ProviderSettings(
                        providerId = "openrouter",
                        apiKey = "sk-test",
                        useCustomBaseUrl = false,
                        baseUrl = null,
                    ),
                    dispatcher = dispatcher,
                ).join()
            }

            assertTrue(apiKeyStore.setThreadName?.startsWith("provider-save-background") == true)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
            store.dispose()
            tmpDir.toFile().deleteRecursively()
        }
    }

    private fun descriptor(modelId: String, displayName: String = modelId) = IlModelDescriptor(
        id = "id-$modelId",
        providerId = "openrouter",
        modelId = modelId,
        displayName = displayName,
        maxInputTokens = null,
        maxOutputTokens = null,
        inputPricePerToken = null,
        outputPricePerToken = null,
        cacheReadPricePerToken = null,
        cacheWritePricePerToken = null,
        capabilities = setOf(IlCapability.TEXT, IlCapability.STREAMING),
        reasoningEffort = null,
        source = IlModelSource.CATALOG,
    )

    private class RecordingProviderApiKeyStore : ProviderApiKeyStore {
        var setThreadName: String? = null

        override fun get(providerId: String): String? = null

        override fun set(providerId: String, apiKey: String?) {
            setThreadName = Thread.currentThread().name
        }
    }
}
