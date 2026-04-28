package net.alkalines.radiumcode.agent.config

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlModelSource
import net.alkalines.radiumcode.agent.il.IlReasoningEffort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.UUID

internal interface ProviderApiKeyStore {
    fun get(providerId: String): String?
    fun set(providerId: String, apiKey: String?)
}

internal object PasswordSafeProviderApiKeyStore : ProviderApiKeyStore {
    override fun get(providerId: String): String? =
        PasswordSafe.instance.getPassword(attributes(providerId))

    override fun set(providerId: String, apiKey: String?) {
        PasswordSafe.instance.set(
            attributes(providerId),
            apiKey?.let { Credentials(providerId, it) },
        )
    }

    private fun attributes(providerId: String): CredentialAttributes =
        CredentialAttributes("Radium Code:$providerId")
}

@Service(Service.Level.APP)
class AgentModelConfigStore @JvmOverloads internal constructor(
    private val databasePath: Path = defaultDatabasePath(),
    private val apiKeyStore: ProviderApiKeyStore = PasswordSafeProviderApiKeyStore,
    initializeSynchronously: Boolean = false,
) : Disposable {

    private val logger = Logger.getInstance(AgentModelConfigStore::class.java)
    private val database: Database by lazy {
        Files.createDirectories(databasePath.parent)
        Database.connect(
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}",
            driver = "org.sqlite.JDBC",
        )
    }
    private val initializeLock = Any()
    @Volatile
    private var initialized = false
    private val preload: CompletableFuture<Void>?

    private val _configuredModels = MutableStateFlow<List<IlModelDescriptor>>(emptyList())
    val configuredModels: StateFlow<List<IlModelDescriptor>> = _configuredModels.asStateFlow()

    private val _providerSettings = MutableStateFlow<Map<String, ProviderSettings>>(emptyMap())
    val providerSettings: StateFlow<Map<String, ProviderSettings>> = _providerSettings.asStateFlow()

    private val _lastSelectedModelId = MutableStateFlow<String?>(null)
    val lastSelectedModelId: StateFlow<String?> = _lastSelectedModelId.asStateFlow()

    init {
        preload = if (initializeSynchronously) {
            initializeAndReload()
            null
        } else {
            CompletableFuture.runAsync {
                runCatching { initializeAndReload() }
                    .onFailure { logger.warn("Failed to preload agent model config", it) }
            }
        }
    }

    fun configuredModel(id: String): IlModelDescriptor? =
        _configuredModels.value.firstOrNull { it.id == id }

    fun providerSettings(providerId: String): ProviderSettings? =
        _providerSettings.value[providerId]

    fun upsertConfiguredModel(model: IlModelDescriptor): IlModelDescriptor {
        ensureInitialized()
        val now = System.currentTimeMillis()
        val resolved = if (model.id.isBlank()) model.copy(id = UUID.randomUUID().toString()) else model
        transaction(database) {
            val existingCreatedAt = ConfiguredModelsTable.selectAll()
                .where { ConfiguredModelsTable.id eq resolved.id }
                .firstOrNull()
                ?.get(ConfiguredModelsTable.createdAt)
            ConfiguredModelsTable.upsert {
                it[id] = resolved.id
                it[providerId] = resolved.providerId
                it[modelId] = resolved.modelId
                it[displayName] = resolved.displayName
                it[maxInputTokens] = resolved.maxInputTokens
                it[maxOutputTokens] = resolved.maxOutputTokens
                it[inputPricePerToken] = resolved.inputPricePerToken
                it[outputPricePerToken] = resolved.outputPricePerToken
                it[cacheReadPricePerToken] = resolved.cacheReadPricePerToken
                it[cacheWritePricePerToken] = resolved.cacheWritePricePerToken
                it[supportsText] = IlCapability.TEXT in resolved.capabilities
                it[supportsThinking] = IlCapability.THINKING in resolved.capabilities
                it[supportsToolCalling] = IlCapability.TOOL_CALLING in resolved.capabilities
                it[supportsStreaming] = IlCapability.STREAMING in resolved.capabilities
                it[reasoningEffort] = resolved.reasoningEffort?.name
                it[sourceType] = resolved.source.name
                it[createdAt] = existingCreatedAt ?: now
                it[updatedAt] = now
            }
        }
        reloadConfiguredModels()
        return resolved
    }

    fun deleteConfiguredModel(id: String) {
        ensureInitialized()
        transaction(database) {
            ConfiguredModelsTable.deleteWhere { ConfiguredModelsTable.id eq id }
        }
        reloadConfiguredModels()
        if (_lastSelectedModelId.value == id) {
            setLastSelectedModel(null)
        }
    }

    fun upsertProviderSettings(settings: ProviderSettings) {
        ensureInitialized()
        val now = System.currentTimeMillis()
        apiKeyStore.set(settings.providerId, settings.apiKey?.takeIf { it.isNotBlank() })
        transaction(database) {
            ProviderSettingsTable.upsert {
                it[providerId] = settings.providerId
                it[apiKey] = null
                it[useCustomBaseUrl] = settings.useCustomBaseUrl
                it[baseUrl] = settings.baseUrl
                it[updatedAt] = now
            }
        }
        reloadProviderSettings()
    }

    fun setLastSelectedModel(id: String?) {
        ensureInitialized()
        transaction(database) {
            if (id == null) {
                AppSettingsTable.deleteWhere { AppSettingsTable.key eq APP_SETTING_LAST_SELECTED_MODEL }
            } else {
                AppSettingsTable.upsert {
                    it[key] = APP_SETTING_LAST_SELECTED_MODEL
                    it[value] = id
                }
            }
        }
        _lastSelectedModelId.value = id
    }

    private fun initializeAndReload() {
        ensureInitialized()
        reloadAll()
    }

    private fun ensureInitialized() {
        if (initialized) {
            return
        }
        synchronized(initializeLock) {
            if (initialized) {
                return
            }
            transaction(database) {
                SchemaUtils.create(ProviderSettingsTable, ConfiguredModelsTable, AppSettingsTable)
                @Suppress("DEPRECATION")
                SchemaUtils.createMissingTablesAndColumns(ProviderSettingsTable, ConfiguredModelsTable, AppSettingsTable)
            }
            initialized = true
        }
    }

    private fun reloadAll() {
        reloadProviderSettings()
        reloadAppSettings()
        reloadConfiguredModels()
    }

    private fun reloadProviderSettings() {
        ensureInitialized()
        val rows = transaction(database) {
            ProviderSettingsTable.selectAll().map { row ->
                val providerId = row[ProviderSettingsTable.providerId]
                ProviderSettings(
                    providerId = providerId,
                    apiKey = apiKeyStore.get(providerId),
                    useCustomBaseUrl = row[ProviderSettingsTable.useCustomBaseUrl],
                    baseUrl = row[ProviderSettingsTable.baseUrl],
                )
            }
        }
        _providerSettings.value = rows.associateBy { it.providerId }
    }

    private fun reloadConfiguredModels() {
        ensureInitialized()
        val rows = transaction(database) {
            ConfiguredModelsTable.selectAll().map { row ->
                val capabilities = buildSet {
                    if (row[ConfiguredModelsTable.supportsText]) add(IlCapability.TEXT)
                    if (row[ConfiguredModelsTable.supportsThinking]) add(IlCapability.THINKING)
                    if (row[ConfiguredModelsTable.supportsToolCalling]) add(IlCapability.TOOL_CALLING)
                    if (row[ConfiguredModelsTable.supportsStreaming]) add(IlCapability.STREAMING)
                }
                IlModelDescriptor(
                    id = row[ConfiguredModelsTable.id],
                    providerId = row[ConfiguredModelsTable.providerId],
                    modelId = row[ConfiguredModelsTable.modelId],
                    displayName = row[ConfiguredModelsTable.displayName],
                    maxInputTokens = row[ConfiguredModelsTable.maxInputTokens],
                    maxOutputTokens = row[ConfiguredModelsTable.maxOutputTokens],
                    inputPricePerToken = row[ConfiguredModelsTable.inputPricePerToken],
                    outputPricePerToken = row[ConfiguredModelsTable.outputPricePerToken],
                    cacheReadPricePerToken = row[ConfiguredModelsTable.cacheReadPricePerToken],
                    cacheWritePricePerToken = row[ConfiguredModelsTable.cacheWritePricePerToken],
                    capabilities = capabilities,
                    reasoningEffort = row[ConfiguredModelsTable.reasoningEffort]
                        ?.let { runCatching { IlReasoningEffort.valueOf(it) }.getOrNull() },
                    source = runCatching { IlModelSource.valueOf(row[ConfiguredModelsTable.sourceType]) }
                        .getOrDefault(IlModelSource.MANUAL),
                )
            }
        }
        _configuredModels.value = rows.sortedBy { it.displayName.lowercase() }
    }

    private fun reloadAppSettings() {
        ensureInitialized()
        val value = transaction(database) {
            AppSettingsTable.selectAll()
                .where { AppSettingsTable.key eq APP_SETTING_LAST_SELECTED_MODEL }
                .firstOrNull()
                ?.get(AppSettingsTable.value)
        }
        _lastSelectedModelId.value = value
    }

    override fun dispose() {
        preload?.cancel(true)
        runCatching { TransactionManager.closeAndUnregister(database) }
            .onFailure { logger.warn("Failed to unregister Exposed database", it) }
    }

    companion object {
        fun getInstance(): AgentModelConfigStore = service()

        private fun defaultDatabasePath(): Path =
            Path.of(System.getProperty("user.home"), ".radium", "db.sql")
    }
}
