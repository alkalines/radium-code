package net.alkalines.radiumcode.agent.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.alkalines.radiumcode.agent.config.AgentModelConfigStore
import net.alkalines.radiumcode.agent.config.ProviderApiKeyStore
import net.alkalines.radiumcode.agent.config.ProviderSettings
import net.alkalines.radiumcode.agent.il.BlockStarted
import net.alkalines.radiumcode.agent.il.IlBlockKind
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlModelSource
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlStreamEvent
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlToolDefinition
import net.alkalines.radiumcode.agent.il.IlTurnStatus
import net.alkalines.radiumcode.agent.il.TextDelta
import net.alkalines.radiumcode.agent.il.ToolCallCompleted
import net.alkalines.radiumcode.agent.il.TurnCompleted
import net.alkalines.radiumcode.agent.il.TurnStarted
import net.alkalines.radiumcode.agent.providers.AgentProvider
import net.alkalines.radiumcode.agent.providers.ProviderRegistry

class AgentRuntimeTest {

    @Test
    fun `creates user turn and loops after tool call`() = runBlocking {
        val provider = RecordingProvider()
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free", toolCalling = true)
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 3, toolExecutionTimeoutMs = 1000L),
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        delay(50)

        assertEquals(2, provider.requests.size)
        assertEquals(IlRole.USER, runtime.session.value.turns.first().role)
        assertTrue(runtime.session.value.turns.last().blocks.any { it is IlTextBlock })
    }

    @Test
    fun `surfaces provider exception message in session state`() = runBlocking {
        val provider = FailingProvider()
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free")
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        delay(50)

        val assistantTurn = runtime.session.value.turns.last()
        assertTrue(assistantTurn.error?.message?.contains("OpenRouter 429 rate limited") == true)
    }

    @Test
    fun `falls back to exception cause when provider exception message is null`() = runBlocking {
        val provider = NullMessageFailingProvider()
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free")
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        delay(50)

        val assistantTurn = runtime.session.value.turns.last()
        assertTrue(assistantTurn.error?.message?.contains("OpenRouter body says quota exceeded") == true)
    }

    @Test
    fun `marks session as streaming before the first provider event arrives`() = runBlocking {
        val provider = DelayedProvider(delayMs = 250)
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free")
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))

        assertTrue(runtime.state.value.isStreaming)
    }

    @Test
    fun `ignores a second submit while a run is already active`() = runBlocking {
        val provider = DelayedProvider(delayMs = 250)
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free")
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        assertEquals(SubmitPromptResult.BUSY, runtime.submitPrompt("again"))
        delay(50)

        assertEquals(1, provider.requests.size)
        assertEquals(1, runtime.session.value.turns.count { it.role == IlRole.USER })
    }

    @Test
    fun `drops late tool results after cancellation during tool execution`() = runBlocking {
        val provider = ToolCallingProvider()
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free", toolCalling = true)
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 3, toolExecutionTimeoutMs = 1000L),
            toolExecutor = ToolExecutor {
                delay(250)
                ToolExecutionResult("""{"ok":true}""", false)
            },
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        delay(50)
        runtime.cancelActiveTurn()
        delay(350)

        assertFalse(runtime.session.value.turns.any { it.role == IlRole.TOOL })
        assertEquals(IlTurnStatus.CANCELLED, runtime.session.value.turns.last().status)
    }

    @Test
    fun `drops completed tool turns when cancelling before the follow up assistant turn starts`() = runBlocking {
        val provider = ToolThenDelayedFollowupProvider(delayMs = 500)
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free", toolCalling = true)
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 3, toolExecutionTimeoutMs = 1000L),
            toolExecutor = ToolExecutor {
                ToolExecutionResult("""{"ok":true}""", false)
            },
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        delay(100)
        assertTrue(runtime.session.value.turns.any { it.role == IlRole.TOOL })

        runtime.cancelActiveTurn()
        delay(50)

        assertFalse(runtime.session.value.turns.any { it.role == IlRole.TOOL })
        assertEquals(IlTurnStatus.CANCELLED, runtime.session.value.turns.last().status)
    }

    @Test
    fun `drops late provider events after cancellation`() = runBlocking {
        val provider = StartedThenDelayedProvider(delayMs = 250)
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free")
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        val cancelledRunId = runtime.state.value.activeRunId
        delay(50)
        runtime.cancelActiveTurn()

        val applied = applyEventIfActive(
            runtime = runtime,
            runId = checkNotNull(cancelledRunId),
            requestIndex = 0,
            event = TurnCompleted(
                eventId = "late-completed",
                turnId = "turn-1",
                finishReason = net.alkalines.radiumcode.agent.il.IlFinishReason.STOP,
                rawReason = "completed",
                willContinue = false,
                meta = meta()
            )
        )

        assertFalse(applied)
        assertEquals(IlTurnStatus.CANCELLED, runtime.session.value.turns.last().status)
    }

    @Test
    fun `submit fails with missing provider error when no model is configured`() = runBlocking {
        val provider = RecordingProvider()
        val store = emptyConfigStore()
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        assertFalse(runtime.state.value.hasUsableSelection)
        assertEquals(SubmitPromptResult.MISSING_PROVIDER_OR_MODEL, runtime.submitPrompt("hello"))
        assertTrue(runtime.state.value.inlineError?.contains("No configured model") == true)
    }

    @Test
    fun `selectModel updates state from store`() = runBlocking {
        val provider = RecordingProvider()
        val (store, descriptor) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free")
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        runtime.selectModel(descriptor.id)

        assertEquals(descriptor.id, runtime.state.value.selectedModel?.id)
        assertTrue(runtime.state.value.hasUsableSelection)
    }

    @Test
    fun `selectModel does not persist a missing configured model id`() = runBlocking {
        val provider = RecordingProvider()
        val (store, descriptor) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free")
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        runtime.selectModel("missing-model")

        assertEquals(descriptor.id, store.lastSelectedModelId.value)
    }

    @Test
    fun `selectModel ignores model switches while a run is active`() = runBlocking {
        val provider = DelayedProvider(delayMs = 250)
        val store = emptyConfigStore()
        val first = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "first-model"))
        val second = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "second-model"))
        store.setLastSelectedModel(first.id)
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        runtime.selectModel(second.id)

        assertEquals(first.id, runtime.state.value.selectedModel?.id)
        assertEquals(first.id, store.lastSelectedModelId.value)
    }

    @Test
    fun `reconcileSelection follows persisted selected model changes`() = runBlocking {
        val provider = RecordingProvider()
        val store = emptyConfigStore()
        val first = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "first-model"))
        val second = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "second-model"))
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        runtime.selectModel(first.id)
        store.setLastSelectedModel(second.id)
        delay(50)

        assertEquals(second.id, runtime.state.value.selectedModel?.id)
        assertTrue(runtime.state.value.hasUsableSelection)
    }

    @Test
    fun `reconcileSelection does not switch models while a run is active`() = runBlocking {
        val provider = DelayedProvider(delayMs = 250)
        val store = emptyConfigStore()
        val first = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "first-model"))
        val second = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "second-model"))
        store.setLastSelectedModel(first.id)
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        store.deleteConfiguredModel(first.id)
        delay(50)

        assertEquals(first.id, runtime.state.value.selectedModel?.id)
        assertEquals(1, provider.requests.size)
        assertEquals("first-model", provider.requests.first().model.modelId)
        assertTrue(store.configuredModels.value.any { it.id == second.id })
    }

    @Test
    fun `reconcileSelection falls back when selected model is deleted`() = runBlocking {
        val provider = RecordingProvider()
        val store = emptyConfigStore()
        val first = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "first-model"))
        val second = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "second-model"))
        store.setLastSelectedModel(first.id)
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        runtime.selectModel(first.id)
        store.deleteConfiguredModel(first.id)
        delay(50)

        assertEquals(second.id, runtime.state.value.selectedModel?.id)
        assertTrue(runtime.state.value.hasUsableSelection)
        assertEquals(null, runtime.state.value.inlineError)
    }

    @Test
    fun `reconcileSelection clears stale no model error after a model is added`() = runBlocking {
        val provider = RecordingProvider()
        val store = emptyConfigStore()
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )
        assertEquals(SubmitPromptResult.MISSING_PROVIDER_OR_MODEL, runtime.submitPrompt("hello"))

        val saved = store.upsertConfiguredModel(modelDescriptor(provider.providerId, "z-ai/glm-4.5-air:free"))
        delay(50)

        assertEquals(saved.id, runtime.state.value.selectedModel?.id)
        assertEquals(null, runtime.state.value.inlineError)
    }

    @Test
    fun `dispose stops runtime model store collection`() = runBlocking {
        val provider = RecordingProvider()
        val store = emptyConfigStore()
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        runtime.dispose()
        store.upsertConfiguredModel(modelDescriptor(provider.providerId, "z-ai/glm-4.5-air:free"))
        delay(50)

        assertEquals(null, runtime.state.value.selectedModel)
    }

    @Test
    fun `tool executor exceptions do not leave the runtime stuck as busy`() = runBlocking {
        val provider = RecordingProvider()
        val (store, _) = configStoreWithModel(provider.providerId, "z-ai/glm-4.5-air:free", toolCalling = true)
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 3, toolExecutionTimeoutMs = 1000L),
            toolExecutor = ToolExecutor {
                throw IllegalStateException("Tool exploded")
            },
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        delay(100)

        assertFalse(runtime.state.value.isStreaming)
        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("again"))
    }

    @Test
    fun `does not send tools when the selected model lacks tool calling capability`() = runBlocking {
        val provider = TextOnlyProvider()
        val (store, _) = configStoreWithModel(provider.providerId, "text-only", toolCalling = false)
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            configStore = store,
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
            toolCatalog = ToolCatalog {
                listOf(
                    IlToolDefinition(
                        name = "lookup",
                        description = "Lookup data",
                        inputSchema = kotlinx.serialization.json.buildJsonObject {
                            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                        }
                    )
                )
            },
        )

        assertEquals(SubmitPromptResult.ACCEPTED, runtime.submitPrompt("hello"))
        delay(50)

        val request = provider.requests.single()
        assertTrue(request.tools.isEmpty())
        assertFalse(request.allowParallelToolCalls)
        assertEquals(net.alkalines.radiumcode.agent.il.IlToolChoice.None, request.toolChoice)
    }

    private abstract class FakeProvider(
        override val providerId: String = "openrouter",
        override val displayName: String = "OpenRouter",
    ) : AgentProvider() {
        override suspend fun fetchAvailableModels(settings: ProviderSettings): Result<List<IlModelDescriptor>> =
            Result.success(emptyList())
    }

    private class RecordingProvider : FakeProvider() {
        val requests = mutableListOf<IlGenerateRequest>()

        override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
            requests += request
            emit(TurnStarted("turn-${requests.size}", "turn-${requests.size}", IlRole.ASSISTANT, meta()))
            if (requests.size == 1) {
                emit(BlockStarted("block-tool", "turn-1", "block-tool", IlBlockKind.TOOL_CALL, null, "lookup", "call-1", meta()))
                emit(ToolCallCompleted("tool-done", "turn-1", "block-tool", meta()))
                emit(TurnCompleted("turn-complete-1", "turn-1", net.alkalines.radiumcode.agent.il.IlFinishReason.TOOL_CALL, "tool", true, meta()))
            } else {
                emit(BlockStarted("block-text", "turn-2", "block-text", IlBlockKind.TEXT, null, null, null, meta()))
                emit(TextDelta("delta-1", "turn-2", "block-text", "done", meta()))
                emit(TurnCompleted("turn-complete-2", "turn-2", net.alkalines.radiumcode.agent.il.IlFinishReason.STOP, "completed", false, meta()))
            }
        }
    }

    private class FailingProvider : FakeProvider() {
        override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
            emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
            throw IllegalStateException("OpenRouter 429 rate limited")
        }
    }

    private class NullMessageFailingProvider : FakeProvider() {
        override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
            emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
            throw RuntimeException(null, IllegalStateException("OpenRouter body says quota exceeded"))
        }
    }

    private class DelayedProvider(private val delayMs: Long) : FakeProvider() {
        val requests = mutableListOf<IlGenerateRequest>()

        override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
            requests += request
            delay(delayMs)
            emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
            emit(TurnCompleted("turn-complete-1", "turn-1", net.alkalines.radiumcode.agent.il.IlFinishReason.STOP, "completed", false, meta()))
        }
    }

    private class ToolCallingProvider : FakeProvider() {
        override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
            emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
            emit(BlockStarted("block-tool", "turn-1", "block-tool", IlBlockKind.TOOL_CALL, null, "lookup", "call-1", meta()))
            emit(ToolCallCompleted("tool-done", "turn-1", "block-tool", meta()))
            emit(TurnCompleted("turn-complete-1", "turn-1", net.alkalines.radiumcode.agent.il.IlFinishReason.TOOL_CALL, "tool", true, meta()))
        }
    }

    private class StartedThenDelayedProvider(private val delayMs: Long) : FakeProvider() {
        override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
            emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
            delay(delayMs)
            emit(TextDelta("late-delta", "turn-1", "block-text", "ignored", meta()))
            emit(TurnCompleted("late-complete", "turn-1", net.alkalines.radiumcode.agent.il.IlFinishReason.STOP, "completed", false, meta()))
        }
    }

    private class ToolThenDelayedFollowupProvider(private val delayMs: Long) : FakeProvider() {
        val requests = mutableListOf<IlGenerateRequest>()

        override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
            requests += request
            if (requests.size == 1) {
                emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
                emit(BlockStarted("block-tool", "turn-1", "block-tool", IlBlockKind.TOOL_CALL, null, "lookup", "call-1", meta()))
                emit(ToolCallCompleted("tool-done", "turn-1", "block-tool", meta()))
                emit(TurnCompleted("turn-complete-1", "turn-1", net.alkalines.radiumcode.agent.il.IlFinishReason.TOOL_CALL, "tool", true, meta()))
            } else {
                delay(delayMs)
                emit(TurnStarted("turn-2", "turn-2", IlRole.ASSISTANT, meta()))
                emit(TurnCompleted("turn-complete-2", "turn-2", net.alkalines.radiumcode.agent.il.IlFinishReason.STOP, "completed", false, meta()))
            }
        }
    }

    private class TextOnlyProvider : FakeProvider() {
        val requests = mutableListOf<IlGenerateRequest>()

        override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
            requests += request
            emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
            emit(TurnCompleted("turn-complete-1", "turn-1", net.alkalines.radiumcode.agent.il.IlFinishReason.STOP, "completed", false, meta()))
        }
    }

    private companion object {
        fun meta() = IlMeta.openrouter("test")

        fun emptyConfigStore(): AgentModelConfigStore {
            val tmpDir = Files.createTempDirectory("radium-test")
            tmpDir.toFile().deleteOnExit()
            return AgentModelConfigStore(
                databasePath = tmpDir.resolve("db.sql"),
                apiKeyStore = InMemoryProviderApiKeyStore(),
                initializeSynchronously = true,
            )
        }

        fun configStoreWithModel(
            providerId: String,
            modelId: String,
            toolCalling: Boolean = false,
        ): Pair<AgentModelConfigStore, IlModelDescriptor> {
            val store = emptyConfigStore()
            val descriptor = modelDescriptor(providerId, modelId, toolCalling)
            val saved = store.upsertConfiguredModel(descriptor)
            store.setLastSelectedModel(saved.id)
            return store to saved
        }

        fun modelDescriptor(
            providerId: String,
            modelId: String,
            toolCalling: Boolean = false,
        ): IlModelDescriptor {
            val capabilities = buildSet {
                add(IlCapability.TEXT)
                add(IlCapability.STREAMING)
                add(IlCapability.THINKING)
                if (toolCalling) add(IlCapability.TOOL_CALLING)
            }
            return IlModelDescriptor(
                id = "",
                providerId = providerId,
                modelId = modelId,
                displayName = modelId,
                maxInputTokens = null,
                maxOutputTokens = null,
                inputPricePerToken = null,
                outputPricePerToken = null,
                cacheReadPricePerToken = null,
                cacheWritePricePerToken = null,
                capabilities = capabilities,
                reasoningEffort = null,
                source = IlModelSource.MANUAL,
            )
        }

        fun applyEventIfActive(
            runtime: AgentRuntime,
            runId: String,
            requestIndex: Int,
            event: IlStreamEvent,
        ): Boolean {
            val method = AgentRuntime::class.java.getDeclaredMethod(
                "applyEventIfActive",
                String::class.java,
                Integer.TYPE,
                IlStreamEvent::class.java,
            )
            method.isAccessible = true
            return method.invoke(runtime, runId, requestIndex, event) as Boolean
        }

        class InMemoryProviderApiKeyStore : ProviderApiKeyStore {
            private val keys = mutableMapOf<String, String>()

            override fun get(providerId: String): String? = keys[providerId]

            override fun set(providerId: String, apiKey: String?) {
                if (apiKey == null) {
                    keys.remove(providerId)
                } else {
                    keys[providerId] = apiKey
                }
            }
        }
    }
}
