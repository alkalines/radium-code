package net.alkalines.radiumcode

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.intellij.openapi.Disposable
import net.alkalines.radiumcode.agent.runtime.SubmitPromptResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolWindowFactoryTest {

    @Test
    fun `omits the compose tab title to avoid duplicating the stripe title`() {
        assertNull(AgentToolWindowChrome.composeTabTitle())
    }

    @Test
    fun `starts on the chat route`() {
        assertEquals(AgentToolWindowRoute.CHAT, AgentToolWindowRoute.initial())
    }

    @Test
    fun `selects config route from the toolbar action`() {
        assertEquals(AgentToolWindowRoute.CONFIG, AgentToolWindowRoute.showConfig())
    }

    @Test
    fun `toggles config route back to chat`() {
        assertEquals(AgentToolWindowRoute.CONFIG, AgentToolWindowRoute.toggleConfig(AgentToolWindowRoute.CHAT))
        assertEquals(AgentToolWindowRoute.CHAT, AgentToolWindowRoute.toggleConfig(AgentToolWindowRoute.CONFIG))
    }

    @Test
    fun `config mock label is config`() {
        assertEquals("config", AgentConfigToolWindowContentModel.mockLabel())
    }

    @Test
    fun `chat tool window state is disposable`() {
        assertTrue(Disposable::class.java.isAssignableFrom(AgentChatToolWindowState::class.java))
    }

    @Test
    fun `config toolbar tooltip is config`() {
        assertEquals("Config", AgentToolWindowToolbarModel.configTooltip())
    }

    @Test
    fun `config toolbar button background is only visible during interaction`() {
        assertFalse(AgentToolWindowToolbarModel.showsConfigButtonBackground(isHovered = false, isPressed = false))
        assertTrue(AgentToolWindowToolbarModel.showsConfigButtonBackground(isHovered = true, isPressed = false))
        assertTrue(AgentToolWindowToolbarModel.showsConfigButtonBackground(isHovered = false, isPressed = true))
    }

    @Test
    fun `config toolbar button uses pressed background while pressed`() {
        assertEquals(
            AgentToolWindowButtonBackground.NONE,
            AgentToolWindowToolbarModel.configButtonBackground(isHovered = false, isPressed = false)
        )
        assertEquals(
            AgentToolWindowButtonBackground.HOVER,
            AgentToolWindowToolbarModel.configButtonBackground(isHovered = true, isPressed = false)
        )
        assertEquals(
            AgentToolWindowButtonBackground.PRESSED,
            AgentToolWindowToolbarModel.configButtonBackground(isHovered = true, isPressed = true)
        )
    }

    @Test
    fun `submits the prompt when enter is pressed without shift`() {
        assertTrue(
            shouldSubmitPromptFromKeyEvent(
                prompt = TextFieldValue("hello"),
                key = Key.Enter,
                type = KeyEventType.KeyDown,
                isShiftPressed = false
            )
        )
    }

    @Test
    fun `keeps multiline editing when shift enter is pressed`() {
        assertFalse(
            shouldSubmitPromptFromKeyEvent(
                prompt = TextFieldValue("hello"),
                key = Key.Enter,
                type = KeyEventType.KeyDown,
                isShiftPressed = true
            )
        )
    }

    @Test
    fun `does not submit the prompt while ime composition is active`() {
        assertFalse(
            shouldSubmitPromptFromKeyEvent(
                prompt = TextFieldValue("hello", composition = TextRange(0, 5)),
                key = Key.Enter,
                type = KeyEventType.KeyDown,
                isShiftPressed = false
            )
        )
    }

    @Test
    fun `does not insert a newline while ime composition is active`() {
        assertFalse(
            shouldInsertLineBreakFromKeyEvent(
                prompt = TextFieldValue("hello", composition = TextRange(0, 5)),
                key = Key.Enter,
                type = KeyEventType.KeyDown,
                isShiftPressed = true
            )
        )
    }

    @Test
    fun `inserts a newline at the caret when shift enter is pressed`() {
        assertEquals(
            TextFieldValue("hello\nworld", selection = TextRange(6)),
            insertedLineBreakPromptValue(
                TextFieldValue("helloworld", selection = TextRange(5))
            )
        )
    }

    @Test
    fun `replaces the selection with a newline when shift enter is pressed`() {
        assertEquals(
            TextFieldValue("ab\nef", selection = TextRange(3)),
            insertedLineBreakPromptValue(
                TextFieldValue("abcdef", selection = TextRange(2, 4))
            )
        )
    }

    @Test
    fun `clears the prompt after an accepted submission`() {
        assertEquals(TextFieldValue(""), submittedPromptValue(prompt = TextFieldValue("hello"), result = SubmitPromptResult.ACCEPTED))
    }

    @Test
    fun `keeps the prompt when submission is rejected`() {
        assertEquals(
            TextFieldValue("hello"),
            submittedPromptValue(prompt = TextFieldValue("hello"), result = SubmitPromptResult.REJECTED_BLANK)
        )
    }

    @Test
    fun `shows stop in the trailing composer slot while streaming`() {
        assertEquals(
            ComposerTrailingButton.STOP,
            composerTrailingButton(isStreaming = true)
        )
    }

    @Test
    fun `shows send in the trailing composer slot when streaming ends`() {
        assertEquals(
            ComposerTrailingButton.SEND,
            composerTrailingButton(isStreaming = false)
        )
    }

    @Test
    fun `does not show model menu when no configured models exist`() {
        assertFalse(shouldShowModelMenu(isExpanded = true, configuredModelCount = 0))
    }

    @Test
    fun `shows model menu when expanded and configured models exist`() {
        assertTrue(shouldShowModelMenu(isExpanded = true, configuredModelCount = 1))
    }

    @Test
    fun `places model menu above selector when there is space`() {
        val offset = modelMenuOffset(
            selectorXpx = 196,
            selectorYpx = 172,
            selectorWidthPx = 160,
            selectorHeightPx = 34,
            containerXpx = 16,
            containerYpx = 12,
            menuWidthPx = 220,
            menuHeightPx = 120,
            menuGapPx = 8
        )

        assertEquals(120, offset.x)
        assertEquals(32, offset.y)
    }

    @Test
    fun `places model menu below selector when there is not enough space above`() {
        val offset = modelMenuOffset(
            selectorXpx = 196,
            selectorYpx = 92,
            selectorWidthPx = 160,
            selectorHeightPx = 34,
            containerXpx = 16,
            containerYpx = 12,
            menuWidthPx = 220,
            menuHeightPx = 120,
            menuGapPx = 8
        )

        assertEquals(120, offset.x)
        assertEquals(122, offset.y)
    }

    @Test
    fun `keeps thinking items collapsed by default`() {
        assertFalse(isThinkingExpanded(emptySet(), itemId = "thinking-2"))
    }

    @Test
    fun `toggles thinking item expansion independently by stable id`() {
        val expanded = toggledThinkingItemExpansion(emptySet(), itemId = "thinking-2")
        val collapsedAgain = toggledThinkingItemExpansion(expanded, itemId = "thinking-2")

        assertTrue(isThinkingExpanded(expanded, itemId = "thinking-2"))
        assertFalse(isThinkingExpanded(expanded, itemId = "thinking-3"))
        assertFalse(isThinkingExpanded(collapsedAgain, itemId = "thinking-2"))
    }

    @Test
    fun `rotates thinking chevron downward when expanded`() {
        assertEquals(0f, thinkingChevronRotation(isExpanded = false))
        assertEquals(90f, thinkingChevronRotation(isExpanded = true))
    }

    @Test
    fun `animates thinking content only when expansion state changes to open`() {
        assertFalse(shouldAnimateThinkingExpansion(wasExpanded = false, isExpanded = false))
        assertTrue(shouldAnimateThinkingExpansion(wasExpanded = false, isExpanded = true))
        assertFalse(shouldAnimateThinkingExpansion(wasExpanded = true, isExpanded = true))
    }
}
