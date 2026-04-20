package net.alkalines.radiumcode.agent.runtime

import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlConversationTurn

data class AgentSessionState(
    val session: IlConversationSession = IlConversationSession(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
) {
    val turns: List<IlConversationTurn>
        get() = session.turns

    val isStreaming: Boolean
        get() = session.turns.lastOrNull()?.status == net.alkalines.radiumcode.agent.il.IlTurnStatus.IN_PROGRESS
}
