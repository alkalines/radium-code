package net.alkalines.radiumcode.agent.runtime

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlFinish
import net.alkalines.radiumcode.agent.il.IlFinishReason
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlStreamEvent
import net.alkalines.radiumcode.agent.il.IlToolCallBlock
import net.alkalines.radiumcode.agent.il.IlToolChoice
import net.alkalines.radiumcode.agent.il.IlTurnStatus
import net.alkalines.radiumcode.agent.il.StreamError
import net.alkalines.radiumcode.agent.il.ToolResultAdded
import net.alkalines.radiumcode.agent.il.TurnStarted
import net.alkalines.radiumcode.agent.providers.ProviderRegistry

data class AgentConfig(
    val maxIterations: Int = 10,
    val defaultMaxOutputTokens: Int? = null,
    val defaultTemperature: Double? = null,
    val toolExecutionTimeoutMs: Long = 30_000L,
)

enum class SubmitPromptResult {
    ACCEPTED,
    BUSY,
    MISSING_PROVIDER_OR_MODEL,
    REJECTED_BLANK,
}

class AgentRuntime(
    private val registry: ProviderRegistry = ProviderRegistry.lazyInstance,
    private val config: AgentConfig = AgentConfig(),
    private val toolExecutor: ToolExecutor = InMemoryToolExecutor(),
    private val toolCatalog: ToolCatalog = EmptyToolCatalog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val reducer = AgentStreamReducer()
    private val stateLock = Any()
    private var currentSession = IlConversationSession()
    private val _session = MutableStateFlow(IlConversationSession())
    private val _state = MutableStateFlow(initialState())
    private var activeJob: Job? = null
    private var sequenceNumber = 0L

    val state: StateFlow<AgentSessionState> = _state.asStateFlow()
    val session: StateFlow<IlConversationSession> = _session.asStateFlow()

    fun selectModel(providerId: String, modelId: String) {
        synchronized(stateLock) {
            val selection = resolveSelection(providerId, modelId)
            val current = _state.value
            publishLocked(
                currentSession,
                current.copy(
                    selectedProviderId = providerId,
                    selectedModelId = modelId,
                    hasUsableSelection = selection != null,
                    inlineError = if (selection == null) "Selected provider or model is unavailable." else null,
                )
            )
        }
    }

    fun submitPrompt(prompt: String): SubmitPromptResult {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            return SubmitPromptResult.REJECTED_BLANK
        }

        var launchRunId: String? = null
        synchronized(stateLock) {
            val current = _state.value
            if (current.activeRunId != null) {
                return SubmitPromptResult.BUSY
            }
            val selection = resolveSelection(current.selectedProviderId, current.selectedModelId)
            if (selection == null) {
                publishLocked(
                    currentSession,
                    current.copy(
                        hasUsableSelection = false,
                        inlineError = "Select a configured provider and model before sending a prompt.",
                    )
                )
                return SubmitPromptResult.MISSING_PROVIDER_OR_MODEL
            }
            val runId = UUID.randomUUID().toString()
            val userTurn = IlConversationTurn.userText("user-${UUID.randomUUID()}", trimmedPrompt, stampMeta(IlMeta.openrouter("user"), 0))
            currentSession = currentSession.copy(turns = currentSession.turns + userTurn)
            launchRunId = runId
            publishLocked(
                currentSession,
                current.copy(
                    session = currentSession,
                    selectedProviderId = selection.providerId,
                    selectedModelId = selection.modelId,
                    activeRunId = runId,
                    activeAssistantTurnId = null,
                    hasUsableSelection = true,
                    inlineError = null,
                )
            )
        }

        val runId = launchRunId ?: return SubmitPromptResult.MISSING_PROVIDER_OR_MODEL
        val job = scope.launch {
            generateLoop(runId, requestIndex = 0)
        }
        synchronized(stateLock) {
            if (_state.value.activeRunId == runId) {
                activeJob = job
            }
        }
        return SubmitPromptResult.ACCEPTED
    }

    fun cancelActiveTurn() {
        val jobToCancel: Job?
        synchronized(stateLock) {
            val current = _state.value
            if (current.activeRunId == null) {
                return
            }
            currentSession = current.activeAssistantTurnId?.let { assistantTurnId ->
                cancelAssistantTurn(currentSession, assistantTurnId)
            } ?: currentSession
            jobToCancel = activeJob
            activeJob = null
            publishLocked(
                currentSession,
                current.copy(
                    session = currentSession,
                    activeRunId = null,
                    activeAssistantTurnId = null,
                    inlineError = null,
                )
            )
        }
        jobToCancel?.cancel(CancellationException("Cancelled by user"))
    }

    private suspend fun generateLoop(runId: String, requestIndex: Int) {
        if (requestIndex >= config.maxIterations) {
            val activeAssistantTurnId = synchronized(stateLock) {
                if (_state.value.activeRunId != runId) {
                    null
                } else {
                    _state.value.activeAssistantTurnId
                }
            } ?: return
            applyEventIfActive(
                runId,
                requestIndex,
                StreamError(
                    eventId = "iteration-limit",
                    turnId = activeAssistantTurnId,
                    message = "Iteration limit reached",
                    meta = IlMeta.openrouter("iteration_limit")
                )
            )
            finishRun(runId)
            return
        }

        val requestContext = synchronized(stateLock) {
            if (_state.value.activeRunId != runId) {
                null
            } else {
                buildRequestContext(requestIndex)
            }
        } ?: return

        try {
            requestContext.provider.stream(requestContext.request).collect { event ->
                applyEventIfActive(runId, requestIndex, event)
            }
        } catch (exception: Exception) {
            val assistantTurnId = synchronized(stateLock) {
                if (_state.value.activeRunId != runId) {
                    null
                } else {
                    _state.value.activeAssistantTurnId ?: "provider-error-${UUID.randomUUID()}".also { turnId ->
                        val started = stampEvent(
                            TurnStarted("provider.exception", turnId, IlRole.ASSISTANT, IlMeta.openrouter("provider.exception")),
                            requestIndex
                        ) as TurnStarted
                        currentSession = reducer.apply(currentSession, started)
                        publishLocked(
                            currentSession,
                            _state.value.copy(
                                session = currentSession,
                                activeAssistantTurnId = turnId,
                            )
                        )
                    }
                }
            } ?: return

            applyEventIfActive(
                runId,
                requestIndex,
                StreamError(
                    eventId = "provider.exception",
                    turnId = assistantTurnId,
                    message = exception.message ?: exception.cause?.message ?: exception.toString(),
                    meta = IlMeta.openrouter("provider.exception")
                )
            )
            finishRun(runId)
            return
        }

        val loopState = synchronized(stateLock) {
            if (_state.value.activeRunId != runId) {
                null
            } else {
                val assistantTurnId = _state.value.activeAssistantTurnId
                val assistantTurn = assistantTurnId?.let { id -> currentSession.turns.firstOrNull { it.id == id } }
                if (assistantTurnId == null || assistantTurn == null) {
                    null
                } else {
                    PostStreamState(
                        assistantTurnId = assistantTurnId,
                        assistantTurn = assistantTurn,
                        allowParallelToolCalls = requestContext.request.allowParallelToolCalls,
                        pendingCalls = reducer.pendingToolCalls(currentSession, assistantTurnId),
                    )
                }
            }
        } ?: return

        if (!loopState.assistantTurn.willContinue) {
            finishRun(runId)
            return
        }
        if (loopState.pendingCalls.isEmpty()) {
            finishRun(runId)
            return
        }

        val results = if (loopState.allowParallelToolCalls) {
            coroutineScope {
                loopState.pendingCalls.map { toolCall ->
                    async {
                        executeTool(toolCall)
                    }
                }.awaitAll()
            }
        } else {
            loopState.pendingCalls.map { executeTool(it) }
        }

        for ((toolCall, outputPayload, isError) in results) {
            val applied = applyEventIfActive(
                runId,
                requestIndex,
                ToolResultAdded(
                    eventId = "tool-result-${toolCall.callId ?: toolCall.id}",
                    turnId = loopState.assistantTurnId,
                    callId = toolCall.callId ?: toolCall.id,
                    toolName = toolCall.toolName ?: "tool",
                    outputPayload = outputPayload,
                    isError = isError,
                    meta = IlMeta.openrouter("tool_result")
                )
            )
            if (!applied) {
                return
            }
        }

        generateLoop(runId, requestIndex + 1)
    }

    private suspend fun executeTool(toolCall: IlToolCallBlock): Triple<IlToolCallBlock, String, Boolean> {
        val result = try {
            withTimeoutOrNull(config.toolExecutionTimeoutMs) {
                toolExecutor.execute(toolCall)
            } ?: ToolExecutionResult("""{"ok":false,"error":"timeout"}""", true)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            ToolExecutionResult(
                buildJsonObject {
                    put("ok", false)
                    put("error", exception.message ?: exception::class.simpleName ?: "tool_execution_failed")
                    put("tool", toolCall.toolName ?: "tool")
                }.toString(),
                true,
            )
        }
        return Triple(toolCall, result.outputPayload, result.isError)
    }

    private fun applyEventIfActive(
        runId: String,
        requestIndex: Int,
        event: IlStreamEvent,
    ): Boolean = synchronized(stateLock) {
        if (_state.value.activeRunId != runId) {
            return@synchronized false
        }
        val stampedEvent = stampEvent(event, requestIndex)
        currentSession = reducer.apply(currentSession, stampedEvent)
        val current = _state.value
        val nextAssistantTurnId = when (stampedEvent) {
            is TurnStarted -> if (stampedEvent.role == IlRole.ASSISTANT) stampedEvent.turnId else current.activeAssistantTurnId
            else -> current.activeAssistantTurnId
        }
        publishLocked(
            currentSession,
            current.copy(
                session = currentSession,
                activeAssistantTurnId = nextAssistantTurnId,
                inlineError = null,
            )
        )
        true
    }

    private fun finishRun(runId: String) {
        synchronized(stateLock) {
            if (_state.value.activeRunId != runId) {
                return
            }
            activeJob = null
            publishLocked(
                currentSession,
                _state.value.copy(
                    session = currentSession,
                    activeRunId = null,
                    activeAssistantTurnId = null,
                    inlineError = null,
                )
            )
        }
    }

    private fun cancelAssistantTurn(
        session: IlConversationSession,
        assistantTurnId: String,
    ): IlConversationSession {
        val assistantIndex = session.turns.indexOfFirst { it.id == assistantTurnId }
        if (assistantIndex == -1) {
            return session
        }
        val updatedTurns = session.turns.toMutableList()
        updatedTurns[assistantIndex] = updatedTurns[assistantIndex].copy(
            status = IlTurnStatus.CANCELLED,
            finish = IlFinish(IlFinishReason.CANCELLED, "cancelled"),
            willContinue = false,
            error = null,
        )
        while (assistantIndex + 1 < updatedTurns.size && updatedTurns[assistantIndex + 1].role == IlRole.TOOL) {
            updatedTurns.removeAt(assistantIndex + 1)
        }
        return session.copy(turns = updatedTurns)
    }

    private fun buildRequestContext(requestIndex: Int): RequestContext {
        val selection = resolveSelection(_state.value.selectedProviderId, _state.value.selectedModelId)
            ?: error("buildRequestContext called without a usable selection")
        val provider = registry.provider(selection.providerId)
        val supportsToolCalling = registry.model(selection.providerId, selection.modelId)
            ?.capabilities
            ?.contains(IlCapability.TOOL_CALLING) == true
        val tools = if (supportsToolCalling) toolCatalog.definitions() else emptyList()
        return RequestContext(
            provider = provider,
            request = IlGenerateRequest(
                providerId = selection.providerId,
                modelId = selection.modelId,
                input = currentSession.turns,
                tools = tools,
                toolChoice = if (tools.isEmpty()) IlToolChoice.None else IlToolChoice.Auto,
                allowParallelToolCalls = tools.isNotEmpty(),
                maxOutputTokens = config.defaultMaxOutputTokens,
                temperature = config.defaultTemperature,
                topP = null,
                stopSequences = emptyList(),
                continuation = null,
                metadata = buildJsonObject { put("requestIndex", requestIndex.toString()) },
                providerOptions = buildJsonObject { },
            )
        )
    }

    private fun resolveSelection(selectedProviderId: String?, selectedModelId: String?): ResolvedSelection? {
        if (selectedProviderId != null || selectedModelId != null) {
            if (selectedProviderId == null || selectedModelId == null) {
                return null
            }
            val selectedModel = registry.model(selectedProviderId, selectedModelId) ?: return null
            val provider = registry.providerOrNull(selectedModel.providerId) ?: return null
            return ResolvedSelection(providerId = provider.providerId, modelId = selectedModel.modelId)
        }
        val defaultModel = registry.defaultModel ?: return null
        val provider = registry.providerOrNull(defaultModel.providerId) ?: return null
        return ResolvedSelection(providerId = provider.providerId, modelId = defaultModel.modelId)
    }

    private fun initialState(): AgentSessionState {
        val defaultModel = registry.defaultModel
        return AgentSessionState(
            session = currentSession,
            selectedProviderId = defaultModel?.providerId,
            selectedModelId = defaultModel?.modelId,
            hasUsableSelection = defaultModel != null,
            inlineError = if (defaultModel == null) "No provider or model is configured." else null,
        )
    }

    private fun publishLocked(session: IlConversationSession, state: AgentSessionState) {
        currentSession = session
        _session.value = session
        _state.value = state.copy(session = session)
    }

    private fun stampEvent(event: IlStreamEvent, requestIndex: Int): IlStreamEvent {
        val meta = stampMeta(event.meta, requestIndex)
        return when (event) {
            is TurnStarted -> event.copy(meta = meta)
            is net.alkalines.radiumcode.agent.il.BlockStarted -> event.copy(meta = meta)
            is net.alkalines.radiumcode.agent.il.TextDelta -> event.copy(meta = meta)
            is net.alkalines.radiumcode.agent.il.ThinkingDelta -> event.copy(meta = meta)
            is net.alkalines.radiumcode.agent.il.RefusalDelta -> event.copy(meta = meta)
            is net.alkalines.radiumcode.agent.il.ToolCallArgumentsDelta -> event.copy(meta = meta)
            is net.alkalines.radiumcode.agent.il.ToolCallCompleted -> event.copy(meta = meta)
            is ToolResultAdded -> event.copy(meta = meta)
            is net.alkalines.radiumcode.agent.il.UsageUpdated -> event.copy(meta = meta)
            is net.alkalines.radiumcode.agent.il.TurnCompleted -> event.copy(meta = meta)
            is StreamError -> event.copy(meta = meta)
        }
    }

    private fun stampMeta(meta: IlMeta, requestIndex: Int): IlMeta {
        val providerExtras = meta.providerExtras.toMutableMap()
        providerExtras["requestIndex"] = kotlinx.serialization.json.JsonPrimitive(requestIndex)
        return meta.copy(
            providerExtras = JsonObject(providerExtras),
            sequenceNumber = ++sequenceNumber,
        )
    }

    private data class RequestContext(
        val provider: net.alkalines.radiumcode.agent.providers.AgentProvider,
        val request: IlGenerateRequest,
    )

    private data class ResolvedSelection(
        val providerId: String,
        val modelId: String,
    )

    private data class PostStreamState(
        val assistantTurnId: String,
        val assistantTurn: IlConversationTurn,
        val allowParallelToolCalls: Boolean,
        val pendingCalls: List<IlToolCallBlock>,
    )
}
