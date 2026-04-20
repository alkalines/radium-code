package net.alkalines.radiumcode.agent.providers

import kotlinx.coroutines.flow.Flow
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlStreamEvent

abstract class AgentProvider {
    abstract val providerId: String
    abstract val displayName: String
    abstract val models: List<IlModelDescriptor>

    open fun supports(modelId: String): Boolean = models.any { it.modelId == modelId }

    abstract fun stream(request: IlGenerateRequest): Flow<IlStreamEvent>
}
