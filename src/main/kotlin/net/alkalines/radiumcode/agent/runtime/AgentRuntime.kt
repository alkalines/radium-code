package net.alkalines.radiumcode.agent.runtime

import com.intellij.openapi.Disposable
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alkalines.radiumcode.agent.config.AgentModelConfigStore
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlFinish
import net.alkalines.radiumcode.agent.il.IlFinishReason
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
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
    private val configStore: AgentModelConfigStore = AgentModelConfigStore.getInstance(),
    private val config: AgentConfig = AgentConfig(),
    private val toolExecutor: ToolExecutor = InMemoryToolExecutor(),
    private val toolCatalog: ToolCatalog = EmptyToolCatalog,
    externalScope: CoroutineScope? = null,
) : Disposable {
    private val runtimeJob = SupervisorJob()
    private val scope: CoroutineScope = externalScope ?: CoroutineScope(runtimeJob + Dispatchers.Default)
    private val reducer = AgentStreamReducer()
    private val stateLock = Any()
    private var currentSession = IlConversationSession()
    private val _session = MutableStateFlow(IlConversationSession())
    private val _state = MutableStateFlow(initialState())
    private var activeJob: Job? = null
    private var selectionJob: Job? = null
    private var sequenceNumber = 0L

    val state: StateFlow<AgentSessionState> = _state.asStateFlow()
    val session: StateFlow<IlConversationSession> = _session.asStateFlow()

    init {
        selectionJob = scope.launch {
            combine(configStore.configuredModels, configStore.lastSelectedModelId) { models, lastSelectedModelId ->
                models to lastSelectedModelId
            }.collect { (models, lastSelectedModelId) ->
                reconcileSelection(models, lastSelectedModelId)
            }
        }
    }

    fun selectModel(configuredModelId: String) {
        var shouldPersistSelection = false
        synchronized(stateLock) {
            val current = _state.value
            if (current.activeRunId != null) {
                return
            }
            val model = configStore.configuredModel(configuredModelId)
            shouldPersistSelection = model != null && registry.providerOrNull(model.providerId) != null
            publishLocked(
                currentSession,
                current.copy(
                    selectedModel = model,
                    hasUsableSelection = shouldPersistSelection,
                    inlineError = when {
                        model == null -> "Selected model is no longer available."
                        registry.providerOrNull(model.providerId) == null -> "Provider \"${model.providerId}\" is not registered."
                        else -> null
                    },
                )
            )
        }
        if (shouldPersistSelection) {
            configStore.setLastSelectedModel(configuredModelId)
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
            val selectedModel = current.selectedModel
            if (selectedModel == null || registry.providerOrNull(selectedModel.providerId) == null) {
                publishLocked(
                    currentSession,
                    current.copy(
                        hasUsableSelection = false,
                        inlineError = "No configured model. Open Config to add one.",
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
        var currentRequestIndex = requestIndex
        while (true) {
            if (currentRequestIndex >= config.maxIterations) {
                val activeAssistantTurnId = synchronized(stateLock) {
                    if (_state.value.activeRunId != runId) {
                        null
                    } else {
                        _state.value.activeAssistantTurnId
                    }
                } ?: return
                applyEventIfActive(
                    runId,
                    currentRequestIndex,
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
                    buildRequestContext(currentRequestIndex)
                }
            } ?: return

            try {
                requestContext.provider.stream(requestContext.request).collect { event ->
                    applyEventIfActive(runId, currentRequestIndex, event)
                }
            } catch (exception: Exception) {
                val assistantTurnId = synchronized(stateLock) {
                    if (_state.value.activeRunId != runId) {
                        null
                    } else {
                        _state.value.activeAssistantTurnId ?: "provider-error-${UUID.randomUUID()}".also { turnId ->
                            val started = stampEvent(
                                TurnStarted("provider.exception", turnId, IlRole.ASSISTANT, IlMeta.openrouter("provider.exception")),
                                currentRequestIndex
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
                    currentRequestIndex,
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
                    currentRequestIndex,
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

            currentRequestIndex++
        }
    }

    private suspend fun executeTool(toolCall: IlToolCallBlock): Triple<IlToolCallBlock, String, Boolean> {
        val result = try {
            withTimeoutOrNull(config.toolExecutionTimeoutMs.milliseconds) {
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
        reconcileSelection(configStore.configuredModels.value, configStore.lastSelectedModelId.value)
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
        val selectedModel = _state.value.selectedModel
            ?: error("buildRequestContext called without a usable selection")
        val provider = registry.provider(selectedModel.providerId)
        val supportsToolCalling = IlCapability.TOOL_CALLING in selectedModel.capabilities
        val tools = if (supportsToolCalling) toolCatalog.definitions() else emptyList()
        return RequestContext(
            provider = provider,
            request = IlGenerateRequest(
                model = selectedModel,
                input = currentSession.turns,
                tools = tools,
                toolChoice = if (tools.isEmpty()) IlToolChoice.None else IlToolChoice.Auto,
                allowParallelToolCalls = tools.isNotEmpty(),
                maxOutputTokens = config.defaultMaxOutputTokens ?: selectedModel.maxOutputTokens?.toInt(),
                temperature = config.defaultTemperature,
                topP = null,
                stopSequences = emptyList(),
                continuation = null,
                metadata = buildJsonObject { put("requestIndex", requestIndex.toString()) },
                providerOptions = buildJsonObject { },
            )
        )
    }

    private fun reconcileSelection(currentList: List<IlModelDescriptor>, lastSelectedModelId: String? = configStore.lastSelectedModelId.value) {
        synchronized(stateLock) {
            val current = _state.value
            if (current.activeRunId != null) {
                return@synchronized
            }
            val selected = current.selectedModel
            val resolvedSelection: IlModelDescriptor? = when {
                selected != null && selected.id == lastSelectedModelId ->
                    currentList.firstOrNull { it.id == selected.id } ?: fallbackSelection(currentList, lastSelectedModelId)
                else -> fallbackSelection(currentList, lastSelectedModelId)
            }
            val providerAvailable = resolvedSelection?.let { registry.providerOrNull(it.providerId) != null } == true
            publishLocked(
                currentSession,
                current.copy(
                    selectedModel = resolvedSelection,
                    hasUsableSelection = providerAvailable,
                    inlineError = when {
                        resolvedSelection == null -> "No configured model. Open Config to add one."
                        !providerAvailable -> "Provider \"${resolvedSelection.providerId}\" is not registered."
                        else -> null
                    },
                )
            )
        }
    }

    private fun fallbackSelection(currentList: List<IlModelDescriptor>, lastSelectedModelId: String?): IlModelDescriptor? {
        if (currentList.isEmpty()) {
            return null
        }
        return run {
            val byLast = lastSelectedModelId?.let { id -> currentList.firstOrNull { it.id == id } }
            byLast ?: currentList.firstOrNull()
        }
    }

    private fun initialState(): AgentSessionState {
        val models = configStore.configuredModels.value
        val lastId = configStore.lastSelectedModelId.value
        val initial = lastId?.let { id -> models.firstOrNull { it.id == id } } ?: models.firstOrNull()
        val providerAvailable = initial?.let { registry.providerOrNull(it.providerId) != null } == true
        return AgentSessionState(
            session = currentSession,
            selectedModel = initial,
            hasUsableSelection = providerAvailable,
            inlineError = when {
                initial == null -> "No configured model. Open Config to add one."
                !providerAvailable -> "Provider \"${initial.providerId}\" is not registered."
                else -> null
            },
        )
    }

    private fun publishLocked(session: IlConversationSession, state: AgentSessionState) {
        currentSession = session
        _session.value = session
        _state.value = state.copy(session = session)
    }

    override fun dispose() {
        activeJob?.cancel(CancellationException("Runtime disposed"))
        activeJob = null
        selectionJob?.cancel(CancellationException("Runtime disposed"))
        selectionJob = null
        runtimeJob.cancel(CancellationException("Runtime disposed"))
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

    private data class PostStreamState(
        val assistantTurnId: String,
        val assistantTurn: IlConversationTurn,
        val allowParallelToolCalls: Boolean,
        val pendingCalls: List<IlToolCallBlock>,
    )
}
