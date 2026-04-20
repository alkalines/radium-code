package net.alkalines.radiumcode.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.alkalines.radiumcode.agent.il.IlBlockKind
import net.alkalines.radiumcode.agent.il.IlBlockStatus
import net.alkalines.radiumcode.agent.il.IlFinishReason
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingBlock
import net.alkalines.radiumcode.agent.il.IlThinkingVisibility
import net.alkalines.radiumcode.agent.il.IlToolCallBlock
import net.alkalines.radiumcode.agent.il.IlTurnStatus
import net.alkalines.radiumcode.agent.il.IlUsage
import net.alkalines.radiumcode.agent.il.BlockStarted
import net.alkalines.radiumcode.agent.il.TextDelta
import net.alkalines.radiumcode.agent.il.ThinkingDelta
import net.alkalines.radiumcode.agent.il.ToolCallArgumentsDelta
import net.alkalines.radiumcode.agent.il.ToolCallCompleted
import net.alkalines.radiumcode.agent.il.ToolResultAdded
import net.alkalines.radiumcode.agent.il.TurnCompleted
import net.alkalines.radiumcode.agent.il.TurnStarted
import net.alkalines.radiumcode.agent.il.UsageMergeMode
import net.alkalines.radiumcode.agent.il.UsageUpdated

class AgentStreamReducerTest {

    @Test
    fun `reconstructs text and thinking blocks by block id in wire order`() {
        val reducer = AgentStreamReducer()

        reducer.apply(turnStarted())
        reducer.apply(blockStarted("text-1", IlBlockKind.TEXT))
        reducer.apply(TextDelta(eventId = "e1", turnId = TURN_ID, blockId = "text-1", delta = "Hello", meta = meta()))
        reducer.apply(blockStarted("thinking-1", IlBlockKind.THINKING, visibility = IlThinkingVisibility.SUMMARY))
        reducer.apply(ThinkingDelta(eventId = "e2", turnId = TURN_ID, blockId = "thinking-1", delta = "Plan", visibility = IlThinkingVisibility.SUMMARY, meta = meta()))
        reducer.apply(TextDelta(eventId = "e3", turnId = TURN_ID, blockId = "text-1", delta = " world", meta = meta()))

        val session = reducer.session
        val assistantTurn = session.turns.single()

        assertEquals(IlRole.ASSISTANT, assistantTurn.role)
        assertEquals(listOf("text-1", "thinking-1"), assistantTurn.blocks.map { it.id })
        assertEquals("Hello world", assertIs<IlTextBlock>(assistantTurn.blocks[0]).text)
        val thinkingBlock = assertIs<IlThinkingBlock>(assistantTurn.blocks[1])
        assertEquals("Plan", thinkingBlock.text)
        assertEquals(IlThinkingVisibility.SUMMARY, thinkingBlock.visibility)
    }

    @Test
    fun `preserves reasoning continuity payload in reference payload`() {
        val reducer = AgentStreamReducer()
        val reasoningPayload = Json.parseToJsonElement("""{"type":"reasoning.encrypted","data":"abc"}""")

        reducer.apply(turnStarted())
        reducer.apply(blockStarted("thinking-1", IlBlockKind.THINKING, visibility = IlThinkingVisibility.FULL))
        reducer.apply(
            ThinkingDelta(
                eventId = "e-thinking",
                turnId = TURN_ID,
                blockId = "thinking-1",
                delta = "Hidden",
                visibility = IlThinkingVisibility.FULL,
                meta = meta(rawPayload = reasoningPayload)
            )
        )

        val thinkingBlock = assertIs<IlThinkingBlock>(reducer.session.turns.single().blocks.single())
        assertEquals(reasoningPayload, thinkingBlock.referencePayload)
    }

    @Test
    fun `tool call completion waits for valid json and unresolved tool call keeps willContinue true`() {
        val reducer = AgentStreamReducer()

        reducer.apply(turnStarted())
        reducer.apply(blockStarted("tool-1", IlBlockKind.TOOL_CALL, initialToolName = "lookup", initialCallId = "call-1"))
        reducer.apply(ToolCallArgumentsDelta(eventId = "e1", turnId = TURN_ID, blockId = "tool-1", delta = "{\"query\":\"hel", meta = meta()))
        reducer.apply(ToolCallArgumentsDelta(eventId = "e2", turnId = TURN_ID, blockId = "tool-1", delta = "lo\"}", meta = meta()))
        reducer.apply(ToolCallCompleted(eventId = "e3", turnId = TURN_ID, blockId = "tool-1", meta = meta()))
        reducer.apply(TurnCompleted(eventId = "e4", turnId = TURN_ID, finishReason = IlFinishReason.STOP, rawReason = "completed", willContinue = false, meta = meta()))

        val turn = reducer.session.turns.single()
        val toolCall = assertIs<IlToolCallBlock>(turn.blocks.single())

        assertEquals("{\"query\":\"hello\"}", toolCall.argumentsJson)
        assertEquals(IlBlockStatus.COMPLETED, toolCall.status)
        assertEquals(IlFinishReason.TOOL_CALL, turn.finish?.reason)
        assertTrue(turn.willContinue)
    }

    @Test
    fun `duplicate tool result emits stream error and usage replace wins`() {
        val reducer = AgentStreamReducer()

        reducer.apply(turnStarted())
        reducer.apply(UsageUpdated(eventId = "usage-1", turnId = TURN_ID, usage = IlUsage(inputTokens = 2, outputTokens = 1, totalTokens = 3), mode = UsageMergeMode.REPLACE, meta = meta()))
        reducer.apply(ToolResultAdded(eventId = "r1", turnId = TURN_ID, callId = "call-1", toolName = "lookup", resultJson = "{\"ok\":true}", isError = false, meta = meta()))
        reducer.apply(ToolResultAdded(eventId = "r2", turnId = TURN_ID, callId = "call-1", toolName = "lookup", resultJson = "{\"ok\":false}", isError = false, meta = meta()))

        val turn = reducer.session.turns.single()

        assertEquals(3, turn.usage?.totalTokens)
        assertEquals(IlTurnStatus.FAILED, turn.status)
        assertNotNull(turn.error)
        assertTrue(turn.error!!.message.contains("Duplicate tool result"))
    }

    private fun turnStarted() = TurnStarted(
        eventId = "turn-start",
        turnId = TURN_ID,
        role = IlRole.ASSISTANT,
        meta = meta()
    )

    private fun blockStarted(
        blockId: String,
        kind: IlBlockKind,
        visibility: IlThinkingVisibility? = null,
        initialToolName: String? = null,
        initialCallId: String? = null,
    ) = BlockStarted(
        eventId = "block-$blockId",
        turnId = TURN_ID,
        blockId = blockId,
        kind = kind,
        visibility = visibility,
        initialToolName = initialToolName,
        initialCallId = initialCallId,
        meta = meta()
    )

    private fun meta(rawPayload: kotlinx.serialization.json.JsonElement? = null) = IlMeta(
        providerId = "openrouter",
        rawType = "test",
        providerExtras = JsonObject(emptyMap()),
        rawPayload = rawPayload,
        receivedAt = 1L,
        sequenceNumber = 1L
    )

    private companion object {
        const val TURN_ID = "turn-1"
    }
}
