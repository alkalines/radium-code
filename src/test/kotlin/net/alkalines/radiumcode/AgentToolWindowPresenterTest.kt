package net.alkalines.radiumcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlMeta
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlModelSource
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingBlock
import net.alkalines.radiumcode.agent.il.IlThinkingVisibility
import net.alkalines.radiumcode.agent.il.IlTurnError
import net.alkalines.radiumcode.agent.il.IlTurnStatus

class AgentToolWindowPresenterTest {

    @Test
    fun `model label uses display name when a model is selected`() {
        val descriptor = IlModelDescriptor(
            id = "id-1",
            providerId = "openrouter",
            modelId = "z-ai/glm-4.5-air:free",
            displayName = "GLM 4.5 Air",
            maxInputTokens = null,
            maxOutputTokens = null,
            inputPricePerToken = null,
            outputPricePerToken = null,
            cacheReadPricePerToken = null,
            cacheWritePricePerToken = null,
            capabilities = setOf(IlCapability.TEXT, IlCapability.THINKING, IlCapability.STREAMING),
            reasoningEffort = null,
            source = IlModelSource.MANUAL,
        )

        assertEquals("GLM 4.5 Air", AgentToolWindowPresenter.modelLabel(descriptor))
    }

    @Test
    fun `model label falls back to no-configured-model when nothing selected`() {
        assertEquals(
            AgentToolWindowPresenter.NO_CONFIGURED_MODEL_LABEL,
            AgentToolWindowPresenter.modelLabel(null)
        )
    }

    @Test
    fun `exposes assistant thinking text as a thinking chat item before the final answer`() {
        val session = IlConversationSession(
            turns = listOf(
                IlConversationTurn.userText("user-1", "Hello"),
                IlConversationTurn(
                    id = "assistant-1",
                    role = IlRole.ASSISTANT,
                    blocks = listOf(
                        IlThinkingBlock("thinking-1", "Planning", IlThinkingVisibility.SUMMARY, null, net.alkalines.radiumcode.agent.il.IlBlockStatus.COMPLETED, IlMeta.openrouter("thinking")),
                        IlTextBlock.completed("text-1", "Answer", IlMeta.openrouter("text"))
                    ),
                    status = IlTurnStatus.FAILED,
                    usage = null,
                    finish = null,
                    willContinue = false,
                    meta = IlMeta.openrouter("assistant"),
                    error = IlTurnError("OpenRouter API key not configured in OpenRouterProvider")
                )
            )
        )

        val items = AgentToolWindowPresenter.chatItems(session)

        assertEquals(4, items.size)
        assertEquals("Hello", items[0].text)
        assertEquals("assistant-1:thinking-1", items[1].id)
        assertEquals(AgentChatItem.Kind.THINKING, items[1].kind)
        assertEquals("Planning", items[1].text)
        assertEquals("assistant-1:text-1", items[2].id)
        assertEquals(AgentChatItem.Kind.TEXT, items[2].kind)
        assertEquals("Answer", items[2].text)
        assertTrue(items[3].text.contains("OpenRouterProvider"))
    }

    @Test
    fun `namespaces repeated block ids by turn id`() {
        val session = IlConversationSession(
            turns = listOf(
                IlConversationTurn(
                    id = "assistant-1",
                    role = IlRole.ASSISTANT,
                    blocks = listOf(
                        IlThinkingBlock("thinking-1", "First", IlThinkingVisibility.SUMMARY, null, net.alkalines.radiumcode.agent.il.IlBlockStatus.COMPLETED, IlMeta.openrouter("thinking"))
                    ),
                    status = IlTurnStatus.COMPLETED,
                    usage = null,
                    finish = null,
                    willContinue = false,
                    meta = IlMeta.openrouter("assistant"),
                ),
                IlConversationTurn(
                    id = "assistant-2",
                    role = IlRole.ASSISTANT,
                    blocks = listOf(
                        IlThinkingBlock("thinking-1", "Second", IlThinkingVisibility.SUMMARY, null, net.alkalines.radiumcode.agent.il.IlBlockStatus.COMPLETED, IlMeta.openrouter("thinking"))
                    ),
                    status = IlTurnStatus.COMPLETED,
                    usage = null,
                    finish = null,
                    willContinue = false,
                    meta = IlMeta.openrouter("assistant"),
                )
            )
        )

        val items = AgentToolWindowPresenter.chatItems(session)

        assertEquals("assistant-1:thinking-1", items[0].id)
        assertEquals("assistant-2:thinking-1", items[1].id)
        assertTrue(items.map { it.id }.distinct().size == items.size)
    }

    @Test
    fun `auto scroll signal changes as streaming text grows and items are added`() {
        val empty = emptyList<AgentChatItem>()
        val oneShort = listOf(AgentChatItem("text-1", IlRole.ASSISTANT, "hi", AgentChatItem.Kind.TEXT, AgentChatItem.Alignment.START))
        val oneLonger = listOf(AgentChatItem("text-1", IlRole.ASSISTANT, "hi there", AgentChatItem.Kind.TEXT, AgentChatItem.Alignment.START))
        val twoItems = oneShort + AgentChatItem("text-2", IlRole.ASSISTANT, "x", AgentChatItem.Kind.TEXT, AgentChatItem.Alignment.START)

        val emptySignal = AgentToolWindowPresenter.autoScrollSignal(empty)
        val shortSignal = AgentToolWindowPresenter.autoScrollSignal(oneShort)
        val longerSignal = AgentToolWindowPresenter.autoScrollSignal(oneLonger)
        val twoSignal = AgentToolWindowPresenter.autoScrollSignal(twoItems)

        assertEquals(0, emptySignal)
        assertTrue(shortSignal > emptySignal, "signal must increase when content appears")
        assertTrue(longerSignal > shortSignal, "signal must increase when text grows inside the same item")
        assertTrue(twoSignal > shortSignal, "signal must increase when a new item is appended")
    }

    @Test
    fun `italicises thinking chat items and keeps others upright`() {
        assertTrue(AgentToolWindowPresenter.shouldItalicize(AgentChatItem.Kind.THINKING))
        assertFalse(AgentToolWindowPresenter.shouldItalicize(AgentChatItem.Kind.TEXT))
        assertFalse(AgentToolWindowPresenter.shouldItalicize(AgentChatItem.Kind.TOOL))
        assertFalse(AgentToolWindowPresenter.shouldItalicize(AgentChatItem.Kind.ERROR))
    }

    @Test
    fun `marks user messages for trailing bubble alignment`() {
        val session = IlConversationSession(
            turns = listOf(
                IlConversationTurn.userText("user-1", "Hello"),
                IlConversationTurn(
                    id = "assistant-1",
                    role = IlRole.ASSISTANT,
                    blocks = listOf(IlTextBlock.completed("text-1", "Answer", IlMeta.openrouter("text"))),
                    status = IlTurnStatus.COMPLETED,
                    usage = null,
                    finish = null,
                    willContinue = false,
                    meta = IlMeta.openrouter("assistant"),
                )
            )
        )

        val items = AgentToolWindowPresenter.chatItems(session)

        assertEquals(AgentChatItem.Alignment.END, items[0].alignment)
        assertEquals(AgentChatItem.Alignment.START, items[1].alignment)
    }

}
