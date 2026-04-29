package net.alkalines.radiumcode.agent.config

data class ProviderSettings(
    val providerId: String,
    val configuredModelId: String? = null,
    val apiKey: String? = null,
    val useCustomBaseUrl: Boolean = false,
    val baseUrl: String? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    val storageKey: String get() = configuredModelId?.let(::modelStorageKey) ?: providerId

    companion object {
        fun modelStorageKey(configuredModelId: String): String = "model:$configuredModelId"
    }
}
