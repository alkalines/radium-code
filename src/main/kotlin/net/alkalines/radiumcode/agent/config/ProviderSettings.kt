package net.alkalines.radiumcode.agent.config

data class ProviderSettings(
    val providerId: String,
    val apiKey: String? = null,
    val useCustomBaseUrl: Boolean = false,
    val baseUrl: String? = null,
)
