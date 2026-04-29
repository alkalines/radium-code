package net.alkalines.radiumcode.agent.config

import org.jetbrains.exposed.v1.core.Table

internal object ProviderSettingsTable : Table("provider_settings") {
    val providerId = varchar("provider_id", 64)
    val apiKey = text("api_key").nullable()
    val useCustomBaseUrl = bool("use_custom_base_url").default(false)
    val baseUrl = text("base_url").nullable()
    val extrasJson = text("extras_json").default("{}")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(providerId)
}

internal object ModelProviderSettingsTable : Table("model_provider_settings") {
    val configuredModelId = varchar("configured_model_id", 64)
    val providerId = varchar("provider_id", 64)
    val apiKey = text("api_key").nullable()
    val useCustomBaseUrl = bool("use_custom_base_url").default(false)
    val baseUrl = text("base_url").nullable()
    val extrasJson = text("extras_json").default("{}")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(configuredModelId)
}

internal object ConfiguredModelsTable : Table("configured_models") {
    val id = varchar("id", 64)
    val providerId = varchar("provider_id", 64)
    val modelId = varchar("model_id", 255)
    val displayName = varchar("display_name", 255)
    val maxInputTokens = long("max_input_tokens").nullable()
    val maxOutputTokens = long("max_output_tokens").nullable()
    val inputPricePerToken = double("input_price_per_token").nullable()
    val outputPricePerToken = double("output_price_per_token").nullable()
    val cacheReadPricePerToken = double("cache_read_price_per_token").nullable()
    val cacheWritePricePerToken = double("cache_write_price_per_token").nullable()
    val supportsText = bool("supports_text").default(true)
    val supportsThinking = bool("supports_thinking").default(false)
    val supportsToolCalling = bool("supports_tools").default(false)
    val supportsStreaming = bool("supports_streaming").default(true)
    val reasoningEffort = varchar("reasoning_effort", 16).nullable()
    val sourceType = varchar("source", 16)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object AppSettingsTable : Table("app_settings") {
    val key = varchar("key", 64)
    val value = text("value").nullable()
    override val primaryKey = PrimaryKey(key)
}

internal const val APP_SETTING_LAST_SELECTED_MODEL = "last_selected_configured_model_id"
