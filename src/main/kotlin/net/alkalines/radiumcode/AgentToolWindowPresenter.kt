package net.alkalines.radiumcode

import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlRefusalBlock
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingBlock
import net.alkalines.radiumcode.agent.il.IlToolCallBlock
import net.alkalines.radiumcode.agent.il.IlToolResultBlock

data class AgentChatItem(
    val id: String,
    val role: IlRole,
    val text: String,
    val kind: Kind,
    val alignment: Alignment,
) {
    enum class Kind {
        TEXT,
        THINKING,
        TOOL,
        ERROR,
    }

    enum class Alignment {
        START,
        END,
    }
}

object AgentToolWindowPresenter {
    const val NO_CONFIGURED_MODEL_LABEL: String = "No configured model"

    fun modelLabel(selectedModel: IlModelDescriptor?): String =
        selectedModel?.displayName ?: NO_CONFIGURED_MODEL_LABEL

    fun autoScrollSignal(items: List<AgentChatItem>): Int = items.sumOf { it.text.length } + items.size

    fun shouldItalicize(kind: AgentChatItem.Kind): Boolean = kind == AgentChatItem.Kind.THINKING

    fun chatItems(session: IlConversationSession): List<AgentChatItem> = buildList {
        session.turns.forEach { turn ->
            turn.blocks.forEach { block ->
                when (block) {
                    is IlTextBlock -> add(AgentChatItem(chatItemId(turn.id, block.id), turn.role, block.text, AgentChatItem.Kind.TEXT, turn.role.alignment()))
                    is IlThinkingBlock -> add(AgentChatItem(chatItemId(turn.id, block.id), turn.role, block.text, AgentChatItem.Kind.THINKING, turn.role.alignment()))
                    is IlToolCallBlock -> add(AgentChatItem(chatItemId(turn.id, block.id), turn.role, block.toolName ?: "Tool call", AgentChatItem.Kind.TOOL, turn.role.alignment()))
                    is IlToolResultBlock -> add(AgentChatItem(chatItemId(turn.id, block.id), turn.role, block.outputPayload, AgentChatItem.Kind.TOOL, turn.role.alignment()))
                    is IlRefusalBlock -> add(AgentChatItem(chatItemId(turn.id, block.id), turn.role, block.text, AgentChatItem.Kind.ERROR, turn.role.alignment()))
                    else -> Unit
                }
            }
            turn.error?.let { add(AgentChatItem(chatItemId(turn.id, "error"), turn.role, it.message, AgentChatItem.Kind.ERROR, turn.role.alignment())) }
        }
    }

    private fun chatItemId(turnId: String, blockId: String): String = "$turnId:$blockId"

    private fun IlRole.alignment(): AgentChatItem.Alignment =
        if (this == IlRole.USER) AgentChatItem.Alignment.END else AgentChatItem.Alignment.START
}
