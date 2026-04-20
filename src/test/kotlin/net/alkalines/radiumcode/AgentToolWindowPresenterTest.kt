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
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingBlock
import net.alkalines.radiumcode.agent.il.IlThinkingVisibility
import net.alkalines.radiumcode.agent.il.IlTurnError
import net.alkalines.radiumcode.agent.il.IlTurnStatus
import net.alkalines.radiumcode.agent.providers.AgentProvider
import net.alkalines.radiumcode.agent.providers.ProviderRegistry

class AgentToolWindowPresenterTest {

    @Test
    fun `builds the compact selector label from the registry default model`() {
        val registry = ProviderRegistry.fromProviders(listOf(TestProvider()))

        assertEquals("z-ai/glm-4.5-air:free", AgentToolWindowPresenter.modelLabel(registry))
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
        assertEquals(AgentChatItem.Kind.THINKING, items[1].kind)
        assertEquals("Planning", items[1].text)
        assertEquals(AgentChatItem.Kind.TEXT, items[2].kind)
        assertEquals("Answer", items[2].text)
        assertTrue(items[3].text.contains("OpenRouterProvider"))
    }

    @Test
    fun `auto scroll signal changes as streaming text grows and items are added`() {
        val empty = emptyList<AgentChatItem>()
        val oneShort = listOf(AgentChatItem(IlRole.ASSISTANT, "hi", AgentChatItem.Kind.TEXT, AgentChatItem.Alignment.START))
        val oneLonger = listOf(AgentChatItem(IlRole.ASSISTANT, "hi there", AgentChatItem.Kind.TEXT, AgentChatItem.Alignment.START))
        val twoItems = oneShort + AgentChatItem(IlRole.ASSISTANT, "x", AgentChatItem.Kind.TEXT, AgentChatItem.Alignment.START)

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

    private class TestProvider : AgentProvider() {
        override val providerId = "openrouter"
        override val displayName = "OpenRouter"
        override val models = listOf(
            IlModelDescriptor(
                providerId = providerId,
                modelId = "z-ai/glm-4.5-air:free",
                displayName = "OpenRouter",
                capabilities = setOf(IlCapability.TEXT, IlCapability.THINKING, IlCapability.STREAMING),
                isDefault = true,
            )
        )

        override fun stream(request: net.alkalines.radiumcode.agent.il.IlGenerateRequest) =
            throw UnsupportedOperationException("not needed for presenter test")
    }
}
