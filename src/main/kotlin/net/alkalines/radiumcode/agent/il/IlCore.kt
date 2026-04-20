package net.alkalines.radiumcode.agent.il

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class IlConversationSession(
    val turns: List<IlConversationTurn> = emptyList(),
)

enum class IlRole {
    SYSTEM,
    DEVELOPER,
    USER,
    ASSISTANT,
    TOOL,
}

enum class IlTurnStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class IlFinishReason {
    STOP,
    TOOL_CALL,
    MAX_TOKENS,
    SAFETY,
    ERROR,
    CANCELLED,
    OTHER,
}

data class IlFinish(
    val reason: IlFinishReason,
    val rawReason: String?,
)

data class IlUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
)

data class IlTurnError(
    val message: String,
    val meta: IlMeta? = null,
)

data class IlMeta(
    val providerId: String,
    val rawType: String,
    val providerExtras: JsonObject,
    val rawPayload: JsonElement?,
    val receivedAt: Long,
    val sequenceNumber: Long?,
) {
    companion object {
        fun openrouter(rawType: String, providerExtras: JsonObject = buildJsonObject { put("openrouter", buildJsonObject { }) }) = IlMeta(
            providerId = "openrouter",
            rawType = rawType,
            providerExtras = providerExtras,
            rawPayload = null,
            receivedAt = System.currentTimeMillis(),
            sequenceNumber = null,
        )
    }
}

enum class IlBlockKind {
    TEXT,
    THINKING,
    REFUSAL,
    TOOL_CALL,
    TOOL_RESULT,
    OPAQUE,
}

enum class IlBlockStatus {
    IN_PROGRESS,
    COMPLETED,
    INCOMPLETE,
    ERRORED,
}

enum class IlThinkingVisibility {
    SUMMARY,
    FULL,
    ENCRYPTED_REFERENCE,
}

sealed interface IlBlock {
    val id: String
    val kind: IlBlockKind
    val status: IlBlockStatus
    val meta: IlMeta
}

data class IlTextBlock(
    override val id: String,
    val text: String,
    override val status: IlBlockStatus,
    override val meta: IlMeta,
) : IlBlock {
    override val kind = IlBlockKind.TEXT

    companion object {
        fun completed(id: String, text: String, meta: IlMeta = IlMeta.openrouter("text")) = IlTextBlock(id, text, IlBlockStatus.COMPLETED, meta)
    }
}

data class IlThinkingBlock(
    override val id: String,
    val text: String,
    val visibility: IlThinkingVisibility,
    val referencePayload: JsonElement?,
    override val status: IlBlockStatus,
    override val meta: IlMeta,
) : IlBlock {
    override val kind = IlBlockKind.THINKING
}

data class IlRefusalBlock(
    override val id: String,
    val text: String,
    override val status: IlBlockStatus,
    override val meta: IlMeta,
) : IlBlock {
    override val kind = IlBlockKind.REFUSAL
}

data class IlToolCallBlock(
    override val id: String,
    val callId: String?,
    val toolName: String?,
    val argumentsJson: String,
    val executionKind: String = "function",
    override val status: IlBlockStatus,
    override val meta: IlMeta,
) : IlBlock {
    override val kind = IlBlockKind.TOOL_CALL
}

data class IlToolResultBlock(
    override val id: String,
    val callId: String,
    val toolName: String,
    val resultJson: String,
    val isError: Boolean,
    override val status: IlBlockStatus,
    override val meta: IlMeta,
) : IlBlock {
    override val kind = IlBlockKind.TOOL_RESULT
}

data class IlProviderOpaqueBlock(
    override val id: String,
    val providerType: String,
    val rawData: JsonElement,
    override val status: IlBlockStatus,
    override val meta: IlMeta,
) : IlBlock {
    override val kind = IlBlockKind.OPAQUE
}

data class IlConversationTurn(
    val id: String,
    val role: IlRole,
    val blocks: List<IlBlock>,
    val status: IlTurnStatus,
    val usage: IlUsage?,
    val finish: IlFinish?,
    val willContinue: Boolean,
    val meta: IlMeta,
    val error: IlTurnError? = null,
) {
    companion object {
        fun userText(id: String, text: String, meta: IlMeta = IlMeta.openrouter("user")) = IlConversationTurn(
            id = id,
            role = IlRole.USER,
            blocks = listOf(IlTextBlock.completed("$id-text", text, meta)),
            status = IlTurnStatus.COMPLETED,
            usage = null,
            finish = null,
            willContinue = false,
            meta = meta,
        )
    }
}

data class IlContinuation(
    val kind: String,
    val opaqueToken: String,
    val meta: JsonObject = buildJsonObject { },
)

data class IlToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val meta: IlMeta = IlMeta.openrouter("tool_definition"),
)

sealed interface IlToolChoice {
    data object Auto : IlToolChoice
    data object None : IlToolChoice
    data object Required : IlToolChoice
    data class Specific(val name: String) : IlToolChoice
}

enum class IlCapability {
    TEXT,
    THINKING,
    TOOL_CALLING,
    STREAMING,
}

data class IlModelDescriptor(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val capabilities: Set<IlCapability>,
    val isDefault: Boolean = false,
)

data class IlGenerateRequest(
    val providerId: String,
    val modelId: String,
    val input: List<IlConversationTurn>,
    val tools: List<IlToolDefinition>,
    val toolChoice: IlToolChoice,
    val allowParallelToolCalls: Boolean,
    val maxOutputTokens: Int?,
    val temperature: Double?,
    val topP: Double?,
    val stopSequences: List<String>,
    val continuation: IlContinuation?,
    val metadata: JsonObject,
    val providerOptions: JsonObject,
)

enum class UsageMergeMode {
    REPLACE,
    INCREMENT,
}

sealed interface IlStreamEvent {
    val eventId: String
    val turnId: String
    val meta: IlMeta
}

data class TurnStarted(
    override val eventId: String,
    override val turnId: String,
    val role: IlRole,
    override val meta: IlMeta,
) : IlStreamEvent

data class BlockStarted(
    override val eventId: String,
    override val turnId: String,
    val blockId: String,
    val kind: IlBlockKind,
    val visibility: IlThinkingVisibility?,
    val initialToolName: String?,
    val initialCallId: String?,
    override val meta: IlMeta,
) : IlStreamEvent

data class TextDelta(
    override val eventId: String,
    override val turnId: String,
    val blockId: String,
    val delta: String,
    override val meta: IlMeta,
) : IlStreamEvent

data class ThinkingDelta(
    override val eventId: String,
    override val turnId: String,
    val blockId: String,
    val delta: String,
    val visibility: IlThinkingVisibility,
    override val meta: IlMeta,
) : IlStreamEvent

data class RefusalDelta(
    override val eventId: String,
    override val turnId: String,
    val blockId: String,
    val delta: String,
    override val meta: IlMeta,
) : IlStreamEvent

data class ToolCallArgumentsDelta(
    override val eventId: String,
    override val turnId: String,
    val blockId: String,
    val delta: String,
    override val meta: IlMeta,
) : IlStreamEvent

data class ToolCallCompleted(
    override val eventId: String,
    override val turnId: String,
    val blockId: String,
    override val meta: IlMeta,
) : IlStreamEvent

data class ToolResultAdded(
    override val eventId: String,
    override val turnId: String,
    val callId: String,
    val toolName: String,
    val resultJson: String,
    val isError: Boolean,
    override val meta: IlMeta,
) : IlStreamEvent

data class UsageUpdated(
    override val eventId: String,
    override val turnId: String,
    val usage: IlUsage,
    val mode: UsageMergeMode,
    override val meta: IlMeta,
) : IlStreamEvent

data class TurnCompleted(
    override val eventId: String,
    override val turnId: String,
    val finishReason: IlFinishReason,
    val rawReason: String?,
    val willContinue: Boolean,
    override val meta: IlMeta,
) : IlStreamEvent

data class StreamError(
    override val eventId: String,
    override val turnId: String,
    val message: String,
    override val meta: IlMeta,
) : IlStreamEvent
