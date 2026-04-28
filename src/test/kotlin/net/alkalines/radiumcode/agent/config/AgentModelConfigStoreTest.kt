package net.alkalines.radiumcode.agent.config

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlModelSource
import net.alkalines.radiumcode.agent.il.IlReasoningEffort

class AgentModelConfigStoreTest {

    private val tmpDir = Files.createTempDirectory("radium-store-test")
    private val apiKeyStore = InMemoryProviderApiKeyStore()
    private val store = AgentModelConfigStore(
        databasePath = tmpDir.resolve("db.sql"),
        apiKeyStore = apiKeyStore,
        initializeSynchronously = true,
    )

    @AfterTest
    fun tearDown() {
        store.dispose()
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `creates schema and exposes empty initial state`() {
        assertTrue(store.configuredModels.value.isEmpty())
        assertTrue(store.providerSettings.value.isEmpty())
        assertNull(store.lastSelectedModelId.value)
    }

    @Test
    fun `upsert configured model assigns id and exposes via state flow`() {
        val saved = store.upsertConfiguredModel(sampleDescriptor(modelId = "minimax/minimax-m2.7"))

        assertTrue(saved.id.isNotBlank())
        assertEquals(1, store.configuredModels.value.size)
        assertEquals("minimax/minimax-m2.7", store.configuredModels.value.first().modelId)
    }

    @Test
    fun `delete configured model removes it and clears last selected if it pointed there`() {
        val saved = store.upsertConfiguredModel(sampleDescriptor())
        store.setLastSelectedModel(saved.id)

        store.deleteConfiguredModel(saved.id)

        assertTrue(store.configuredModels.value.isEmpty())
        assertNull(store.lastSelectedModelId.value)
    }

    @Test
    fun `provider settings round trip persists api key and base url`() {
        store.upsertProviderSettings(
            ProviderSettings(
                providerId = "openrouter",
                apiKey = "sk-test",
                useCustomBaseUrl = true,
                baseUrl = "https://example/api",
            )
        )

        val settings = store.providerSettings.value["openrouter"]
        assertNotNull(settings)
        assertEquals("sk-test", settings.apiKey)
        assertTrue(settings.useCustomBaseUrl)
        assertEquals("https://example/api", settings.baseUrl)
    }

    @Test
    fun `provider api key is not persisted in sqlite database`() {
        store.upsertProviderSettings(
            ProviderSettings(
                providerId = "openrouter",
                apiKey = "sk-secret-value",
                useCustomBaseUrl = true,
                baseUrl = "https://example/api",
            )
        )

        val dbBytes = Files.readAllBytes(tmpDir.resolve("db.sql")).toString(Charsets.ISO_8859_1)

        assertFalse(dbBytes.contains("sk-secret-value"))
    }

    @Test
    fun `setLastSelectedModel publishes value through state flow`() {
        store.setLastSelectedModel("model-1")
        assertEquals("model-1", store.lastSelectedModelId.value)

        store.setLastSelectedModel(null)
        assertNull(store.lastSelectedModelId.value)
    }

    @Test
    fun `clearing last selected model works with legacy non null app settings value`() {
        val legacyDir = Files.createTempDirectory("radium-store-legacy-settings-test")
        createLegacyAppSettingsSchema(legacyDir.resolve("db.sql"))
        val legacyStore = AgentModelConfigStore(
            databasePath = legacyDir.resolve("db.sql"),
            apiKeyStore = InMemoryProviderApiKeyStore(),
            initializeSynchronously = true,
        )

        try {
            legacyStore.setLastSelectedModel("model-1")

            legacyStore.setLastSelectedModel(null)

            assertNull(legacyStore.lastSelectedModelId.value)
        } finally {
            legacyStore.dispose()
            legacyDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reasoning effort round trips through enum name`() {
        val saved = store.upsertConfiguredModel(
            sampleDescriptor(reasoningEffort = IlReasoningEffort.HIGH)
        )
        assertEquals(IlReasoningEffort.HIGH, saved.reasoningEffort)
        assertEquals(IlReasoningEffort.HIGH, store.configuredModels.value.first().reasoningEffort)
    }

    @Test
    fun `upsert configured model preserves createdAt and updates updatedAt`() {
        val saved = store.upsertConfiguredModel(sampleDescriptor(modelId = "openai/gpt-5.4"))
        val firstCreatedAt = configuredModelTimestamps(saved.id).first
        val firstUpdatedAt = configuredModelTimestamps(saved.id).second
        Thread.sleep(5)

        store.upsertConfiguredModel(saved.copy(displayName = "Updated GPT"))
        val (secondCreatedAt, secondUpdatedAt) = configuredModelTimestamps(saved.id)

        assertEquals(firstCreatedAt, secondCreatedAt)
        assertTrue(secondUpdatedAt > firstUpdatedAt)
    }

    @Test
    fun `upsert configured model works with legacy supports tools column`() {
        val legacyDir = Files.createTempDirectory("radium-store-legacy-test")
        createLegacyConfiguredModelsSchema(legacyDir.resolve("db.sql"))
        val legacyStore = AgentModelConfigStore(
            databasePath = legacyDir.resolve("db.sql"),
            apiKeyStore = InMemoryProviderApiKeyStore(),
            initializeSynchronously = true,
        )

        try {
            val saved = legacyStore.upsertConfiguredModel(sampleDescriptor(modelId = "anthropic/claude-sonnet-4.5"))

            assertTrue(IlCapability.TOOL_CALLING in saved.capabilities)
            assertTrue(IlCapability.TOOL_CALLING in legacyStore.configuredModels.value.first().capabilities)
        } finally {
            legacyStore.dispose()
            legacyDir.toFile().deleteRecursively()
        }
    }

    private fun sampleDescriptor(
        modelId: String = "openai/gpt-5.4",
        reasoningEffort: IlReasoningEffort? = null,
    ) = IlModelDescriptor(
        id = "",
        providerId = "openrouter",
        modelId = modelId,
        displayName = "OPENROUTER ${modelId.uppercase()}",
        maxInputTokens = 1_000_000L,
        maxOutputTokens = 128_000L,
        inputPricePerToken = 0.00000125,
        outputPricePerToken = 0.0000075,
        cacheReadPricePerToken = 0.000000125,
        cacheWritePricePerToken = null,
        capabilities = setOf(IlCapability.TEXT, IlCapability.STREAMING, IlCapability.THINKING, IlCapability.TOOL_CALLING),
        reasoningEffort = reasoningEffort,
        source = IlModelSource.CATALOG,
    )

    private fun configuredModelTimestamps(id: String): Pair<Long, Long> =
        DriverManager.getConnection("jdbc:sqlite:${tmpDir.resolve("db.sql").toAbsolutePath()}").use { connection ->
            connection.prepareStatement("select created_at, updated_at from configured_models where id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getLong("created_at") to resultSet.getLong("updated_at")
                }
            }
        }

    private fun createLegacyConfiguredModelsSchema(databasePath: java.nio.file.Path) {
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    create table configured_models (
                        id varchar(64) primary key,
                        provider_id varchar(64) not null,
                        model_id varchar(255) not null,
                        display_name varchar(255) not null,
                        max_input_tokens bigint,
                        max_output_tokens bigint,
                        input_price_per_token double,
                        output_price_per_token double,
                        cache_read_price_per_token double,
                        cache_write_price_per_token double,
                        supports_text boolean not null default 1,
                        supports_thinking boolean not null default 0,
                        supports_tools boolean not null,
                        supports_streaming boolean not null default 1,
                        reasoning_effort varchar(16),
                        source varchar(16) not null,
                        created_at bigint not null,
                        updated_at bigint not null
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun createLegacyAppSettingsSchema(databasePath: java.nio.file.Path) {
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    create table app_settings (
                        key varchar(64) primary key,
                        value text not null
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private class InMemoryProviderApiKeyStore : ProviderApiKeyStore {
        private val keys = mutableMapOf<String, String>()

        override fun get(providerId: String): String? = keys[providerId]

        override fun set(providerId: String, apiKey: String?) {
            if (apiKey == null) {
                keys.remove(providerId)
            } else {
                keys[providerId] = apiKey
            }
        }
    }
}
