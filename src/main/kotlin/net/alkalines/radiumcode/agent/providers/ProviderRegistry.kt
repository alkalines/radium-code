package net.alkalines.radiumcode.agent.providers

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName

class ProviderRegistry private constructor(
    private val providers: List<AgentProvider>,
) {
    val allProviders: List<AgentProvider> = providers

    fun provider(providerId: String): AgentProvider = providers.first { it.providerId == providerId }
    fun providerOrNull(providerId: String): AgentProvider? = providers.firstOrNull { it.providerId == providerId }

    companion object {
        private val logger = Logger.getInstance(ProviderRegistry::class.java)
        private val extensionPointName = ExtensionPointName<AgentProviderBean>("net.alkalines.radiumcode.agentProvider")

        val lazyInstance: ProviderRegistry by lazy {
            fromProviders(extensionPointName.extensionList.map { it.instantiate() })
        }

        fun fromProviders(providers: List<AgentProvider>): ProviderRegistry {
            val providerIds = mutableSetOf<String>()
            providers.forEach { provider ->
                check(providerIds.add(provider.providerId)) {
                    "Duplicate providerId: ${provider.providerId}"
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
