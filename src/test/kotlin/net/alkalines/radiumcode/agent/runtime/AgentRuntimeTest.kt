package net.alkalines.radiumcode.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.alkalines.radiumcode.agent.il.BlockStarted
import net.alkalines.radiumcode.agent.il.IlBlockKind
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlTextBlock
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
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            config = AgentConfig(maxIterations = 3, toolExecutionTimeoutMs = 1000L),
        )

        runtime.submitPrompt("hello")
        delay(50)

        assertEquals(2, provider.requests.size)
        assertEquals(IlRole.USER, runtime.session.value.turns.first().role)
        assertTrue(runtime.session.value.turns.last().blocks.any { it is IlTextBlock })
    }

    @Test
    fun `surfaces provider exception message in session state`() = runBlocking {
        val provider = FailingProvider()
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        runtime.submitPrompt("hello")
        delay(50)

        val assistantTurn = runtime.session.value.turns.last()
        assertTrue(assistantTurn.error?.message?.contains("OpenRouter 429 rate limited") == true)
    }

    @Test
    fun `falls back to exception cause when provider exception message is null`() = runBlocking {
        val provider = NullMessageFailingProvider()
        val runtime = AgentRuntime(
            registry = ProviderRegistry.fromProviders(listOf(provider)),
            config = AgentConfig(maxIterations = 1, toolExecutionTimeoutMs = 1000L),
        )

        runtime.submitPrompt("hello")
        delay(50)

        val assistantTurn = runtime.session.value.turns.last()
        assertTrue(assistantTurn.error?.message?.contains("OpenRouter body says quota exceeded") == true)
    }

    private class RecordingProvider : AgentProvider() {
        val requests = mutableListOf<IlGenerateRequest>()

        override val providerId = "openrouter"
        override val displayName = "OpenRouter"
        override val models = listOf(
            IlModelDescriptor(
                providerId = providerId,
                modelId = "z-ai/glm-4.5-air:free",
                displayName = "OpenRouter",
                capabilities = setOf(IlCapability.TEXT, IlCapability.THINKING, IlCapability.TOOL_CALLING, IlCapability.STREAMING),
                isDefault = true
            )
        )

        override fun stream(request: IlGenerateRequest): Flow<net.alkalines.radiumcode.agent.il.IlStreamEvent> = flow {
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

        override fun supports(modelId: String) = modelId == "z-ai/glm-4.5-air:free"
    }

    private class FailingProvider : AgentProvider() {
        override val providerId = "openrouter"
        override val displayName = "OpenRouter"
        override val models = listOf(
            IlModelDescriptor(
                providerId = providerId,
                modelId = "z-ai/glm-4.5-air:free",
                displayName = "OpenRouter",
                capabilities = setOf(IlCapability.TEXT, IlCapability.THINKING, IlCapability.STREAMING),
                isDefault = true
            )
        )

        override fun stream(request: IlGenerateRequest): Flow<net.alkalines.radiumcode.agent.il.IlStreamEvent> = flow {
            emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
            throw IllegalStateException("OpenRouter 429 rate limited")
        }
    }

    private class NullMessageFailingProvider : AgentProvider() {
        override val providerId = "openrouter"
        override val displayName = "OpenRouter"
        override val models = listOf(
            IlModelDescriptor(
                providerId = providerId,
                modelId = "z-ai/glm-4.5-air:free",
                displayName = "OpenRouter",
                capabilities = setOf(IlCapability.TEXT, IlCapability.THINKING, IlCapability.STREAMING),
                isDefault = true
            )
        )

        override fun stream(request: IlGenerateRequest): Flow<net.alkalines.radiumcode.agent.il.IlStreamEvent> = flow {
            emit(TurnStarted("turn-1", "turn-1", IlRole.ASSISTANT, meta()))
            throw RuntimeException(null, IllegalStateException("OpenRouter body says quota exceeded"))
        }
    }

    private companion object {
        fun meta() = IlMeta.openrouter("test")
    }
}
