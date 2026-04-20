package net.alkalines.radiumcode.agent.runtime

import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlToolChoice
import net.alkalines.radiumcode.agent.il.IlTurnStatus
import net.alkalines.radiumcode.agent.il.StreamError
import net.alkalines.radiumcode.agent.il.ToolResultAdded
import net.alkalines.radiumcode.agent.providers.ProviderRegistry

data class AgentConfig(
    val maxIterations: Int = 10,
    val defaultMaxOutputTokens: Int? = null,
    val defaultTemperature: Double? = null,
    val toolExecutionTimeoutMs: Long = 30_000L,
)

class AgentRuntime(
    private val registry: ProviderRegistry = ProviderRegistry.lazyInstance,
    private val config: AgentConfig = AgentConfig(),
    private val toolExecutor: ToolExecutor = InMemoryToolExecutor(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor(
        registry: ProviderRegistry = ProviderRegistry.lazyInstance,
        config: AgentConfig = AgentConfig(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(registry = registry, config = config, toolExecutor = InMemoryToolExecutor(), scope = scope)

    private val reducer = AgentStreamReducer()
    private val _session = MutableStateFlow(IlConversationSession())
    private val _state = MutableStateFlow(
        AgentSessionState(
            session = IlConversationSession(),
            selectedProviderId = registry.defaultModel?.providerId,
            selectedModelId = registry.defaultModel?.modelId,
        )
    )
    val state: StateFlow<AgentSessionState> = _state.asStateFlow()
    val session: StateFlow<IlConversationSession> = _session.asStateFlow()

    private var activeJob: Job? = null

    fun submitPrompt(prompt: String) {
        val userTurn = IlConversationTurn.userText("user-${UUID.randomUUID()}", prompt)
        reducer.session = reducer.session.copy(turns = reducer.session.turns + userTurn)
        publishState()
        activeJob = scope.launch {
            generateLoop(iteration = 0)
        }
    }

    fun cancelActiveTurn() {
        activeJob?.cancel()
        val current = reducer.session.turns.lastOrNull()
        if (current != null) {
            reducer.apply(StreamError("cancel-${current.id}", current.id, "Cancelled", IlMeta.openrouter("cancelled")))
        }
        reducer.session = reducer.session.copy(
            turns = reducer.session.turns.mapIndexed { index, turn ->
                if (index == reducer.session.turns.lastIndex) turn.copy(status = net.alkalines.radiumcode.agent.il.IlTurnStatus.CANCELLED) else turn
            }
        )
        publishState()
    }

    private suspend fun generateLoop(iteration: Int) {
        if (iteration >= config.maxIterations) {
            val lastTurn = reducer.session.turns.lastOrNull() ?: return
            reducer.apply(StreamError("iteration-limit", lastTurn.id, "Iteration limit reached", IlMeta.openrouter("iteration_limit")))
            _session.value = reducer.session
            return
        }
        val model = registry.defaultModel ?: return
        val provider = registry.provider(model.providerId)
        val generateRequest = IlGenerateRequest(
            providerId = model.providerId,
            modelId = model.modelId,
            input = reducer.session.turns,
            tools = emptyList(),
            toolChoice = IlToolChoice.Auto,
            allowParallelToolCalls = true,
            maxOutputTokens = config.defaultMaxOutputTokens,
            temperature = config.defaultTemperature,
            topP = null,
            stopSequences = emptyList(),
            continuation = null,
            metadata = buildJsonObject { },
            providerOptions = buildJsonObject { },
        )
        try {
            provider.stream(generateRequest).collect { event ->
                reducer.apply(event)
                publishState()
            }
        } catch (exception: Exception) {
            val assistantTurnId = reducer.session.turns.lastOrNull { it.role != net.alkalines.radiumcode.agent.il.IlRole.USER && it.status == IlTurnStatus.IN_PROGRESS }?.id
                ?: "openrouter-turn-${UUID.randomUUID()}"
            if (reducer.session.turns.none { it.id == assistantTurnId }) {
                reducer.apply(net.alkalines.radiumcode.agent.il.TurnStarted("provider.exception", assistantTurnId, net.alkalines.radiumcode.agent.il.IlRole.ASSISTANT, IlMeta.openrouter("provider.exception")))
            }
            reducer.apply(
                StreamError(
                    eventId = "provider.exception",
                    turnId = assistantTurnId,
                    message = exception.message ?: exception.cause?.message ?: exception.toString(),
                    meta = IlMeta.openrouter("provider.exception")
                )
            )
            publishState()
            return
        }
        val lastTurn = reducer.session.turns.lastOrNull() ?: return
        if (!lastTurn.willContinue) {
            return
        }
        val pendingCalls = lastTurn.blocks.filterIsInstance<net.alkalines.radiumcode.agent.il.IlToolCallBlock>().filter { toolCall ->
            reducer.session.turns.flatMap { it.blocks }
                .filterIsInstance<net.alkalines.radiumcode.agent.il.IlToolResultBlock>()
                .none { it.callId == toolCall.callId }
        }
        val results = if (pendingCalls.isEmpty()) {
            emptyList()
        } else if (generateRequest.allowParallelToolCalls) {
            coroutineScope {
                pendingCalls.map { toolCall ->
                    async {
                        executeTool(toolCall)
                    }
                }.awaitAll()
            }
        } else {
            pendingCalls.map { executeTool(it) }
        }
        results.forEach { (toolCall, resultJson, isError) ->
            reducer.apply(
                ToolResultAdded(
                    eventId = "tool-result-${toolCall.callId}",
                    turnId = lastTurn.id,
                    callId = toolCall.callId ?: UUID.randomUUID().toString(),
                    toolName = toolCall.toolName ?: "tool",
                    resultJson = resultJson,
                    isError = isError,
                    meta = IlMeta.openrouter("tool_result")
                )
            )
            publishState()
        }
        generateLoop(iteration + 1)
    }

    private suspend fun executeTool(toolCall: net.alkalines.radiumcode.agent.il.IlToolCallBlock): Triple<net.alkalines.radiumcode.agent.il.IlToolCallBlock, String, Boolean> {
        val result = withTimeoutOrNull(config.toolExecutionTimeoutMs) {
            toolExecutor.execute(toolCall)
        } ?: ToolExecutionResult("""{"ok":false,"error":"timeout"}""", true)
        return Triple(toolCall, result.resultJson, result.isError)
    }

    private fun publishState() {
        _session.value = reducer.session
        _state.value = _state.value.copy(session = reducer.session)
    }
}
