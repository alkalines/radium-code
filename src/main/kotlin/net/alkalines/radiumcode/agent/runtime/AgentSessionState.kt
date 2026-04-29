package net.alkalines.radiumcode.agent.runtime

import net.alkalines.radiumcode.agent.il.IlConversationSession
import net.alkalines.radiumcode.agent.il.IlConversationTurn
import net.alkalines.radiumcode.agent.il.IlModelDescriptor

data class AgentSessionState(
    val session: IlConversationSession = IlConversationSession(),
    val selectedModel: IlModelDescriptor? = null,
    val activeRunId: String? = null,
    val activeAssistantTurnId: String? = null,
    val hasUsableSelection: Boolean = false,
    val inlineError: String? = null,
) {
    val selectedProviderId: String? get() = selectedModel?.providerId
    val selectedModelId: String? get() = selectedModel?.id

    val turns: List<IlConversationTurn>
        get() = session.turns

    val isStreaming: Boolean
        get() = activeRunId != null
}
