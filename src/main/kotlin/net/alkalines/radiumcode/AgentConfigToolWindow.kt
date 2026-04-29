package net.alkalines.radiumcode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.alkalines.radiumcode.agent.config.AgentModelConfigStore
import net.alkalines.radiumcode.agent.config.ProviderSettings
import net.alkalines.radiumcode.agent.il.IlCapability
import net.alkalines.radiumcode.agent.il.IlModelDescriptor
import net.alkalines.radiumcode.agent.il.IlModelSource
import net.alkalines.radiumcode.agent.il.IlReasoningEffort
import net.alkalines.radiumcode.agent.providers.AgentProvider
import net.alkalines.radiumcode.agent.providers.ProviderSettingField
import net.alkalines.radiumcode.agent.providers.ProviderSettingFieldKind
import net.alkalines.radiumcode.agent.providers.ProviderSettingKeys
import net.alkalines.radiumcode.agent.providers.ProviderRegistry
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

internal object AgentConfigToolWindowContentModel {
    private val priceScale = BigDecimal("1000000")

    fun mockLabel(): String = "config"

    fun matchesQuery(model: IlModelDescriptor, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return model.displayName.lowercase().contains(q) || model.modelId.lowercase().contains(q)
    }

    fun shouldOfferCustomEntry(query: String, filtered: List<IlModelDescriptor>): Boolean =
        query.isNotBlank() && filtered.none { it.modelId.equals(query.trim(), ignoreCase = true) }

    fun shouldExpandModelDropdown(
        previousText: String,
        nextText: String,
        changeOrigin: ModelSearchTextChangeOrigin,
    ): Boolean = changeOrigin == ModelSearchTextChangeOrigin.USER && previousText != nextText

    fun configuredModelListItems(
        configuredModels: List<IlModelDescriptor>,
        editing: ModelFormState?,
    ): List<ConfiguredModelListItem> {
        if (editing == null) {
            return configuredModels.map(ConfiguredModelListItem::Row)
        }
        if (!editing.isNew && editing.id.isNotBlank()) {
            var replaced = false
            val items = configuredModels.map { model ->
                if (model.id == editing.id) {
                    replaced = true
                    ConfiguredModelListItem.Editor(editing)
                } else {
                    ConfiguredModelListItem.Row(model)
                }
            }
            if (replaced) return items
        }
        return configuredModels.map(ConfiguredModelListItem::Row) + ConfiguredModelListItem.Editor(editing)
    }

    fun formatPricePerMillionTokens(pricePerToken: Double?): String =
        pricePerToken?.let {
            BigDecimal.valueOf(it)
                .multiply(priceScale)
                .stripTrailingZeros()
                .toPlainString()
        }.orEmpty()

    fun parsePricePerMillionTokens(pricePerMillionTokens: String): Double? =
        pricePerMillionTokens.trim()
            .takeIf { it.isNotEmpty() }
            ?.let {
                runCatching { BigDecimal(it).divide(priceScale).toDouble() }.getOrNull()
            }

    fun providerSettingValue(settings: ProviderSettings, key: String): String = when (key) {
        ProviderSettingKeys.API_KEY -> settings.apiKey.orEmpty()
        ProviderSettingKeys.USE_CUSTOM_BASE_URL -> settings.useCustomBaseUrl.toString()
        ProviderSettingKeys.BASE_URL -> settings.baseUrl.orEmpty()
        else -> settings.extras[key].orEmpty()
    }

    fun updateProviderSetting(settings: ProviderSettings, key: String, value: String): ProviderSettings = when (key) {
        ProviderSettingKeys.API_KEY -> settings.copy(apiKey = value.takeIf { it.isNotBlank() })
        ProviderSettingKeys.USE_CUSTOM_BASE_URL -> settings.copy(useCustomBaseUrl = value.toBooleanStrictOrNull() ?: false)
        ProviderSettingKeys.BASE_URL -> settings.copy(baseUrl = value.takeIf { it.isNotBlank() })
        else -> settings.copy(extras = settings.extras + (key to value))
    }

    fun isProviderSettingFieldVisible(field: ProviderSettingField, settings: ProviderSettings): Boolean {
        val dependency = field.visibleWhen ?: return true
        return providerSettingValue(settings, dependency.first) == dependency.second
    }
}

internal enum class ModelSearchTextChangeOrigin {
    USER,
    PROGRAMMATIC,
}

internal sealed interface ConfiguredModelListItem {
    data class Row(val model: IlModelDescriptor) : ConfiguredModelListItem
    data class Editor(val state: ModelFormState) : ConfiguredModelListItem
}

internal class CatalogRefreshTracker {
    private var latestRequestId = 0L

    fun nextRequest(): Long {
        latestRequestId += 1
        return latestRequestId
    }

    fun accepts(requestId: Long): Boolean = requestId == latestRequestId
}

internal fun launchProviderSettingsSave(
    scope: CoroutineScope,
    store: AgentModelConfigStore,
    settings: ProviderSettings,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Job = scope.launch(dispatcher) {
    store.upsertProviderSettings(settings)
}

@Composable
internal fun AgentConfigToolWindowContent() {
    val store = remember { AgentModelConfigStore.getInstance() }
    val registry = remember { ProviderRegistry.lazyInstance }
    val configuredModels by store.configuredModels.collectAsState()
    val providerSettingsMap by store.providerSettings.collectAsState()

    val textColor = rememberThemeColor("Label.foreground", 0xFF1F2329.toInt(), 0xFFDFE1E5.toInt())
    val placeholderColor = rememberThemeColor("TextField.placeholderForeground", 0xFF7A8088.toInt(), 0xFF8B9098.toInt())
    val borderColor = rememberThemeColor("Borders.ContrastBorderColor", 0xFFD8DCE3.toInt(), 0xFF454A4F.toInt())
    val rowSurface = rememberThemeColor("EditorPane.inactiveBackground", 0xFFF4F5F7.toInt(), 0xFF313336.toInt())

    var editing by remember { mutableStateOf<ModelFormState?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GroupHeader(
                AgentMessageBundle.message("toolwindow.AgentToolWindow.config.modelsSection"),
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    editing = ModelFormState.empty(
                        defaultProviderId = registry.allProviders.firstOrNull()?.providerId.orEmpty()
                    )
                }
            ) {
                Text(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.addModel"))
            }
        }

        if (configuredModels.isEmpty() && editing == null) {
            Text(
                AgentMessageBundle.message("toolwindow.AgentToolWindow.noConfiguredModel"),
                style = TextStyle(color = placeholderColor, fontSize = 13.sp)
            )
        }

        AgentConfigToolWindowContentModel.configuredModelListItems(configuredModels, editing).forEach { item ->
            when (item) {
                is ConfiguredModelListItem.Row -> ConfiguredModelRow(
                    model = item.model,
                    rowSurface = rowSurface,
                    borderColor = borderColor,
                    textColor = textColor,
                    placeholderColor = placeholderColor,
                    onEdit = { editing = ModelFormState.from(item.model) },
                    onDelete = { store.deleteConfiguredModel(item.model.id) },
                )
                is ConfiguredModelListItem.Editor -> ModelFormCard(
                    state = item.state,
                    registry = registry,
                    providerSettingsMap = providerSettingsMap,
                    rowSurface = rowSurface,
                    borderColor = borderColor,
                    textColor = textColor,
                    placeholderColor = placeholderColor,
                    onSave = { saved, settings ->
                        val resolved = store.upsertConfiguredModel(saved)
                        launchProviderSettingsSave(
                            scope = this,
                            store = store,
                            settings = settings.copy(
                                providerId = resolved.providerId,
                                configuredModelId = resolved.id,
                            ),
                        )
                        editing = null
                    },
                    onDelete = { id ->
                        store.deleteConfiguredModel(id)
                        editing = null
                    },
                    onCancel = { editing = null },
                )
            }
        }
    }
}

@Composable
private fun ConfiguredModelRow(
    model: IlModelDescriptor,
    rowSurface: Color,
    borderColor: Color,
    textColor: Color,
    placeholderColor: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val editIcon = remember { IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Edit) }
    val deleteIcon = remember { IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.GC) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowSurface, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, style = TextStyle(color = textColor, fontSize = 14.sp))
            Text(
                "${model.providerId} • ${model.modelId}",
                style = TextStyle(color = placeholderColor, fontSize = 11.sp)
            )
        }
        Box(
            modifier = Modifier.size(24.dp).clickable(onClick = onEdit),
            contentAlignment = Alignment.Center
        ) {
            Icon(key = editIcon, contentDescription = AgentMessageBundle.message("toolwindow.AgentToolWindow.config.editModel"), modifier = Modifier.size(16.dp), tint = textColor)
        }
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier.size(24.dp).clickable(onClick = onDelete),
            contentAlignment = Alignment.Center
        ) {
            Icon(key = deleteIcon, contentDescription = AgentMessageBundle.message("toolwindow.AgentToolWindow.config.deleteModel"), modifier = Modifier.size(16.dp), tint = textColor)
        }
    }
}

internal data class ModelFormState(
    val id: String,
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val maxInputTokens: String,
    val maxOutputTokens: String,
    val inputPrice: String,
    val outputPrice: String,
    val cacheReadPrice: String,
    val cacheWritePrice: String,
    val supportsTools: Boolean,
    val supportsReasoning: Boolean,
    val reasoningEffort: IlReasoningEffort,
    val source: IlModelSource,
    val isNew: Boolean,
) {
    fun toDescriptor(): IlModelDescriptor {
        val capabilities = buildSet {
            add(IlCapability.TEXT)
            add(IlCapability.STREAMING)
            if (supportsTools) add(IlCapability.TOOL_CALLING)
            if (supportsReasoning) add(IlCapability.THINKING)
        }
        return IlModelDescriptor(
            id = id,
            providerId = providerId,
            modelId = modelId.trim(),
            displayName = displayName.trim().ifBlank { modelId.trim() },
            maxInputTokens = maxInputTokens.toLongOrNullOrBlank(),
            maxOutputTokens = maxOutputTokens.toLongOrNullOrBlank(),
            inputPricePerToken = AgentConfigToolWindowContentModel.parsePricePerMillionTokens(inputPrice),
            outputPricePerToken = AgentConfigToolWindowContentModel.parsePricePerMillionTokens(outputPrice),
            cacheReadPricePerToken = AgentConfigToolWindowContentModel.parsePricePerMillionTokens(cacheReadPrice),
            cacheWritePricePerToken = AgentConfigToolWindowContentModel.parsePricePerMillionTokens(cacheWritePrice),
            capabilities = capabilities,
            reasoningEffort = if (supportsReasoning) reasoningEffort else null,
            source = source,
        )
    }

    fun isValid(): Boolean = providerId.isNotBlank() && modelId.isNotBlank()

    fun withCatalogSelection(selected: IlModelDescriptor): ModelFormState = copy(
        modelId = selected.modelId,
        displayName = displayName.ifBlank { selected.displayName },
        maxInputTokens = selected.maxInputTokens?.toString().orEmpty(),
        maxOutputTokens = selected.maxOutputTokens?.toString().orEmpty(),
        inputPrice = AgentConfigToolWindowContentModel.formatPricePerMillionTokens(selected.inputPricePerToken),
        outputPrice = AgentConfigToolWindowContentModel.formatPricePerMillionTokens(selected.outputPricePerToken),
        cacheReadPrice = AgentConfigToolWindowContentModel.formatPricePerMillionTokens(selected.cacheReadPricePerToken),
        cacheWritePrice = AgentConfigToolWindowContentModel.formatPricePerMillionTokens(selected.cacheWritePricePerToken),
        supportsTools = IlCapability.TOOL_CALLING in selected.capabilities,
        supportsReasoning = IlCapability.THINKING in selected.capabilities,
        source = IlModelSource.CATALOG,
    )

    companion object {
        fun empty(defaultProviderId: String): ModelFormState = ModelFormState(
            id = "",
            providerId = defaultProviderId,
            modelId = "",
            displayName = "",
            maxInputTokens = "",
            maxOutputTokens = "",
            inputPrice = "",
            outputPrice = "",
            cacheReadPrice = "",
            cacheWritePrice = "",
            supportsTools = false,
            supportsReasoning = false,
            reasoningEffort = IlReasoningEffort.MEDIUM,
            source = IlModelSource.MANUAL,
            isNew = true,
        )

        fun from(model: IlModelDescriptor): ModelFormState = ModelFormState(
            id = model.id,
            providerId = model.providerId,
            modelId = model.modelId,
            displayName = model.displayName,
            maxInputTokens = model.maxInputTokens?.toString().orEmpty(),
            maxOutputTokens = model.maxOutputTokens?.toString().orEmpty(),
            inputPrice = AgentConfigToolWindowContentModel.formatPricePerMillionTokens(model.inputPricePerToken),
            outputPrice = AgentConfigToolWindowContentModel.formatPricePerMillionTokens(model.outputPricePerToken),
            cacheReadPrice = AgentConfigToolWindowContentModel.formatPricePerMillionTokens(model.cacheReadPricePerToken),
            cacheWritePrice = AgentConfigToolWindowContentModel.formatPricePerMillionTokens(model.cacheWritePricePerToken),
            supportsTools = IlCapability.TOOL_CALLING in model.capabilities,
            supportsReasoning = IlCapability.THINKING in model.capabilities,
            reasoningEffort = model.reasoningEffort ?: IlReasoningEffort.MEDIUM,
            source = model.source,
            isNew = false,
        )
    }
}

private fun String.toLongOrNullOrBlank(): Long? = this.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()

@Composable
private fun ModelFormCard(
    state: ModelFormState,
    registry: ProviderRegistry,
    providerSettingsMap: Map<String, ProviderSettings>,
    rowSurface: Color,
    borderColor: Color,
    textColor: Color,
    placeholderColor: Color,
    onSave: CoroutineScope.(IlModelDescriptor, ProviderSettings) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var form by remember(state.id) { mutableStateOf(state) }
    val coroutineScope = rememberCoroutineScope()
    val catalogRefreshTracker = remember { CatalogRefreshTracker() }
    var catalogRefreshJob by remember { mutableStateOf<Job?>(null) }
    var catalog by remember(form.providerId) { mutableStateOf<List<IlModelDescriptor>>(emptyList()) }
    var catalogLoading by remember(form.providerId) { mutableStateOf(false) }
    var catalogError by remember(form.providerId) { mutableStateOf<String?>(null) }

    val provider: AgentProvider? = registry.providerOrNull(form.providerId)
    val storedSettings = form.id.takeIf { it.isNotBlank() }
        ?.let { providerSettingsMap[ProviderSettings.modelStorageKey(it)] }
        ?.takeIf { it.providerId == form.providerId }
        ?: ProviderSettings(providerId = form.providerId, configuredModelId = form.id.takeIf { it.isNotBlank() })
    var providerSettings by remember(form.providerId, form.id) { mutableStateOf(storedSettings) }

    LaunchedEffect(form.providerId, storedSettings) {
        providerSettings = storedSettings
    }

    val refreshCatalog: () -> Unit = {
        if (provider != null) {
            catalogRefreshJob?.cancel()
            val requestId = catalogRefreshTracker.nextRequest()
            catalogLoading = true
            catalogError = null
            catalogRefreshJob = coroutineScope.launch {
                val result = provider.fetchAvailableModels(providerSettings)
                if (!catalogRefreshTracker.accepts(requestId)) {
                    return@launch
                }
                catalogLoading = false
                result.onSuccess { catalog = it }
                    .onFailure {
                        catalogError = it.message ?: AgentMessageBundle.message("toolwindow.AgentToolWindow.config.catalogError")
                    }
            }
        }
    }

    LaunchedEffect(form.providerId, storedSettings) {
        refreshCatalog()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowSurface, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.provider"), textColor)
        ProviderPicker(
            providers = registry.allProviders,
            selectedProviderId = form.providerId,
            textColor = textColor,
            onSelect = { selected ->
                form = form.copy(providerId = selected.providerId, modelId = "", displayName = "", source = IlModelSource.MANUAL)
            },
        )

        provider?.let { selectedProvider ->
            ProviderSettingsFields(
                provider = selectedProvider,
                settings = providerSettings,
                onChange = { key, value ->
                    providerSettings = AgentConfigToolWindowContentModel.updateProviderSetting(providerSettings, key, value)
                },
                textColor = textColor,
                placeholderColor = placeholderColor,
            )
        }

        FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.displayName"), textColor)
        SimpleStringField(form.displayName, onChange = { form = form.copy(displayName = it) })

        FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.modelId"), textColor)
        ModelSearchField(
            currentModelId = form.modelId,
            catalog = catalog,
            catalogLoading = catalogLoading,
            catalogError = catalogError,
            onSelectCatalog = { selected ->
                form = form.withCatalogSelection(selected)
            },
            onUseCustom = { query ->
                form = form.copy(modelId = query.trim(), source = IlModelSource.MANUAL)
            },
            textColor = textColor,
            placeholderColor = placeholderColor,
            borderColor = borderColor,
            rowSurface = rowSurface,
            onRefresh = refreshCatalog,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.maxInputTokens"), textColor)
                SimpleStringField(form.maxInputTokens, onChange = { form = form.copy(maxInputTokens = it) })
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.maxOutputTokens"), textColor)
                SimpleStringField(form.maxOutputTokens, onChange = { form = form.copy(maxOutputTokens = it) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.inputPrice"), textColor)
                SimpleStringField(form.inputPrice, onChange = { form = form.copy(inputPrice = it) })
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.outputPrice"), textColor)
                SimpleStringField(form.outputPrice, onChange = { form = form.copy(outputPrice = it) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.cacheReadPrice"), textColor)
                SimpleStringField(form.cacheReadPrice, onChange = { form = form.copy(cacheReadPrice = it) })
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.cacheWritePrice"), textColor)
                SimpleStringField(form.cacheWritePrice, onChange = { form = form.copy(cacheWritePrice = it) })
            }
        }

        CheckboxRow(
            text = AgentMessageBundle.message("toolwindow.AgentToolWindow.config.supportsTools"),
            checked = form.supportsTools,
            onCheckedChange = { form = form.copy(supportsTools = it) },
        )

        CheckboxRow(
            text = AgentMessageBundle.message("toolwindow.AgentToolWindow.config.supportsReasoning"),
            checked = form.supportsReasoning,
            onCheckedChange = { form = form.copy(supportsReasoning = it) },
        )

        AnimatedVisibility(visible = form.supportsReasoning) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormFieldLabel(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.reasoningEffort"), textColor)
                ReasoningEffortPicker(
                    selected = form.reasoningEffort,
                    onSelect = { form = form.copy(reasoningEffort = it) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(
                enabled = form.isValid(),
                onClick = { coroutineScope.onSave(form.toDescriptor(), providerSettings) },
            ) {
                Text(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.save"))
            }
            OutlinedButton(onClick = onCancel) {
                Text(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.cancel"))
            }
            if (!form.isNew) {
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { onDelete(form.id) }) {
                    Text(AgentMessageBundle.message("toolwindow.AgentToolWindow.config.delete"))
                }
            }
        }
    }
}

@Composable
private fun ProviderPicker(
    providers: List<AgentProvider>,
    selectedProviderId: String,
    textColor: Color,
    onSelect: (AgentProvider) -> Unit,
) {
    if (providers.isEmpty()) {
        Text(
            AgentMessageBundle.message("toolwindow.AgentToolWindow.config.selectProvider"),
            style = TextStyle(color = textColor, fontSize = 14.sp),
        )
        return
    }

    val selectedIndex = providers.indexOfFirst { it.providerId == selectedProviderId }
        .takeIf { it >= 0 }
        ?: 0
    ListComboBox(
        items = providers.map { it.displayName },
        selectedIndex = selectedIndex,
        onSelectedItemChange = { index ->
            providers.getOrNull(index)?.let(onSelect)
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(color = textColor, fontSize = 14.sp),
    )
}

@Composable
private fun ProviderSettingsFields(
    provider: AgentProvider,
    settings: ProviderSettings,
    onChange: (String, String) -> Unit,
    textColor: Color,
    placeholderColor: Color,
) {
    provider.settingsFields.forEach { field ->
        if (AgentConfigToolWindowContentModel.isProviderSettingFieldVisible(field, settings)) {
            when (field.kind) {
                ProviderSettingFieldKind.CHECKBOX -> CheckboxRow(
                    text = field.label,
                    checked = AgentConfigToolWindowContentModel.providerSettingValue(settings, field.key).toBooleanStrictOrNull() ?: false,
                    onCheckedChange = { onChange(field.key, it.toString()) },
                )
                ProviderSettingFieldKind.TEXT, ProviderSettingFieldKind.PASSWORD -> {
                    FormFieldLabel(field.label, textColor)
                    SimpleStringField(
                        value = AgentConfigToolWindowContentModel.providerSettingValue(settings, field.key),
                        onChange = { onChange(field.key, it) },
                        placeholder = field.placeholder,
                        placeholderColor = placeholderColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleStringField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    placeholderColor: Color? = null,
) {
    val state = rememberTextFieldState(initialText = value)
    LaunchedEffect(value) {
        if (state.text.toString() != value) {
            state.edit { replace(0, length, value) }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { onChange(it) }
    }
    TextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        placeholder = placeholder?.let {
            { Text(it, style = TextStyle(color = placeholderColor ?: Color.Unspecified)) }
        },
    )
}

@Composable
private fun ModelSearchField(
    currentModelId: String,
    catalog: List<IlModelDescriptor>,
    catalogLoading: Boolean,
    catalogError: String?,
    onSelectCatalog: (IlModelDescriptor) -> Unit,
    onUseCustom: (String) -> Unit,
    textColor: Color,
    placeholderColor: Color,
    borderColor: Color,
    rowSurface: Color,
    onRefresh: () -> Unit,
) {
    var query by remember { mutableStateOf(currentModelId) }
    var expanded by remember { mutableStateOf(false) }
    var nextTextChangeOrigin by remember { mutableStateOf(ModelSearchTextChangeOrigin.USER) }
    val refreshIcon = remember { IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Refresh) }
    val state = rememberTextFieldState(initialText = currentModelId)

    LaunchedEffect(currentModelId) {
        if (state.text.toString() != currentModelId) {
            nextTextChangeOrigin = ModelSearchTextChangeOrigin.PROGRAMMATIC
            state.edit { replace(0, length, currentModelId) }
            query = currentModelId
        }
    }
    LaunchedEffect(state) {
        var previousText = state.text.toString()
        snapshotFlow { state.text.toString() }.collect {
            val origin = nextTextChangeOrigin
            nextTextChangeOrigin = ModelSearchTextChangeOrigin.USER
            query = it
            if (AgentConfigToolWindowContentModel.shouldExpandModelDropdown(previousText, it, origin)) {
                expanded = true
            }
            previousText = it
        }
    }

    val filtered = remember(query, catalog) {
        catalog.filter { AgentConfigToolWindowContentModel.matchesQuery(it, query) }.take(60)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextField(
                state = state,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        AgentMessageBundle.message("toolwindow.AgentToolWindow.config.searchModelPlaceholder"),
                        style = TextStyle(color = placeholderColor)
                    )
                },
            )
            Box(
                modifier = Modifier.size(28.dp).clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    key = refreshIcon,
                    contentDescription = AgentMessageBundle.message("toolwindow.AgentToolWindow.config.refreshCatalog"),
                    modifier = Modifier.size(16.dp),
                    tint = textColor
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(rowSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                when {
                    catalogLoading -> Text(
                        AgentMessageBundle.message("toolwindow.AgentToolWindow.config.catalogLoading"),
                        style = TextStyle(color = placeholderColor, fontSize = 12.sp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    catalogError != null -> Text(
                        catalogError,
                        style = TextStyle(color = placeholderColor, fontSize = 12.sp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    filtered.isEmpty() && query.isBlank() -> Text(
                        AgentMessageBundle.message("toolwindow.AgentToolWindow.config.catalogEmpty"),
                        style = TextStyle(color = placeholderColor, fontSize = 12.sp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    else -> {
                        filtered.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectCatalog(model)
                                        expanded = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(model.displayName, style = TextStyle(color = textColor, fontSize = 13.sp))
                                    Text(model.modelId, style = TextStyle(color = placeholderColor, fontSize = 11.sp))
                                }
                            }
                        }
                    }
                }

                if (AgentConfigToolWindowContentModel.shouldOfferCustomEntry(query, filtered)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUseCustom(query)
                                expanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            AgentMessageBundle.message("toolwindow.AgentToolWindow.config.useCustomQueryFormat", query.trim()),
                            style = TextStyle(color = textColor, fontSize = 13.sp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningEffortPicker(
    selected: IlReasoningEffort,
    onSelect: (IlReasoningEffort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IlReasoningEffort.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { effort ->
                    Box(modifier = Modifier.width(88.dp)) {
                        if (effort == selected) {
                            DefaultButton(onClick = { onSelect(effort) }) {
                                Text(effort.name)
                            }
                        } else {
                            OutlinedButton(onClick = { onSelect(effort) }) {
                                Text(effort.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormFieldLabel(label: String, textColor: Color) {
    Text(label, style = TextStyle(color = textColor, fontSize = 12.sp))
}
