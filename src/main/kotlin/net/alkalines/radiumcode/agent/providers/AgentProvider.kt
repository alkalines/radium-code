package net.alkalines.radiumcode.agent.providers

import kotlinx.coroutines.flow.Flow
import net.alkalines.radiumcode.agent.config.ProviderSettings
import net.alkalines.radiumcode.agent.il.IlGenerateRequest
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlStreamEvent

abstract class AgentProvider {
    abstract val providerId: String
    abstract val displayName: String
    open val settingsFields: List<ProviderSettingField> = emptyList()

    abstract suspend fun fetchAvailableModels(settings: ProviderSettings): Result<List<IlModelDescriptor>>

    abstract fun stream(request: IlGenerateRequest): Flow<IlStreamEvent>
}

object ProviderSettingKeys {
    const val API_KEY = "apiKey"
    const val USE_CUSTOM_BASE_URL = "useCustomBaseUrl"
    const val BASE_URL = "baseUrl"
}

enum class ProviderSettingFieldKind {
    TEXT,
    PASSWORD,
    CHECKBOX,
}

data class ProviderSettingField(
    val key: String,
    val label: String,
    val kind: ProviderSettingFieldKind,
    val placeholder: String? = null,
    val visibleWhen: Pair<String, String>? = null,
)
