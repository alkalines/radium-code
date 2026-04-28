package net.alkalines.radiumcode.agent.providers

import kotlinx.coroutines.flow.Flow
import net.alkalines.radiumcode.agent.config.ProviderSettings
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlStreamEvent

abstract class AgentProvider {
    abstract val providerId: String
    abstract val displayName: String

    abstract suspend fun fetchAvailableModels(settings: ProviderSettings): Result<List<IlModelDescriptor>>

    abstract fun stream(request: IlGenerateRequest): Flow<IlStreamEvent>
}
