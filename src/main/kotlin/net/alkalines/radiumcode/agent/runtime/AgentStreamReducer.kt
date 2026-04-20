package net.alkalines.radiumcode.agent.runtime

import kotlinx.serialization.json.Json
import net.alkalines.radiumcode.agent.il.BlockStarted
import net.alkalines.radiumcode.agent.il.IlBlock
import net.alkalines.radiumcode.agent.il.IlBlockKind
import net.alkalines.radiumcode.agent.il.IlBlockStatus
import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlFinish
import net.alkalines.radiumcode.agent.il.IlFinishReason
import net.alkalines.radiumcode.agent.il.IlProviderOpaqueBlock
import net.alkalines.radiumcode.agent.il.IlRefusalBlock
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingBlock
import net.alkalines.radiumcode.agent.il.IlThinkingVisibility
import net.alkalines.radiumcode.agent.il.IlToolCallBlock
import net.alkalines.radiumcode.agent.il.IlToolResultBlock
import net.alkalines.radiumcode.agent.il.IlTurnError
import net.alkalines.radiumcode.agent.il.IlTurnStatus
import net.alkalines.radiumcode.agent.il.IlUsage
import net.alkalines.radiumcode.agent.il.RefusalDelta
import net.alkalines.radiumcode.agent.il.StreamError
import net.alkalines.radiumcode.agent.il.TextDelta
import net.alkalines.radiumcode.agent.il.ThinkingDelta
import net.alkalines.radiumcode.agent.il.ToolCallArgumentsDelta
import net.alkalines.radiumcode.agent.il.ToolCallCompleted
import net.alkalines.radiumcode.agent.il.ToolResultAdded
import net.alkalines.radiumcode.agent.il.TurnCompleted
import net.alkalines.radiumcode.agent.il.TurnStarted
import net.alkalines.radiumcode.agent.il.UsageMergeMode
import net.alkalines.radiumcode.agent.il.UsageUpdated

class AgentStreamReducer {
    var session: IlConversationSession = IlConversationSession()
        internal set

    fun apply(event: Any) {
        when (event) {
            is TurnStarted -> startTurn(event)
            is BlockStarted -> startBlock(event)
            is TextDelta -> mutateBlock(event.turnId, event.blockId) { block ->
                (block as IlTextBlock).copy(text = block.text + event.delta)
            }
            is ThinkingDelta -> mutateBlock(event.turnId, event.blockId) { block ->
                (block as IlThinkingBlock).copy(
                    text = block.text + event.delta,
                    referencePayload = block.referencePayload ?: event.meta.rawPayload
                )
            }
            is RefusalDelta -> mutateBlock(event.turnId, event.blockId) { block ->
                (block as IlRefusalBlock).copy(text = block.text + event.delta)
            }
            is ToolCallArgumentsDelta -> mutateBlock(event.turnId, event.blockId) { block ->
                (block as IlToolCallBlock).copy(argumentsJson = block.argumentsJson + event.delta)
            }
            is ToolCallCompleted -> completeToolCall(event)
            is ToolResultAdded -> addToolResult(event)
            is UsageUpdated -> updateUsage(event)
            is TurnCompleted -> completeTurn(event)
            is StreamError -> failTurn(event.turnId, event.message, event.meta)
        }
    }

    private fun startTurn(event: TurnStarted) {
        session = session.copy(
            turns = session.turns + IlConversationTurn(
                id = event.turnId,
                role = event.role,
                blocks = emptyList(),
                status = IlTurnStatus.IN_PROGRESS,
                usage = null,
                finish = null,
                willContinue = false,
                meta = event.meta,
            )
        )
    }

    private fun startBlock(event: BlockStarted) {
        mutateTurn(event.turnId) { turn ->
            turn.copy(blocks = turn.blocks + newBlock(event))
        }
    }

    private fun newBlock(event: BlockStarted): IlBlock = when (event.kind) {
        IlBlockKind.TEXT -> IlTextBlock(event.blockId, "", IlBlockStatus.IN_PROGRESS, event.meta)
        IlBlockKind.THINKING -> IlThinkingBlock(event.blockId, "", event.visibility ?: IlThinkingVisibility.FULL, null, IlBlockStatus.IN_PROGRESS, event.meta)
        IlBlockKind.REFUSAL -> IlRefusalBlock(event.blockId, "", IlBlockStatus.IN_PROGRESS, event.meta)
        IlBlockKind.TOOL_CALL -> IlToolCallBlock(event.blockId, event.initialCallId, event.initialToolName, "", status = IlBlockStatus.IN_PROGRESS, meta = event.meta)
        IlBlockKind.TOOL_RESULT -> IlToolResultBlock(event.blockId, event.initialCallId.orEmpty(), event.initialToolName.orEmpty(), "", false, IlBlockStatus.IN_PROGRESS, event.meta)
        IlBlockKind.OPAQUE -> IlProviderOpaqueBlock(event.blockId, event.meta.rawType, event.meta.rawPayload ?: Json.parseToJsonElement("{}"), IlBlockStatus.IN_PROGRESS, event.meta)
    }

    private fun completeToolCall(event: ToolCallCompleted) {
        mutateBlock(event.turnId, event.blockId) { block ->
            val tool = block as IlToolCallBlock
            try {
                if (tool.argumentsJson.isNotBlank()) {
                    Json.parseToJsonElement(tool.argumentsJson)
                }
                tool.copy(status = IlBlockStatus.COMPLETED)
            } catch (_: Exception) {
                failTurn(event.turnId, "Invalid tool JSON for block ${event.blockId}", event.meta)
                tool.copy(status = IlBlockStatus.ERRORED)
            }
        }
    }

    private fun addToolResult(event: ToolResultAdded) {
        val existing = session.turns.flatMap { it.blocks }.filterIsInstance<IlToolResultBlock>().any { it.callId == event.callId }
        if (existing) {
            failTurn(event.turnId, "Duplicate tool result for callId ${event.callId}", event.meta)
            return
        }
        mutateTurn(event.turnId) { turn ->
            turn.copy(
                blocks = turn.blocks + IlToolResultBlock(
                    id = "${event.callId}-result",
                    callId = event.callId,
                    toolName = event.toolName,
                    resultJson = event.resultJson,
                    isError = event.isError,
                    status = IlBlockStatus.COMPLETED,
                    meta = event.meta,
                )
            )
        }
    }

    private fun updateUsage(event: UsageUpdated) {
        mutateTurn(event.turnId) { turn ->
            val usage = when (event.mode) {
                UsageMergeMode.REPLACE -> event.usage
                UsageMergeMode.INCREMENT -> {
                    val current = turn.usage ?: IlUsage()
                    IlUsage(
                        inputTokens = current.inputTokens + event.usage.inputTokens,
                        outputTokens = current.outputTokens + event.usage.outputTokens,
                        totalTokens = current.totalTokens + event.usage.totalTokens,
                    )
                }
            }
            turn.copy(usage = usage)
        }
    }

    private fun completeTurn(event: TurnCompleted) {
        mutateTurn(event.turnId) { turn ->
            val unresolvedToolCall = turn.blocks.filterIsInstance<IlToolCallBlock>().any { call ->
                session.turns.flatMap { it.blocks }
                    .filterIsInstance<IlToolResultBlock>()
                    .none { it.callId == call.callId }
            }
            val finishReason = if (unresolvedToolCall) {
                IlFinishReason.TOOL_CALL
            } else {
                event.finishReason
            }
            turn.copy(
                status = IlTurnStatus.COMPLETED,
                finish = IlFinish(finishReason, event.rawReason),
                willContinue = unresolvedToolCall || event.willContinue,
                blocks = turn.blocks.map { completeBlock(it) }
            )
        }
    }

    private fun completeBlock(block: IlBlock): IlBlock = when (block) {
        is IlTextBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
        is IlThinkingBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
        is IlRefusalBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
        is IlToolCallBlock -> block
        is IlToolResultBlock -> block
        is IlProviderOpaqueBlock -> block
    }

    private fun failTurn(turnId: String, message: String, meta: net.alkalines.radiumcode.agent.il.IlMeta) {
        mutateTurn(turnId) { turn ->
            turn.copy(status = IlTurnStatus.FAILED, error = IlTurnError(message, meta))
        }
    }

    private fun mutateTurn(turnId: String, transform: (IlConversationTurn) -> IlConversationTurn) {
        session = session.copy(turns = session.turns.map { if (it.id == turnId) transform(it) else it })
    }

    private fun mutateBlock(turnId: String, blockId: String, transform: (IlBlock) -> IlBlock) {
        mutateTurn(turnId) { turn ->
            turn.copy(blocks = turn.blocks.map { if (it.id == blockId) transform(it) else it })
        }
    }
}
