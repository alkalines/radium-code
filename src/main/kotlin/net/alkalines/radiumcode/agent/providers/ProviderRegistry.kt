package net.alkalines.radiumcode.agent.providers

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName

class ProviderRegistry private constructor(
    private val providers: List<AgentProvider>,
) {
    val allProviders: List<AgentProvider> = providers
    val allModels = providers.flatMap { it.models }
    val defaultModel = allModels.firstOrNull { it.isDefault } ?: allModels.firstOrNull()

    fun provider(providerId: String): AgentProvider = providers.first { it.providerId == providerId }
    fun providerOrNull(providerId: String): AgentProvider? = providers.firstOrNull { it.providerId == providerId }
    fun model(providerId: String, modelId: String) = allModels.firstOrNull { it.providerId == providerId && it.modelId == modelId }

    companion object {
        private val logger = Logger.getInstance(ProviderRegistry::class.java)
        private val extensionPointName = ExtensionPointName<AgentProviderBean>("net.alkalines.radiumcode.agentProvider")

        val lazyInstance: ProviderRegistry by lazy {
            fromProviders(extensionPointName.extensionList.map { it.instantiate() })
        }

        fun fromProviders(providers: List<AgentProvider>): ProviderRegistry {
            val providerIds = mutableSetOf<String>()
            val modelKeys = mutableSetOf<String>()
            providers.forEach { provider ->
                check(providerIds.add(provider.providerId)) {
                    "Duplicate providerId: ${provider.providerId}"
                }
                provider.models.forEach { model ->
                    val key = "${provider.providerId}:${model.modelId}"
                    check(modelKeys.add(key)) {
                        "Duplicate model registration: $key"
                    }
                }
            }
            return ProviderRegistry(providers)
        }

        fun notifyRegistryFailure(message: String) {
            logger.warn(message)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Radium Code")
                .createNotification(message, NotificationType.ERROR)
                .notify(null)
        }
    }
}
