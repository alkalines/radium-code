package net.alkalines.radiumcode

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
