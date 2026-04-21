package net.alkalines.radiumcode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import net.alkalines.radiumcode.agent.il.IlRole
import net.alkalines.radiumcode.agent.providers.ProviderRegistry
import net.alkalines.radiumcode.agent.runtime.AgentRuntime
import net.alkalines.radiumcode.agent.runtime.SubmitPromptResult
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.IntelliJIconKey

class AgentToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(AgentToolWindowChrome.composeTabTitle(), focusOnClickInside = true) {
            AgentToolWindowContent()
        }
    }
}

internal object AgentToolWindowChrome {
    fun composeTabTitle(): String? = null
}

internal object AgentToolWindowLayout {
    fun showConversationContainerChrome(): Boolean = false
}

internal enum class ComposerTrailingButton {
    SEND,
    STOP
}

@Composable
@Preview
private fun AgentToolWindowContent() {
    val registry = remember { ProviderRegistry.lazyInstance }
    val runtime = remember { AgentRuntime(registry = registry) }
    val state by runtime.state.collectAsState()
    val chatItems = AgentToolWindowPresenter.chatItems(state.session)
    val modelOptions = remember(registry.allModels) { registry.allModels.map { it.modelId } }
    val selectedModel = state.selectedModelId ?: AgentToolWindowPresenter.modelLabel(registry)
    var prompt by remember { mutableStateOf(TextFieldValue("")) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var selectorXpx by remember { mutableIntStateOf(0) }
    var selectorYpx by remember { mutableIntStateOf(0) }
    var selectorWidthPx by remember { mutableIntStateOf(0) }

    val shape = RoundedCornerShape(24.dp)
    val surfaceColor = rememberThemeColor("Panel.background", 0xFFF7F8FA.toInt(), 0xFF2B2D30.toInt())
    val inputColor = rememberThemeColor("EditorPane.inactiveBackground", 0xFFF4F5F7.toInt(), 0xFF313336.toInt())
    val borderColor = rememberThemeColor("Borders.ContrastBorderColor", 0xFFD8DCE3.toInt(), 0xFF454A4F.toInt())
    val placeholderColor = rememberThemeColor("TextField.placeholderForeground", 0xFF7A8088.toInt(), 0xFF8B9098.toInt())
    val errorColor = rememberThemeColor("ValidationTooltip.errorForeground", 0xFFB3261E.toInt(), 0xFFFFB4AB.toInt())
    val textColor = rememberThemeColor("Label.foreground", 0xFF1F2329.toInt(), 0xFFDFE1E5.toInt())
    val menuSurfaceColor = rememberThemeColor("PopupMenu.background", 0xFFF7F8FA.toInt(), 0xFF2F3136.toInt())
    val selectedRowColor = rememberThemeColor("Toolbar.Dropdown.background", 0xFF3F4247.toInt(), 0xFF4A4D52.toInt())
    val sendEnabled = prompt.text.isNotBlank() && !state.isStreaming && state.hasUsableSelection
    val trailingButton = composerTrailingButton(isStreaming = state.isStreaming)
    val sendBackground = if (sendEnabled) {
        rememberThemeColor("Button.default.startBackground", 0xFF3574F0.toInt(), 0xFF548AF7.toInt())
    } else {
        rememberThemeColor("ActionButton.hoverBackground", 0xFFE8EAEE.toInt(), 0xFF3A3D42.toInt())
    }
    val sendContentColor = if (sendEnabled) Color.White else placeholderColor
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val menuWidth = 220.dp
    val menuHeight = minOf(modelOptions.size * 42 + 40, 280).dp
    val menuGap = 8.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val menuWidthPx = with(density) { menuWidth.roundToPx() }
    val menuHeightPx = with(density) { menuHeight.roundToPx() }
    val menuGapPx = with(density) { menuGap.roundToPx() }
    val sendIcon = remember {
        IntelliJIconKey.fromPlatformIcon(
            IconLoader.getIcon("/expui/general/up.svg", AgentToolWindowFactory::class.java)
        )
    }
    val stopIcon = remember { IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Suspend) }
    val expandIcon = remember { IntelliJIconKey.fromPlatformIcon(AllIcons.General.ArrowDown) }
    val thinkingChevronIcon = remember { IntelliJIconKey.fromPlatformIcon(AllIcons.General.ChevronRight) }
    val checkIcon = remember { IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Checked) }
    var expandedThinkingItems by remember { mutableStateOf(emptySet<String>()) }
    val submitPrompt = {
        prompt = submittedPromptValue(prompt, runtime.submitPrompt(prompt.text))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = bottomPadding)
    ) {
        if (modelMenuExpanded && modelOptions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            x = selectorXpx + selectorWidthPx - menuWidthPx,
                            y = selectorYpx - menuHeightPx - menuGapPx
                        )
                    }
                    .zIndex(2f)
                    .size(width = menuWidth, height = menuHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(menuSurfaceColor)
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .padding(vertical = 6.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    AgentMessageBundle.message("toolwindow.AgentToolWindow.modelMenu"),
                    style = TextStyle(color = placeholderColor, fontSize = 12.sp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
                modelOptions.forEach { option ->
                    val selected = option == selectedModel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) selectedRowColor else Color.Transparent)
                            .clickable {
                                registry.allModels.firstOrNull { it.modelId == option }?.let {
                                    runtime.selectModel(it.providerId, it.modelId)
                                }
                                modelMenuExpanded = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(option, style = TextStyle(color = textColor, fontSize = 14.sp))
                        Spacer(Modifier.weight(1f))
                        if (selected) {
                            Icon(
                                key = checkIcon,
                                contentDescription = AgentMessageBundle.message("toolwindow.AgentToolWindow.check"),
                                modifier = Modifier.size(16.dp),
                                tint = textColor
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val chatScrollState = rememberScrollState()
            val chatLengthSignal = AgentToolWindowPresenter.autoScrollSignal(chatItems)
            LaunchedEffect(chatLengthSignal) {
                val distanceFromBottom = chatScrollState.maxValue - chatScrollState.value
                if (distanceFromBottom <= 64) {
                    chatScrollState.scrollTo(chatScrollState.maxValue)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(
                        if (AgentToolWindowLayout.showConversationContainerChrome()) {
                            Modifier
                                .background(surfaceColor, shape)
                                .border(1.dp, borderColor, shape)
                        } else {
                            Modifier
                        }
                    )
                    .padding(14.dp)
                    .verticalScroll(chatScrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                chatItems.forEach { item ->
                    if (item.role == IlRole.USER && item.kind == AgentChatItem.Kind.TEXT) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 420.dp)
                                    .background(
                                        rememberThemeColor("Button.default.startBackground", 0xFF3574F0.toInt(), 0xFF548AF7.toInt()),
                                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(item.text, style = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp))
                            }
                        }
                    } else {
                        val plainTextColor = when (item.kind) {
                            AgentChatItem.Kind.ERROR -> rememberThemeColor("ValidationTooltip.errorForeground", 0xFFB3261E.toInt(), 0xFFFFB4AB.toInt())
                            AgentChatItem.Kind.THINKING -> placeholderColor
                            AgentChatItem.Kind.TOOL -> placeholderColor
                            AgentChatItem.Kind.TEXT -> textColor
                        }
                        if (item.kind == AgentChatItem.Kind.THINKING) {
                            ThinkingMessage(
                                item = item,
                                textColor = plainTextColor,
                                chevronIcon = thinkingChevronIcon,
                                isExpanded = isThinkingExpanded(expandedThinkingItems, item.id),
                                onToggle = {
                                    expandedThinkingItems = toggledThinkingItemExpansion(expandedThinkingItems, item.id)
                                }
                            )
                        } else {
                            val fontStyle = if (AgentToolWindowPresenter.shouldItalicize(item.kind)) FontStyle.Italic else FontStyle.Normal
                            Text(
                                item.text,
                                style = TextStyle(color = plainTextColor, fontSize = 14.sp, lineHeight = 22.sp, fontStyle = fontStyle)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor, shape)
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    textStyle = TextStyle(color = textColor, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(textColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .onPreviewKeyEvent { event ->
                            if (shouldSubmitPromptFromKeyEvent(prompt, event.key, event.type, event.isShiftPressed)) {
                                submitPrompt()
                                true
                            } else if (shouldInsertLineBreakFromKeyEvent(prompt, event.key, event.type, event.isShiftPressed)) {
                                prompt = insertedLineBreakPromptValue(prompt)
                                true
                            } else {
                                false
                            }
                        },
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(inputColor, RoundedCornerShape(18.dp))
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            if (prompt.text.isEmpty()) {
                                Text(
                                    AgentMessageBundle.message("toolwindow.AgentToolWindow.placeholder"),
                                    style = TextStyle(color = placeholderColor, fontSize = 15.sp)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                state.inlineError?.let { message ->
                    Text(
                        message,
                        style = TextStyle(color = errorColor, fontSize = 13.sp, lineHeight = 18.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(Modifier.weight(1f))
                    ToolbarChip(
                        label = selectedModel,
                        textColor = textColor,
                        backgroundColor = inputColor,
                        borderColor = borderColor,
                        trailingIcon = expandIcon,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            selectorWidthPx = coordinates.size.width
                            val position = coordinates.positionInRoot()
                            selectorXpx = position.x.toInt()
                            selectorYpx = position.y.toInt()
                        },
                        onClick = { modelMenuExpanded = !modelMenuExpanded },
                        trailing = AgentMessageBundle.message("toolwindow.AgentToolWindow.expand")
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(if (trailingButton == ComposerTrailingButton.STOP) inputColor else sendBackground, CircleShape)
                            .then(
                                if (trailingButton == ComposerTrailingButton.STOP) {
                                    Modifier.border(1.dp, borderColor, CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                            .clickable(
                                enabled = trailingButton == ComposerTrailingButton.STOP || sendEnabled,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                when (trailingButton) {
                                    ComposerTrailingButton.SEND -> submitPrompt()
                                    ComposerTrailingButton.STOP -> runtime.cancelActiveTurn()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            key = if (trailingButton == ComposerTrailingButton.STOP) stopIcon else sendIcon,
                            contentDescription = AgentMessageBundle.message(
                                if (trailingButton == ComposerTrailingButton.STOP) {
                                    "toolwindow.AgentToolWindow.stop"
                                } else {
                                    "toolwindow.AgentToolWindow.send"
                                }
                            ),
                            modifier = Modifier.size(16.dp),
                            tint = if (trailingButton == ComposerTrailingButton.STOP) textColor else sendContentColor
                        )
                    }
                }
            }
        }
    }
}

internal fun shouldSubmitPromptFromKeyEvent(
    prompt: TextFieldValue,
    key: Key,
    type: KeyEventType,
    isShiftPressed: Boolean,
): Boolean = prompt.composition == null && key == Key.Enter && type == KeyEventType.KeyDown && !isShiftPressed

internal fun shouldInsertLineBreakFromKeyEvent(
    prompt: TextFieldValue,
    key: Key,
    type: KeyEventType,
    isShiftPressed: Boolean,
): Boolean = prompt.composition == null && key == Key.Enter && type == KeyEventType.KeyDown && isShiftPressed

internal fun composerTrailingButton(isStreaming: Boolean): ComposerTrailingButton =
    if (isStreaming) {
        ComposerTrailingButton.STOP
    } else {
        ComposerTrailingButton.SEND
    }

internal fun insertedLineBreakPromptValue(prompt: TextFieldValue): TextFieldValue {
    val selectionStart = minOf(prompt.selection.start, prompt.selection.end)
    val selectionEnd = maxOf(prompt.selection.start, prompt.selection.end)
    val updatedText = buildString {
        append(prompt.text.substring(0, selectionStart))
        append('\n')
        append(prompt.text.substring(selectionEnd))
    }
    return TextFieldValue(
        text = updatedText,
        selection = TextRange(selectionStart + 1)
    )
}

internal fun submittedPromptValue(prompt: TextFieldValue, result: SubmitPromptResult): TextFieldValue =
    if (result == SubmitPromptResult.ACCEPTED) TextFieldValue("") else prompt

internal fun isThinkingExpanded(expandedItems: Set<String>, itemId: String): Boolean = itemId in expandedItems

internal fun toggledThinkingItemExpansion(expandedItems: Set<String>, itemId: String): Set<String> =
    if (itemId in expandedItems) expandedItems - itemId else expandedItems + itemId

internal fun thinkingChevronRotation(isExpanded: Boolean): Float = if (isExpanded) 90f else 0f

internal fun shouldAnimateThinkingExpansion(wasExpanded: Boolean, isExpanded: Boolean): Boolean =
    !wasExpanded && isExpanded

@Composable
private fun ThinkingMessage(
    item: AgentChatItem,
    textColor: Color,
    chevronIcon: IconKey,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = thinkingChevronRotation(isExpanded),
        animationSpec = tween(durationMillis = 180),
        label = "thinkingChevronRotation"
    )
    var wasExpanded by remember(item.id) { mutableStateOf(false) }
    val shouldAnimateExpansion = shouldAnimateThinkingExpansion(wasExpanded = wasExpanded, isExpanded = isExpanded)
    SideEffect {
        wasExpanded = isExpanded
    }
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onToggle
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                AgentMessageBundle.message("toolwindow.AgentToolWindow.thinking"),
                style = TextStyle(color = textColor, fontSize = 14.sp, lineHeight = 20.sp)
            )
            Icon(
                key = chevronIcon,
                contentDescription = AgentMessageBundle.message("toolwindow.AgentToolWindow.expand"),
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
                tint = textColor
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = if (shouldAnimateExpansion) {
                expandVertically(animationSpec = tween(durationMillis = 180)) + fadeIn(animationSpec = tween(durationMillis = 120))
            } else {
                EnterTransition.None
            },
            exit = shrinkVertically(animationSpec = tween(durationMillis = 180)) + fadeOut(animationSpec = tween(durationMillis = 120))
        ) {
            Text(
                item.text,
                style = TextStyle(color = textColor, fontSize = 14.sp, lineHeight = 22.sp, fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ToolbarChip(
    label: String,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color,
    trailingIcon: IconKey? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor, RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = TextStyle(color = textColor, fontSize = 13.sp))
        if (trailing != null && trailingIcon != null) {
            Icon(
                key = trailingIcon,
                contentDescription = trailing,
                modifier = Modifier.size(14.dp),
                tint = textColor
            )
        }
    }
}

@Composable
private fun rememberThemeColor(name: String, light: Int, dark: Int): Color {
    val awtColor = remember(name, light, dark) { JBColor.namedColor(name, JBColor(light, dark)) }
    return remember(awtColor) { Color(awtColor.rgb) }
}
