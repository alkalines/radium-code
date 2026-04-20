package net.alkalines.radiumcode.agent.providers

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.alkalines.radiumcode.agent.il.BlockStarted
import net.alkalines.radiumcode.agent.il.IlBlockKind
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlFinishReason
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlStreamEvent
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingVisibility
import net.alkalines.radiumcode.agent.il.IlToolCallBlock
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
import java.util.concurrent.TimeUnit

private const val OPENROUTER_API_KEY = "Change The Api Key" // TODO: cole sua API key do OpenRouter aqui para testar

internal fun openRouterHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

class OpenRouterProvider internal constructor(
    private val baseUrl: HttpUrl = "https://openrouter.ai/api/v1/responses".toHttpUrl(),
    private val httpClient: OkHttpClient = openRouterHttpClient(),
    private val apiKeyOverride: String? = null,
) : AgentProvider() {
    override val providerId = "openrouter"
    override val displayName = "OpenRouter"
    override val models = listOf(
        IlModelDescriptor(
            providerId = providerId,
            modelId = "z-ai/glm-4.5-air:free",
            displayName = "OpenRouter",
            capabilities = setOf(IlCapability.TEXT, IlCapability.THINKING, IlCapability.TOOL_CALLING, IlCapability.STREAMING),
            isDefault = true,
        ),
        IlModelDescriptor(
            providerId = providerId,
            modelId = "moonshotai/kimi-k2.6",
            displayName = "Kimi K2.6",
            capabilities = setOf(IlCapability.TEXT, IlCapability.THINKING, IlCapability.TOOL_CALLING, IlCapability.STREAMING),
            isDefault = false,
        ),
        IlModelDescriptor(
            providerId = providerId,
            modelId = "minimax/minimax-m2.7",
            displayName = "MiniMax M2.7",
            capabilities = setOf(IlCapability.TEXT, IlCapability.THINKING, IlCapability.TOOL_CALLING, IlCapability.STREAMING),
            isDefault = false,
        ),
    )

    private val logger = Logger.getInstance(OpenRouterProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(request: IlGenerateRequest): Flow<IlStreamEvent> = flow {
        val syntheticTurnId = "openrouter-turn-${System.currentTimeMillis()}"
        val apiKey = apiKeyOverride ?: OPENROUTER_API_KEY
        if (apiKey.isBlank()) {
            emit(TurnStarted("preflight.created", syntheticTurnId, IlRole.ASSISTANT, meta("preflight.created")))
            emit(StreamError("missing-key", syntheticTurnId, "OpenRouter API key not configured in OpenRouterProvider", meta("error")))
            return@flow
        }

        val body = buildRequestBody(request).toString()
        val httpRequest = Request.Builder()
            .url(baseUrl)
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
                val parsedError = runCatching { json.parseToJsonElement(errorBody).jsonObject["error"]?.jsonObject }.getOrNull()
                val message = parsedError?.get("message")?.jsonPrimitive?.content
                    ?: errorBody.takeIf { it.isNotBlank() }
                    ?: "OpenRouter request failed (HTTP ${response.code})"
                emit(TurnStarted("http.created", syntheticTurnId, IlRole.ASSISTANT, meta("http.created", generationId = generationId)))
                emit(
                    StreamError(
                        eventId = "http-error",
                        turnId = syntheticTurnId,
                        message = message,
                        meta = meta(
                            rawType = "http_error",
                            generationId = generationId,
                            httpStatus = response.code,
                            errorCode = parsedError?.get("code")?.jsonPrimitive?.content
                        )
                    )
                )
                return@flow
            }

            var sawTerminalEvent = false
            var turnId = "turn-openrouter"
            val emittedToolCalls = linkedSetOf<String>()
            val startedBlocks = linkedSetOf<String>()
            val streamedText = StringBuilder()
            response.body?.source()?.inputStream()?.bufferedReader()?.useLines { lines ->
                lines.forEach { rawLine ->
                    currentCoroutineContext().ensureActive()
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith(":") || !line.startsWith("data:")) {
                        return@forEach
                    }
                    val payloadText = line.removePrefix("data:").trim()
                    val parsed = runCatching { json.parseToJsonElement(payloadText) }.getOrNull()
                    val payload = parsed as? JsonObject ?: run {
                        logger.warn("OpenRouter SSE payload is not a JSON object: $payloadText")
                        return@forEach
                    }
                    val type = payload["type"]?.jsonPrimitive?.content.orEmpty()
                    when (type) {
                        "response.created" -> {
                            val responseObject = payload["response"].asObjectOrNull()
                            val responseId = responseObject?.get("id")?.jsonPrimitive?.content ?: "resp-unknown"
                            turnId = responseId
                            emit(TurnStarted("response.created", turnId, IlRole.ASSISTANT, meta(type, generationId = generationId, responseId = responseId, rawPayload = payload)))
                        }

                        "response.output_item.added" -> {
                            val item = payload["item"].asObjectOrNull() ?: return@forEach
                            val itemId = item["id"]?.jsonPrimitive?.content ?: "item-${payload["output_index"]?.jsonPrimitive?.content ?: "0"}"
                            val itemType = item["type"]?.jsonPrimitive?.content.orEmpty()
                            val itemStatus = item["status"]?.jsonPrimitive?.content
                            when (itemType) {
                                "message" -> {
                                    startedBlocks += itemId
                                    val content = item["content"].asArrayOrNull()?.firstOrNull().asObjectOrNull()
                                    val partType = content?.get("type")?.jsonPrimitive?.content.orEmpty()
                                    val kind = when (partType) {
                                        "output_text" -> IlBlockKind.TEXT
                                        "refusal" -> IlBlockKind.REFUSAL
                                        else -> IlBlockKind.TEXT
                                    }
                                    emit(BlockStarted("response.output_item.added.$itemId", turnId, itemId, kind, null, null, null, meta(type, generationId, responseId = turnId, itemId = itemId, itemType = itemType, itemStatus = itemStatus, rawPayload = payload)))
                                }

                                "function_call" -> {
                                    emittedToolCalls += itemId
                                    startedBlocks += itemId
                                    emit(BlockStarted("response.output_item.added.$itemId", turnId, itemId, IlBlockKind.TOOL_CALL, null, item["name"]?.jsonPrimitive?.content, item["call_id"]?.jsonPrimitive?.content, meta(type, generationId, responseId = turnId, itemId = itemId, itemType = itemType, itemStatus = itemStatus, rawPayload = payload)))
                                }
                            }
                        }

                        "response.content_part.added" -> {
                            val itemId = payload["item_id"]?.jsonPrimitive?.content ?: return@forEach
                            val contentIndex = payload["content_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val part = payload["part"].asObjectOrNull() ?: return@forEach
                            val partType = part["type"]?.jsonPrimitive?.content.orEmpty()
                            if (partType == "reasoning_summary") {
                                val blockId = "$itemId-$contentIndex"
                                startedBlocks += blockId
                                emit(BlockStarted("response.content_part.added.$itemId.$contentIndex", turnId, blockId, IlBlockKind.THINKING, IlThinkingVisibility.SUMMARY, null, null, meta(type, generationId, responseId = turnId, itemId = itemId, contentIndex = contentIndex, contentPartType = partType, rawPayload = payload)))
                            }
                        }

                        "response.output_text.delta" -> {
                            val blockId = blockIdForText(payload)
                            if (startedBlocks.add(blockId)) {
                                emit(BlockStarted("response.output_text.delta.$blockId", turnId, blockId, IlBlockKind.TEXT, null, null, null, meta("synthetic.text.start", generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, rawPayload = payload)))
                            }
                            val delta = payload["delta"]?.jsonPrimitive?.content.orEmpty()
                            streamedText.append(delta)
                            emit(TextDelta("response.output_text.delta", turnId, blockId, delta, meta(type, generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, contentIndex = payload["content_index"]?.jsonPrimitive?.content?.toIntOrNull(), rawPayload = payload)))
                        }

                        "response.refusal.delta" -> {
                            val blockId = blockIdForText(payload)
                            if (startedBlocks.add(blockId)) {
                                emit(BlockStarted("response.refusal.delta.$blockId", turnId, blockId, IlBlockKind.REFUSAL, null, null, null, meta("synthetic.refusal.start", generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, rawPayload = payload)))
                            }
                            emit(RefusalDelta("response.refusal.delta", turnId, blockId, payload["delta"]?.jsonPrimitive?.content.orEmpty(), meta(type, generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, contentIndex = payload["content_index"]?.jsonPrimitive?.content?.toIntOrNull(), rawPayload = payload)))
                        }

                        "response.function_call_arguments.delta" -> emit(
                            ToolCallArgumentsDelta("response.function_call_arguments.delta", turnId, payload["item_id"]?.jsonPrimitive?.content.orEmpty(), payload["delta"]?.jsonPrimitive?.content.orEmpty(), meta(type, generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, rawPayload = payload))
                        )

                        "response.function_call_arguments.done" -> emit(
                            ToolCallCompleted("response.function_call_arguments.done", turnId, payload["item_id"]?.jsonPrimitive?.content.orEmpty(), meta(type, generationId, responseId = turnId, itemId = payload["item_id"]?.jsonPrimitive?.content, rawPayload = payload))
                        )

                        "response.reasoning_text.delta" -> {
                            val itemId = payload["item_id"]?.jsonPrimitive?.content ?: "reasoning"
                            if (startedBlocks.add(itemId)) {
                                emit(BlockStarted("response.reasoning.start.$itemId", turnId, itemId, IlBlockKind.THINKING, IlThinkingVisibility.FULL, null, null, meta("synthetic.reasoning.start", generationId, responseId = turnId, itemId = itemId, rawPayload = payload)))
                            }
                            emit(ThinkingDelta(type, turnId, itemId, payload["delta"]?.jsonPrimitive?.content.orEmpty(), IlThinkingVisibility.FULL, meta(type, generationId, responseId = turnId, itemId = itemId, reasoningDetails = payload["reasoning_details"], rawPayload = payload)))
                        }

                        "response.reasoning_summary_text.delta" -> {
                            val itemId = payload["item_id"]?.jsonPrimitive?.content ?: "reasoning-summary"
                            if (startedBlocks.add(itemId)) {
                                emit(BlockStarted("response.reasoning.summary.start.$itemId", turnId, itemId, IlBlockKind.THINKING, IlThinkingVisibility.SUMMARY, null, null, meta("synthetic.reasoning.summary.start", generationId, responseId = turnId, itemId = itemId, rawPayload = payload)))
                            }
                            emit(ThinkingDelta(type, turnId, itemId, payload["delta"]?.jsonPrimitive?.content.orEmpty(), IlThinkingVisibility.SUMMARY, meta(type, generationId, responseId = turnId, itemId = itemId, reasoningSummaryIndex = payload["summary_index"]?.jsonPrimitive?.content?.toIntOrNull(), reasoningDetails = payload["reasoning_details"], rawPayload = payload)))
                        }

                        "response.completed" -> {
                            sawTerminalEvent = true
                            val responseObject = payload["response"].asObjectOrNull() ?: buildJsonObject { }
                            val output = responseObject["output"].asArrayOrNull() ?: JsonArray(emptyList())
                            val usage = responseObject["usage"].asObjectOrNull()
                            usage?.let {
                                emit(UsageUpdated("response.completed.usage", turnId, net.alkalines.radiumcode.agent.il.IlUsage(
                                    inputTokens = usage["input_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                    outputTokens = usage["output_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                    totalTokens = usage["total_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                ), UsageMergeMode.REPLACE, meta(type, generationId, responseId = turnId, rawPayload = payload)))
                            }
                            if (outputText(output) != streamedText.toString()) {
                                logger.warn("OpenRouter stream/payload divergence for response $turnId")
                            }
                            val unresolvedToolCall = emittedToolCalls.isNotEmpty()
                            emit(TurnCompleted(type, turnId, if (unresolvedToolCall) IlFinishReason.TOOL_CALL else IlFinishReason.STOP, "completed", unresolvedToolCall, meta(type, generationId, responseId = turnId, rawPayload = payload)))
                        }

                        "response.incomplete" -> {
                            sawTerminalEvent = true
                            val reason = payload["response"].asObjectOrNull()?.get("incomplete_details").asObjectOrNull()?.get("reason")?.jsonPrimitive?.content
                            emit(TurnCompleted(type, turnId, when (reason) {
                                "max_output_tokens" -> IlFinishReason.MAX_TOKENS
                                "content_filter" -> IlFinishReason.SAFETY
                                else -> IlFinishReason.OTHER
                            }, reason, false, meta(type, generationId, responseId = turnId, rawPayload = payload)))
                        }

                        "response.failed", "response.error", "error" -> {
                            sawTerminalEvent = true
                            val error = payload["error"].asObjectOrNull()
                            val message = error?.get("message")?.jsonPrimitive?.content
                                ?: payload.toString()
                            emit(StreamError(type, turnId, message, meta(type, generationId, responseId = turnId, rawPayload = payload, errorCode = error?.get("code")?.jsonPrimitive?.content)))
                        }
                    }
                }
            }
            if (!sawTerminalEvent) {
                emit(StreamError("stream.truncated", turnId, "OpenRouter stream ended unexpectedly", meta("stream.truncated", generationId, responseId = turnId)))
            }
        }
    }

    internal fun buildRequestBody(request: IlGenerateRequest): JsonObject = buildJsonObject {
        put("model", request.modelId)
        put("stream", true)
        request.maxOutputTokens?.let { put("max_output_tokens", it) }
        val model = models.first()
        if (model.capabilities.contains(IlCapability.THINKING)) {
            put("reasoning", buildJsonObject { put("enabled", true) })
        }
        put("input", buildJsonArray {
            request.input.filter(::shouldSerializeTurn).forEach { add(serializeTurn(it)) }
        })
    }

    private fun shouldSerializeTurn(turn: IlConversationTurn): Boolean = when (turn.role) {
        IlRole.ASSISTANT -> turn.status == IlTurnStatus.COMPLETED &&
            turn.blocks.any { block -> block is IlTextBlock || block is IlToolCallBlock }
        else -> true
    }

    private fun serializeTurn(turn: IlConversationTurn): JsonObject = when (turn.role) {
        IlRole.USER -> buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", buildJsonArray {
                turn.blocks.filterIsInstance<IlTextBlock>().forEach { block ->
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", block.text)
                    })
                }
            })
        }

        IlRole.ASSISTANT -> buildJsonObject {
            put("type", "message")
            put("id", turn.id)
            put("status", "completed")
            put("role", "assistant")
            put("content", buildJsonArray {
                turn.blocks.forEach { block ->
                    when (block) {
                        is IlTextBlock -> add(buildJsonObject {
                            put("type", "output_text")
                            put("text", block.text)
                        })
                        is IlToolCallBlock -> add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", block.callId ?: block.id)
                            put("name", block.toolName ?: "tool")
                            put("arguments", block.argumentsJson)
                        })
                        else -> {}
                    }
                }
            })
        }

        else -> buildJsonObject {
            put("type", "message")
            put("role", turn.role.name.lowercase())
            put("content", buildJsonArray { })
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
        val content = itemObject["content"] as? JsonArray ?: return@joinToString ""
        content.joinToString("") { part ->
            val partObject = part as? JsonObject ?: return@joinToString ""
            partObject["text"]?.jsonPrimitive?.content.orEmpty()
        }
    }

}
