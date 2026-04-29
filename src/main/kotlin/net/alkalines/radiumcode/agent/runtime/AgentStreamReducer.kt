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
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlProviderOpaqueBlock
import net.alkalines.radiumcode.agent.il.IlRefusalBlock
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
    fun apply(session: IlConversationSession, event: Any): IlConversationSession = when (event) {
        is TurnStarted -> startTurn(session, event)
        is BlockStarted -> startBlock(session, event)
        is TextDelta -> mutateBlock(session, event.turnId, event.blockId) { block ->
            (block as IlTextBlock).copy(text = block.text + event.delta)
        }

        is ThinkingDelta -> mutateBlock(session, event.turnId, event.blockId) { block ->
            (block as IlThinkingBlock).copy(
                text = block.text + event.delta,
                referencePayload = block.referencePayload ?: event.meta.rawPayload
            )
        }

        is RefusalDelta -> mutateBlock(session, event.turnId, event.blockId) { block ->
            (block as IlRefusalBlock).copy(text = block.text + event.delta)
        }

        is ToolCallArgumentsDelta -> mutateBlock(session, event.turnId, event.blockId) { block ->
            (block as IlToolCallBlock).copy(argumentsJson = block.argumentsJson + event.delta)
        }

        is ToolCallCompleted -> completeToolCall(session, event)
        is ToolResultAdded -> addToolResult(session, event)
        is UsageUpdated -> updateUsage(session, event)
        is TurnCompleted -> completeTurn(session, event)
        is StreamError -> failTurn(session, event.turnId, event.message, event.meta)
        else -> session
    }

    fun pendingToolCalls(session: IlConversationSession, assistantTurnId: String): List<IlToolCallBlock> {
        val assistantTurn = session.turns.firstOrNull { it.id == assistantTurnId } ?: return emptyList()
        val resolvedCallIds = resolvedCallIdsForTurn(session, assistantTurn)
        return assistantTurn.blocks.filterIsInstance<IlToolCallBlock>().filter { call ->
            call.effectiveCallId() !in resolvedCallIds
        }
    }

    private fun startTurn(session: IlConversationSession, event: TurnStarted): IlConversationSession {
        if (session.turns.any { it.id == event.turnId }) {
            return session
        }
        return session.copy(
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

    private fun startBlock(session: IlConversationSession, event: BlockStarted): IlConversationSession =
        mutateTurn(session, event.turnId) { turn ->
            turn.copy(blocks = turn.blocks + newBlock(event))
        }

    private fun newBlock(event: BlockStarted): IlBlock = when (event.kind) {
        IlBlockKind.TEXT -> IlTextBlock(event.blockId, "", IlBlockStatus.IN_PROGRESS, event.meta)
        IlBlockKind.THINKING -> IlThinkingBlock(event.blockId, "", event.visibility ?: IlThinkingVisibility.FULL, null, IlBlockStatus.IN_PROGRESS, event.meta)
        IlBlockKind.REFUSAL -> IlRefusalBlock(event.blockId, "", IlBlockStatus.IN_PROGRESS, event.meta)
        IlBlockKind.TOOL_CALL -> IlToolCallBlock(event.blockId, event.initialCallId, event.initialToolName, "", status = IlBlockStatus.IN_PROGRESS, meta = event.meta)
        IlBlockKind.TOOL_RESULT -> IlToolResultBlock(event.blockId, event.initialCallId.orEmpty(), event.initialToolName.orEmpty(), "", false, IlBlockStatus.IN_PROGRESS, event.meta)
        IlBlockKind.OPAQUE -> IlProviderOpaqueBlock(event.blockId, event.meta.rawType, event.meta.rawPayload ?: Json.parseToJsonElement("{}"), IlBlockStatus.IN_PROGRESS, event.meta)
    }

    private fun completeToolCall(session: IlConversationSession, event: ToolCallCompleted): IlConversationSession =
        mutateTurn(session, event.turnId) { turn ->
            val block = turn.blocks.firstOrNull { it.id == event.blockId } as? IlToolCallBlock ?: return@mutateTurn turn
            val isValid = runCatching {
                if (block.argumentsJson.isNotBlank()) {
                    Json.parseToJsonElement(block.argumentsJson)
                }
            }.isSuccess
            turn.copy(
                blocks = turn.blocks.map { existing ->
                    if (existing.id == event.blockId) {
                        block.copy(status = if (isValid) IlBlockStatus.COMPLETED else IlBlockStatus.ERRORED)
                    } else {
                        existing
                    }
                },
                status = if (isValid) turn.status else IlTurnStatus.FAILED,
                error = if (isValid) turn.error else IlTurnError("Invalid tool JSON for block ${event.blockId}", event.meta)
            )
        }

    private fun addToolResult(session: IlConversationSession, event: ToolResultAdded): IlConversationSession {
        val assistantTurn = session.turns.firstOrNull { it.id == event.turnId } ?: return session
        val assistantCallIds = assistantTurn.blocks.filterIsInstance<IlToolCallBlock>().map { it.effectiveCallId() }.toSet()
        if (event.callId !in assistantCallIds) {
            return failTurn(session, assistantTurn.id, "Unknown tool result for callId ${event.callId}", event.meta)
        }
        val pendingCallIds = pendingToolCalls(session, assistantTurn.id).map { it.effectiveCallId() }.toSet()
        if (event.callId !in pendingCallIds) {
            return failTurn(session, assistantTurn.id, "Duplicate tool result for callId ${event.callId}", event.meta)
        }

        val toolTurn = IlConversationTurn.toolResult(
            id = "tool-${event.callId}",
            callId = event.callId,
            toolName = event.toolName,
            outputPayload = event.outputPayload,
            isError = event.isError,
            meta = event.meta,
        )
        val assistantIndex = session.turns.indexOfFirst { it.id == assistantTurn.id }
        if (assistantIndex == -1) {
            return session
        }
        var insertIndex = assistantIndex + 1
        while (insertIndex < session.turns.size && session.turns[insertIndex].role == net.alkalines.radiumcode.agent.il.IlRole.TOOL) {
            insertIndex++
        }
        val updatedTurns = session.turns.toMutableList().apply { add(insertIndex, toolTurn) }
        return session.copy(turns = updatedTurns)
    }

    private fun updateUsage(session: IlConversationSession, event: UsageUpdated): IlConversationSession =
        mutateTurn(session, event.turnId) { turn ->
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

    private fun completeTurn(session: IlConversationSession, event: TurnCompleted): IlConversationSession =
        mutateTurn(session, event.turnId) { turn ->
            val unresolvedToolCall = pendingToolCalls(session, turn.id).isNotEmpty()
            val finishReason = if (unresolvedToolCall) IlFinishReason.TOOL_CALL else event.finishReason
            turn.copy(
                status = if (turn.status == IlTurnStatus.CANCELLED) IlTurnStatus.CANCELLED else IlTurnStatus.COMPLETED,
                finish = IlFinish(finishReason, event.rawReason),
                willContinue = unresolvedToolCall || event.willContinue,
                blocks = turn.blocks.map { completeBlock(it) }
            )
        }

    private fun completeBlock(block: IlBlock): IlBlock = when (block) {
        is IlTextBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
        is IlThinkingBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
        is IlRefusalBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
        is IlToolCallBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
        is IlToolResultBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
        is IlProviderOpaqueBlock -> block.copy(status = if (block.status == IlBlockStatus.IN_PROGRESS) IlBlockStatus.COMPLETED else block.status)
    }

    private fun failTurn(
        session: IlConversationSession,
        turnId: String,
        message: String,
        meta: IlMeta,
    ): IlConversationSession = mutateTurn(session, turnId) { turn ->
        turn.copy(status = IlTurnStatus.FAILED, error = IlTurnError(message, meta))
    }

    private fun mutateTurn(
        session: IlConversationSession,
        turnId: String,
        transform: (IlConversationTurn) -> IlConversationTurn,
    ): IlConversationSession =
        session.copy(turns = session.turns.map { if (it.id == turnId) transform(it) else it })

    private fun mutateBlock(
        session: IlConversationSession,
        turnId: String,
        blockId: String,
        transform: (IlBlock) -> IlBlock,
    ): IlConversationSession = mutateTurn(session, turnId) { turn ->
        turn.copy(blocks = turn.blocks.map { if (it.id == blockId) transform(it) else it })
    }

    private fun resolvedCallIdsForTurn(
        session: IlConversationSession,
        turn: IlConversationTurn,
    ): Set<String> {
        val assistantCallIds = turn.blocks.filterIsInstance<IlToolCallBlock>().map { it.effectiveCallId() }.toSet()
        if (assistantCallIds.isEmpty()) {
            return emptySet()
        }
        return session.turns
            .asSequence()
            .filter { it.role == net.alkalines.radiumcode.agent.il.IlRole.TOOL }
            .flatMap { it.blocks.asSequence() }
            .filterIsInstance<IlToolResultBlock>()
            .map { it.callId }
            .filter { it in assistantCallIds }
            .toSet()
    }

    private fun IlToolCallBlock.effectiveCallId(): String = callId ?: id
}
