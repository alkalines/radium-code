package net.alkalines.radiumcode.agent.providers

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.alkalines.radiumcode.agent.config.AgentModelConfigStore
import net.alkalines.radiumcode.agent.config.ProviderSettings
import net.alkalines.radiumcode.agent.il.BlockStarted
import net.alkalines.radiumcode.agent.il.IlBlock
import net.alkalines.radiumcode.agent.il.IlBlockKind
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlFinishReason
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlModelSource
import net.alkalines.radiumcode.agent.il.IlReasoningEffort
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlStreamEvent
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingVisibility
import net.alkalines.radiumcode.agent.il.IlToolCallBlock
import net.alkalines.radiumcode.agent.il.IlToolChoice
import net.alkalines.radiumcode.agent.il.IlToolDefinition
import net.alkalines.radiumcode.agent.il.IlToolResultBlock
import net.alkalines.radiumcode.agent.il.IlTurnStatus
import net.alkalines.radiumcode.agent.il.RefusalDelta
import net.alkalines.radiumcode.agent.il.StreamError
import net.alkalines.radiumcode.agent.il.TextDelta
import net.alkalines.radiumcode.agent.il.ThinkingDelta
import net.alkalines.radiumcode.agent.il.ToolCallArgumentsDelta
import net.alkalines.radiumcode.agent.il.ToolCallCompleted
import net.alkalines.radiumcode.agent.il.TurnCompleted
import net.alkalines.radiumcode.agent.il.TurnStarted
import net.alkalines.radiumcode.agent.il.UsageMergeMode
import net.alkalines.radiumcode.agent.il.UsageUpdated
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val OPENROUTER_PROVIDER_ID = "openrouter"
private const val OPENROUTER_DEFAULT_RESPONSES_URL = "https://openrouter.ai/api/v1/responses"
private const val OPENROUTER_DEFAULT_MODELS_URL = "https://openrouter.ai/api/v1/models"

internal fun openRouterHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

internal fun openRouterCatalogHttpClient(baseClient: OkHttpClient = openRouterHttpClient()): OkHttpClient =
    baseClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

class OpenRouterProvider internal constructor(
    private val baseUrl: HttpUrl? = null,
    private val modelsUrlOverride: HttpUrl? = null,
    private val httpClient: OkHttpClient = openRouterHttpClient(),
    private val catalogHttpClient: OkHttpClient = openRouterCatalogHttpClient(httpClient),
    private val apiKeyOverride: String? = null,
    private val settingsLookup: (String?) -> ProviderSettings? = { configuredModelId ->
        runCatching {
            val store = AgentModelConfigStore.getInstance()
            configuredModelId?.let { store.providerSettingsForModel(it, OPENROUTER_PROVIDER_ID) }
                ?: store.providerSettings(OPENROUTER_PROVIDER_ID)
        }.getOrNull()
    },
) : AgentProvider() {
    constructor() : this(baseUrl = null)

    override val providerId = OPENROUTER_PROVIDER_ID
    override val displayName = "OpenRouter"
    override val settingsFields = listOf(
        ProviderSettingField(
            key = ProviderSettingKeys.API_KEY,
            label = "API key",
            kind = ProviderSettingFieldKind.PASSWORD,
            placeholder = "sk-or-...",
        ),
        ProviderSettingField(
            key = ProviderSettingKeys.USE_CUSTOM_BASE_URL,
            label = "Use custom base URL",
            kind = ProviderSettingFieldKind.CHECKBOX,
        ),
        ProviderSettingField(
            key = ProviderSettingKeys.BASE_URL,
            label = "Base URL",
            kind = ProviderSettingFieldKind.TEXT,
            placeholder = "https://...",
            visibleWhen = ProviderSettingKeys.USE_CUSTOM_BASE_URL to "true",
        ),
    )

    private val logger = Logger.getInstance(OpenRouterProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveSettings(configuredModelId: String? = null): ProviderSettings? = settingsLookup(configuredModelId)

    private fun resolveResponsesUrl(configuredModelId: String): HttpUrl {
        baseUrl?.let { return it }
        val settings = resolveSettings(configuredModelId)
        if (settings != null && settings.useCustomBaseUrl && !settings.baseUrl.isNullOrBlank()) {
            return endpointUrl(settings.baseUrl, "responses")
        }
        return OPENROUTER_DEFAULT_RESPONSES_URL.toHttpUrl()
    }

    private fun resolveModelsUrl(settings: ProviderSettings): HttpUrl {
        modelsUrlOverride?.let { return it }
        if (settings.useCustomBaseUrl && !settings.baseUrl.isNullOrBlank()) {
            return endpointUrl(settings.baseUrl, "models")
        }
        return OPENROUTER_DEFAULT_MODELS_URL.toHttpUrl()
    }

    private fun endpointUrl(baseUrl: String, endpoint: String): HttpUrl {
        val url = baseUrl.toHttpUrl()
        return if (url.pathSegments.lastOrNull { it.isNotBlank() } == endpoint) {
            url
        } else {
            url.newBuilder().addPathSegment(endpoint).build()
        }
    }

    private fun resolveApiKey(configuredModelId: String): String? = apiKeyOverride ?: resolveSettings(configuredModelId)?.apiKey

    override suspend fun fetchAvailableModels(settings: ProviderSettings): Result<List<IlModelDescriptor>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = resolveModelsUrl(settings)
                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                val key = apiKeyOverride ?: settings.apiKey
                if (!key.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $key")
                }
                val call = catalogHttpClient.newCall(builder.get().build())
                currentCoroutineContext().job.invokeOnCompletion {
                    if (!call.isCanceled()) {
                        call.cancel()
                    }
                }
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        error("OpenRouter /models returned HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    parseCatalog(body)
                }
            }
        }

    internal fun parseCatalog(body: String): List<IlModelDescriptor> {
        val parsed = json.parseToJsonElement(body).jsonObject
        val data = parsed["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val modelId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val displayName = obj["name"]?.jsonPrimitive?.contentOrNull ?: modelId
            val contextLength = obj["context_length"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val topProvider = obj["top_provider"] as? JsonObject
            val maxOutput = topProvider?.get("max_completion_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val pricing = obj["pricing"] as? JsonObject
            val inputPrice = pricing?.priceFor("prompt")
            val outputPrice = pricing?.priceFor("completion")
            val cacheRead = pricing?.priceFor("input_cache_read")
            val cacheWrite = pricing?.priceFor("input_cache_write")
            val supportedParams = (obj["supported_parameters"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
            val capabilities = buildSet {
                add(IlCapability.TEXT)
                add(IlCapability.STREAMING)
                if ("tools" in supportedParams) add(IlCapability.TOOL_CALLING)
                if ("reasoning" in supportedParams || "include_reasoning" in supportedParams) {
                    add(IlCapability.THINKING)
                }
            }
            IlModelDescriptor(
                id = catalogModelId(modelId),
                providerId = providerId,
                modelId = modelId,
                displayName = displayName,
                maxInputTokens = contextLength,
                maxOutputTokens = maxOutput,
                inputPricePerToken = inputPrice,
                outputPricePerToken = outputPrice,
                cacheReadPricePerToken = cacheRead,
                cacheWritePricePerToken = cacheWrite,
                capabilities = capabilities,
                reasoningEffort = null,
                source = IlModelSource.CATALOG,
            )
        }
    }

    private fun JsonObject.priceFor(key: String): Double? =
        this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

    override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = callbackFlow {
        fun emit(event: IlStreamEvent) {
            trySendBlocking(event).getOrThrow()
        }
        val streamJob = launch(Dispatchers.IO) {
            try {
        val syntheticTurnId = "openrouter-turn-${System.currentTimeMillis()}"
        val apiKey = resolveApiKey(request.model.id).orEmpty()
        if (apiKey.isBlank()) {
            emit(TurnStarted("preflight.created", syntheticTurnId, IlRole.ASSISTANT, meta("preflight.created", requestIndex = request.requestIndex())))
            emit(StreamError("missing-key", syntheticTurnId, "OpenRouter API key is not configured. Open Config to add it.", meta("error", requestIndex = request.requestIndex())))
            return@launch
        }

        val body = buildRequestBody(request).toString()
        val httpRequest = Request.Builder()
            .url(resolveResponsesUrl(request.model.id))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = httpClient.newCall(httpRequest)
        currentCoroutineContext().job.invokeOnCompletion {
            if (!call.isCanceled()) {
                call.cancel()
            }
        }
        call.execute().use { response ->
            val generationId = response.header("X-Generation-Id")
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                val parsedError = runCatching {
                    (json.parseToJsonElement(errorBody) as? JsonObject)?.get("error").asObjectOrNull()
                }.getOrNull()
                val message = parsedError?.get("message")?.jsonPrimitive?.content
                    ?: errorBody.takeIf { it.isNotBlank() }
                    ?: "OpenRouter request failed (HTTP ${response.code})"
                emit(TurnStarted("http.created", syntheticTurnId, IlRole.ASSISTANT, meta("http.created", generationId = generationId, requestIndex = request.requestIndex())))
                emit(
                    StreamError(
                        eventId = "http-error",
                        turnId = syntheticTurnId,
                        message = message,
                        meta = meta(
                            rawType = "http_error",
                            generationId = generationId,
                            httpStatus = response.code,
                            errorCode = parsedError?.get("code")?.jsonPrimitive?.content,
                            requestIndex = request.requestIndex(),
                        )
                    )
                )
                return@launch
            }

            var sawTerminalEvent = false
            var turnId = syntheticTurnId
            val emittedToolCalls = linkedSetOf<String>()
            val startedBlocks = linkedSetOf<String>()
            val streamedText = StringBuilder()
            val reader = response.body?.source() ?: run {
                emit(
                    StreamError(
                        "response.body.missing",
                        turnId,
                        "OpenRouter returned a successful response without a body",
                        meta("response.body.missing", generationId, responseId = turnId, requestIndex = request.requestIndex()),
                    )
                )
                return@launch
            }

            InputStreamReader(reader.inputStream(), Charsets.UTF_8).buffered().use { charReader ->
                readSseEvents(charReader) { payloadText ->
                    currentCoroutineContext().ensureActive()
                    if (payloadText == "[DONE]") {
                        return@readSseEvents
                    }
                    val parsed = runCatching { json.parseToJsonElement(payloadText) }.getOrNull()
                    val payload = parsed as? JsonObject ?: run {
                        logger.warn("OpenRouter SSE payload is not a JSON object: $payloadText")
                        return@readSseEvents
                    }
                    val type = payload["type"]?.jsonPrimitive?.content.orEmpty()
                    when (type) {
                        "response.created" -> {
                            val responseObject = payload["response"].asObjectOrNull()
                            val responseId = responseObject?.get("id")?.jsonPrimitive?.content ?: "resp-unknown"
                            turnId = responseId
                            emit(TurnStarted("response.created", turnId, IlRole.ASSISTANT, meta(type, generationId = generationId, responseId = responseId, rawPayload = payload, requestIndex = request.requestIndex())))
                        }

                        "response.output_item.added" -> {
                            val item = payload["item"].asObjectOrNull() ?: return@readSseEvents
                            val itemId = item["id"]?.jsonPrimitive?.content ?: "item-${payload["output_index"]?.jsonPrimitive?.content ?: "0"}"
                            val itemType = item["type"]?.jsonPrimitive?.content.orEmpty()
                            val itemStatus = item["status"]?.jsonPrimitive?.content
                            when (itemType) {
                                "message" -> {
                                    val content = item["content"].asArrayOrNull()?.firstOrNull().asObjectOrNull()
                                    val partType = content?.get("type")?.jsonPrimitive?.content.orEmpty()
                                    val kind = when (partType) {
                                        "refusal" -> IlBlockKind.REFUSAL
                                        else -> IlBlockKind.TEXT
                                    }
                                    if (startedBlocks.add(itemId)) {
                                        emit(BlockStarted("response.output_item.added.$itemId", turnId, itemId, kind, null, null, null, meta(type, generationId, responseId = turnId, itemId = itemId, itemType = itemType, itemStatus = itemStatus, rawPayload = payload, requestIndex = request.requestIndex())))
                                    }
                                }

                                "function_call" -> {
                                    emittedToolCalls += itemId
                                    if (startedBlocks.add(itemId)) {
                                        emit(BlockStarted("response.output_item.added.$itemId", turnId, itemId, IlBlockKind.TOOL_CALL, null, item["name"]?.jsonPrimitive?.content, item["call_id"]?.jsonPrimitive?.content, meta(type, generationId, responseId = turnId, itemId = itemId, itemType = itemType, itemStatus = itemStatus, rawPayload = payload, requestIndex = request.requestIndex())))
                                    }
                                }
                            }
                        }

                        "response.content_part.added" -> {
                            val itemId = payload["item_id"]?.jsonPrimitive?.content ?: return@readSseEvents
                            val contentIndex = payload["content_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val part = payload["part"].asObjectOrNull() ?: return@readSseEvents
                            val partType = part["type"]?.jsonPrimitive?.content.orEmpty()
                            if (partType == "reasoning_summary") {
                                val blockId = "$itemId-$contentIndex"
                                if (startedBlocks.add(blockId)) {
                                    emit(BlockStarted("response.content_part.added.$itemId.$contentIndex", turnId, blockId, IlBlockKind.THINKING, IlThinkingVisibility.SUMMARY, null, null, meta(type, generationId, responseId = turnId, itemId = itemId, contentIndex = contentIndex, contentPartType = partType, rawPayload = payload, requestIndex = request.requestIndex())))
                                }
                            }
                        }

                        "response.output_text.delta" -> {
                            val blockId = blockIdForText(payload)
                            if (startedBlocks.add(blockId)) {
                                emit(BlockStarted("response.output_text.delta.$blockId", turnId, blockId, IlBlockKind.TEXT, null, null, null, meta("synthetic.text.start", generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, rawPayload = payload, requestIndex = request.requestIndex())))
                            }
                            val delta = payload["delta"]?.jsonPrimitive?.content.orEmpty()
                            streamedText.append(delta)
                            emit(TextDelta("response.output_text.delta", turnId, blockId, delta, meta(type, generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, contentIndex = payload["content_index"]?.jsonPrimitive?.content?.toIntOrNull(), rawPayload = payload, requestIndex = request.requestIndex())))
                        }

                        "response.refusal.delta" -> {
                            val blockId = blockIdForText(payload)
                            if (startedBlocks.add(blockId)) {
                                emit(BlockStarted("response.refusal.delta.$blockId", turnId, blockId, IlBlockKind.REFUSAL, null, null, null, meta("synthetic.refusal.start", generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, rawPayload = payload, requestIndex = request.requestIndex())))
                            }
                            emit(RefusalDelta("response.refusal.delta", turnId, blockId, payload["delta"]?.jsonPrimitive?.content.orEmpty(), meta(type, generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, contentIndex = payload["content_index"]?.jsonPrimitive?.content?.toIntOrNull(), rawPayload = payload, requestIndex = request.requestIndex())))
                        }

                        "response.function_call_arguments.delta" -> emit(
                            ToolCallArgumentsDelta("response.function_call_arguments.delta", turnId, payload["item_id"]?.jsonPrimitive?.content.orEmpty(), payload["delta"]?.jsonPrimitive?.content.orEmpty(), meta(type, generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, rawPayload = payload, requestIndex = request.requestIndex()))
                        )

                        "response.function_call_arguments.done" -> emit(
                            ToolCallCompleted("response.function_call_arguments.done", turnId, payload["item_id"]?.jsonPrimitive?.content.orEmpty(), meta(type, generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, rawPayload = payload, requestIndex = request.requestIndex()))
                        )

                        "response.reasoning_text.delta" -> {
                            val itemId = payload["item_id"]?.jsonPrimitive?.content ?: "reasoning"
                            if (startedBlocks.add(itemId)) {
                                emit(BlockStarted("response.reasoning.start.$itemId", turnId, itemId, IlBlockKind.THINKING, IlThinkingVisibility.FULL, null, null, meta("synthetic.reasoning.start", generationId, responseId = turnId, itemId = itemId, rawPayload = payload, requestIndex = request.requestIndex())))
                            }
                            emit(ThinkingDelta(type, turnId, itemId, payload["delta"]?.jsonPrimitive?.content.orEmpty(), IlThinkingVisibility.FULL, meta(type, generationId, responseId = turnId, itemId = itemId, reasoningDetails = payload["reasoning_details"], rawPayload = payload, requestIndex = request.requestIndex())))
                        }

                        "response.reasoning_summary_text.delta" -> {
                            val itemId = payload["item_id"]?.jsonPrimitive?.content ?: "reasoning-summary"
                            if (startedBlocks.add(itemId)) {
                                emit(BlockStarted("response.reasoning.summary.start.$itemId", turnId, itemId, IlBlockKind.THINKING, IlThinkingVisibility.SUMMARY, null, null, meta("synthetic.reasoning.summary.start", generationId, responseId = turnId, itemId = itemId, rawPayload = payload, requestIndex = request.requestIndex())))
                            }
                            emit(ThinkingDelta(type, turnId, itemId, payload["delta"]?.jsonPrimitive?.content.orEmpty(), IlThinkingVisibility.SUMMARY, meta(type, generationId, responseId = turnId, itemId = itemId, reasoningSummaryIndex = payload["summary_index"]?.jsonPrimitive?.content?.toIntOrNull(), reasoningDetails = payload["reasoning_details"], rawPayload = payload, requestIndex = request.requestIndex())))
                        }

                        "response.completed" -> {
                            sawTerminalEvent = true
                            val responseObject = payload["response"].asObjectOrNull() ?: buildJsonObject { }
                            val output = responseObject["output"].asArrayOrNull() ?: JsonArray(emptyList())
                            val usage = responseObject["usage"].asObjectOrNull()
                            usage?.let {
                                emit(
                                    UsageUpdated(
                                        "response.completed.usage",
                                        turnId,
                                        net.alkalines.radiumcode.agent.il.IlUsage(
                                            inputTokens = usage["input_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                            outputTokens = usage["output_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                            totalTokens = usage["total_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                        ),
                                        UsageMergeMode.REPLACE,
                                        meta(type, generationId, responseId = turnId, rawPayload = payload, requestIndex = request.requestIndex())
                                    )
                                )
                            }
                            if (outputText(output) != streamedText.toString()) {
                                logger.warn("OpenRouter stream/payload divergence for response $turnId")
                            }
                            val unresolvedToolCall = emittedToolCalls.isNotEmpty()
                            emit(TurnCompleted(type, turnId, if (unresolvedToolCall) IlFinishReason.TOOL_CALL else IlFinishReason.STOP, "completed", unresolvedToolCall, meta(type, generationId, responseId = turnId, rawPayload = payload, requestIndex = request.requestIndex())))
                        }

                        "response.incomplete" -> {
                            sawTerminalEvent = true
                            val reason = payload["response"].asObjectOrNull()?.get("incomplete_details").asObjectOrNull()?.get("reason")?.jsonPrimitive?.content
                            emit(
                                TurnCompleted(
                                    type,
                                    turnId,
                                    when (reason) {
                                        "max_output_tokens" -> IlFinishReason.MAX_TOKENS
                                        "content_filter" -> IlFinishReason.SAFETY
                                        else -> IlFinishReason.OTHER
                                    },
                                    reason,
                                    false,
                                    meta(type, generationId, responseId = turnId, rawPayload = payload, requestIndex = request.requestIndex())
                                )
                            )
                        }

                        "response.failed", "response.error", "error" -> {
                            sawTerminalEvent = true
                            val error = payload["error"].asObjectOrNull()
                            val message = error?.get("message")?.jsonPrimitive?.content ?: payload.toString()
                            emit(StreamError(type, turnId, message, meta(type, generationId, responseId = turnId, rawPayload = payload, errorCode = error?.get("code")?.jsonPrimitive?.content, requestIndex = request.requestIndex())))
                        }
                    }
                }
            }
            if (!sawTerminalEvent) {
                emit(StreamError("stream.truncated", turnId, "OpenRouter stream ended unexpectedly", meta("stream.truncated", generationId, responseId = turnId, requestIndex = request.requestIndex())))
            }
        }
            } finally {
                close()
            }
        }
        awaitClose { streamJob.cancel() }
    }

    private fun catalogModelId(modelId: String): String = "$providerId:$modelId"

    internal fun buildRequestBody(request: IlGenerateRequest): JsonObject = buildJsonObject {
        val model = request.model
        put("model", model.modelId)
        put("stream", true)
        request.maxOutputTokens?.let { put("max_output_tokens", it) }
        if (IlCapability.THINKING in model.capabilities) {
            val effort = model.reasoningEffort
            if (effort != null && effort != IlReasoningEffort.NONE && effort.wireValue != null) {
                put("reasoning", buildJsonObject { put("effort", effort.wireValue) })
            }
        }
        val supportsToolCalling = IlCapability.TOOL_CALLING in model.capabilities
        if (supportsToolCalling && request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { add(serializeToolDefinition(it)) }
            })
            put("parallel_tool_calls", request.allowParallelToolCalls)
        }
        if (supportsToolCalling && (request.tools.isNotEmpty() || request.toolChoice !is IlToolChoice.None)) {
            put("tool_choice", serializeToolChoice(request.toolChoice))
        }
        put("input", buildJsonArray {
            request.input.filter(::shouldSerializeTurn).flatMap(::serializeTurn).forEach { add(it) }
        })
    }

    private fun shouldSerializeTurn(turn: IlConversationTurn): Boolean = when (turn.role) {
        IlRole.SYSTEM, IlRole.DEVELOPER ->
            turn.status == IlTurnStatus.COMPLETED && turn.blocks.any { it is IlTextBlock && it.text.isNotBlank() }
        IlRole.USER -> turn.blocks.any { it is IlTextBlock && it.text.isNotBlank() }
        IlRole.ASSISTANT -> turn.status == IlTurnStatus.COMPLETED &&
            turn.blocks.any { it is IlTextBlock || it is IlToolCallBlock || it is net.alkalines.radiumcode.agent.il.IlRefusalBlock }
        IlRole.TOOL -> turn.status == IlTurnStatus.COMPLETED && turn.blocks.any { it is IlToolResultBlock }
    }

    private fun serializeTurn(turn: IlConversationTurn): List<JsonObject> = when (turn.role) {
        IlRole.SYSTEM -> listOf(serializeInputMessageTurn(turn, "system"))
        IlRole.DEVELOPER -> listOf(serializeInputMessageTurn(turn, "developer"))
        IlRole.USER -> listOf(serializeInputMessageTurn(turn, "user"))

        IlRole.ASSISTANT -> serializeAssistantTurn(turn)
        IlRole.TOOL -> turn.blocks.filterIsInstance<IlToolResultBlock>().map { block ->
            buildJsonObject {
                put("type", "function_call_output")
                put("id", block.id)
                put("call_id", block.callId)
                put("output", block.outputPayload)
            }
        }
    }

    private fun serializeAssistantTurn(turn: IlConversationTurn): List<JsonObject> {
        val serialized = mutableListOf<JsonObject>()
        val bufferedMessageBlocks = mutableListOf<IlBlock>()
        var messageChunkIndex = 0

        fun flushMessageBlocks() {
            if (bufferedMessageBlocks.isEmpty()) {
                return
            }
            val messageId = if (messageChunkIndex == 0) turn.id else "${turn.id}-msg-$messageChunkIndex"
            serialized += buildJsonObject {
                put("type", "message")
                put("id", messageId)
                put("status", "completed")
                put("role", "assistant")
                put("content", buildJsonArray {
                    bufferedMessageBlocks.forEach { block ->
                        when (block) {
                            is IlTextBlock -> add(buildJsonObject {
                                put("type", "output_text")
                                put("text", block.text)
                            })

                            is net.alkalines.radiumcode.agent.il.IlRefusalBlock -> add(buildJsonObject {
                                put("type", "refusal")
                                put("text", block.text)
                            })

                            else -> Unit
                        }
                    }
                })
            }
            bufferedMessageBlocks.clear()
            messageChunkIndex += 1
        }

        turn.blocks.forEach { block ->
            when (block) {
                is IlToolCallBlock -> {
                    flushMessageBlocks()
                    serialized += buildJsonObject {
                        put("type", "function_call")
                        put("id", block.id)
                        put("call_id", block.callId ?: block.id)
                        put("name", block.toolName ?: "tool")
                        put("arguments", block.argumentsJson)
                    }
                }

                is IlTextBlock, is net.alkalines.radiumcode.agent.il.IlRefusalBlock -> bufferedMessageBlocks += block
                else -> Unit
            }
        }
        flushMessageBlocks()

        return serialized
    }

    private fun serializeInputMessageTurn(turn: IlConversationTurn, role: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("role", role)
        put("content", buildJsonArray {
            turn.blocks.filterIsInstance<IlTextBlock>().forEach { block ->
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", block.text)
                })
            }
        })
    }

    private fun serializeToolDefinition(tool: IlToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", tool.inputSchema)
    }

    private fun serializeToolChoice(toolChoice: IlToolChoice): JsonElement = when (toolChoice) {
        IlToolChoice.Auto -> JsonPrimitive("auto")
        IlToolChoice.None -> JsonPrimitive("none")
        IlToolChoice.Required -> JsonPrimitive("required")
        is IlToolChoice.Specific -> buildJsonObject {
            put("type", "function")
            put("name", toolChoice.name)
        }
    }

    private suspend fun readSseEvents(
        reader: BufferedReader,
        onEvent: suspend (String) -> Unit,
    ) {
        val dataLines = mutableListOf<String>()

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                val payload = dataLines.joinToString("\n")
                dataLines.clear()
                if (payload.isNotBlank()) {
                    onEvent(payload)
                }
            } else if (!line.startsWith(":") && line.startsWith("data:")) {
                dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        if (dataLines.isNotEmpty()) {
            val payload = dataLines.joinToString("\n")
            if (payload.isNotBlank()) {
                onEvent(payload)
            }
        }
    }

    private fun meta(
        rawType: String,
        generationId: String? = null,
        responseId: String? = null,
        itemId: String? = null,
        itemType: String? = null,
        itemStatus: String? = null,
        contentIndex: Int? = null,
        contentPartType: String? = null,
        reasoningSummaryIndex: Int? = null,
        reasoningDetails: JsonElement? = null,
        encryptedReasoningContent: JsonElement? = null,
        httpStatus: Int? = null,
        errorCode: String? = null,
        rawPayload: JsonElement? = null,
        requestIndex: Int? = null,
    ): IlMeta = IlMeta(
        providerId = providerId,
        rawType = rawType,
        providerExtras = buildJsonObject {
            put("openrouter", buildJsonObject {
                responseId?.let { put("responseId", it) }
                generationId?.let { put("generationId", it) }
                itemId?.let { put("itemId", it) }
                itemType?.let { put("itemType", it) }
                itemStatus?.let { put("itemStatus", it) }
                contentIndex?.let { put("contentIndex", it) }
                contentPartType?.let { put("contentPartType", it) }
                reasoningSummaryIndex?.let { put("reasoningSummaryIndex", it) }
                reasoningDetails?.let { put("reasoningDetails", it) }
                encryptedReasoningContent?.let { put("encryptedReasoningContent", it) }
                httpStatus?.let { put("httpStatus", it) }
                errorCode?.let { put("errorCode", it) }
            })
            requestIndex?.let { put("requestIndex", it) }
        },
        rawPayload = rawPayload,
        receivedAt = System.currentTimeMillis(),
        sequenceNumber = null,
    )

    private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

    private fun blockIdForText(payload: JsonObject): String {
        val itemId = payload["item_id"]?.jsonPrimitive?.content.orEmpty()
        val contentIndex = payload["content_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return if (contentIndex == 0) itemId else "$itemId-$contentIndex"
    }

    private fun outputText(output: JsonArray): String = output.joinToString("") { item ->
        val itemObject = item as? JsonObject ?: return@joinToString ""
        if (itemObject["type"]?.jsonPrimitive?.content != "message") {
            return@joinToString ""
        }
        val content = itemObject["content"] as? JsonArray ?: return@joinToString ""
        content.joinToString("") { part ->
            val partObject = part as? JsonObject ?: return@joinToString ""
            if (partObject["type"]?.jsonPrimitive?.content != "output_text") {
                return@joinToString ""
            }
            partObject["text"]?.jsonPrimitive?.content.orEmpty()
        }
    }

    private fun IlGenerateRequest.requestIndex(): Int = metadata["requestIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
}
