package net.alkalines.radiumcode

import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlRefusalBlock
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.il.IlTextBlock
import net.alkalines.radiumcode.agent.il.IlThinkingBlock
import net.alkalines.radiumcode.agent.il.IlToolCallBlock
import net.alkalines.radiumcode.agent.il.IlToolResultBlock
import net.alkalines.radiumcode.agent.providers.ProviderRegistry

data class AgentChatItem(
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
    fun modelLabel(registry: ProviderRegistry): String {
        val model = registry.defaultModel ?: return "No model"
        return model.modelId
    }

    fun autoScrollSignal(items: List<AgentChatItem>): Int = items.sumOf { it.text.length } + items.size

    fun shouldItalicize(kind: AgentChatItem.Kind): Boolean = kind == AgentChatItem.Kind.THINKING

    fun chatItems(session: IlConversationSession): List<AgentChatItem> = buildList {
        session.turns.forEach { turn ->
            turn.blocks.forEach { block ->
                when (block) {
                    is IlTextBlock -> add(AgentChatItem(turn.role, block.text, AgentChatItem.Kind.TEXT, turn.role.alignment()))
                    is IlThinkingBlock -> add(AgentChatItem(turn.role, block.text, AgentChatItem.Kind.THINKING, turn.role.alignment()))
                    is IlToolCallBlock -> add(AgentChatItem(turn.role, block.toolName ?: "Tool call", AgentChatItem.Kind.TOOL, turn.role.alignment()))
                    is IlToolResultBlock -> add(AgentChatItem(turn.role, block.outputPayload, AgentChatItem.Kind.TOOL, turn.role.alignment()))
                    is IlRefusalBlock -> add(AgentChatItem(turn.role, block.text, AgentChatItem.Kind.ERROR, turn.role.alignment()))
                    else -> Unit
                }
            }
            turn.error?.let { add(AgentChatItem(turn.role, it.message, AgentChatItem.Kind.ERROR, turn.role.alignment())) }
        }
    }

    private fun IlRole.alignment(): AgentChatItem.Alignment =
        if (this == IlRole.USER) AgentChatItem.Alignment.END else AgentChatItem.Alignment.START
}
