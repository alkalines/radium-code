package net.alkalines.radiumcode.agent.runtime

import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlConversationTurn

data class AgentSessionState(
    val session: IlConversationSession = IlConversationSession(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val activeRunId: String? = null,
    val activeAssistantTurnId: String? = null,
    val hasUsableSelection: Boolean = false,
    val inlineError: String? = null,
) {
    val turns: List<IlConversationTurn>
        get() = session.turns

    val isStreaming: Boolean
        get() = activeRunId != null
}
