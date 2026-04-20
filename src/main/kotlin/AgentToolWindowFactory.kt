package net.alkalines.radiumcode

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
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
        toolWindow.addComposeTab("", focusOnClickInside = true) {
            AgentToolWindowContent()
        }
    }
}

internal object AgentToolWindowLayout {
    fun showConversationContainerChrome(): Boolean = false
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
    var prompt by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var selectorXpx by remember { mutableIntStateOf(0) }
    var selectorYpx by remember { mutableIntStateOf(0) }
    var selectorWidthPx by remember { mutableIntStateOf(0) }

    val shape = RoundedCornerShape(24.dp)
    val surfaceColor = rememberThemeColor("Panel.background", 0xFFF7F8FA.toInt(), 0xFF2B2D30.toInt())
    val inputColor = rememberThemeColor("EditorPane.inactiveBackground", 0xFFF4F5F7.toInt(), 0xFF313336.toInt())
    val borderColor = rememberThemeColor("Borders.ContrastBorderColor", 0xFFD8DCE3.toInt(), 0xFF454A4F.toInt())
    val placeholderColor = rememberThemeColor("TextField.placeholderForeground", 0xFF7A8088.toInt(), 0xFF8B9098.toInt())
    val textColor = rememberThemeColor("Label.foreground", 0xFF1F2329.toInt(), 0xFFDFE1E5.toInt())
    val menuSurfaceColor = rememberThemeColor("PopupMenu.background", 0xFFF7F8FA.toInt(), 0xFF2F3136.toInt())
    val selectedRowColor = rememberThemeColor("Toolbar.Dropdown.background", 0xFF3F4247.toInt(), 0xFF4A4D52.toInt())
    val sendEnabled = prompt.isNotBlank()
    val sendBackground = if (sendEnabled) {
        rememberThemeColor("Button.default.startBackground", 0xFF3574F0.toInt(), 0xFF548AF7.toInt())
    } else {
        rememberThemeColor("ActionButton.hoverBackground", 0xFFE8EAEE.toInt(), 0xFF3A3D42.toInt())
    }
    val sendContentColor = if (sendEnabled) Color.White else placeholderColor
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val menuWidth = 220.dp
    val menuHeight = (modelOptions.size * 42 + 40).dp
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
    val checkIcon = remember { IntelliJIconKey.fromPlatformIcon(AllIcons.Actions.Checked) }
    val isStreaming = state.isStreaming

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
                chatScrollState.scrollTo(chatScrollState.maxValue)
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
                        val fontStyle = if (AgentToolWindowPresenter.shouldItalicize(item.kind)) FontStyle.Italic else FontStyle.Normal
                        Text(
                            item.text,
                            style = TextStyle(color = plainTextColor, fontSize = 14.sp, lineHeight = 22.sp, fontStyle = fontStyle)
                        )
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
                        .verticalScroll(rememberScrollState()),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(inputColor, RoundedCornerShape(18.dp))
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            if (prompt.isEmpty()) {
                                Text(
                                    AgentMessageBundle.message("toolwindow.AgentToolWindow.placeholder"),
                                    style = TextStyle(color = placeholderColor, fontSize = 15.sp)
                                )
                            }
                            innerTextField()
                        }
                    }
                )

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
                    if (isStreaming) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(inputColor, CircleShape)
                                .border(1.dp, borderColor, CircleShape)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { runtime.cancelActiveTurn() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                key = stopIcon,
                                contentDescription = AgentMessageBundle.message("toolwindow.AgentToolWindow.stop"),
                                modifier = Modifier.size(16.dp),
                                tint = textColor
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(sendBackground, CircleShape)
                            .clickable(
                                enabled = sendEnabled,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                runtime.submitPrompt(prompt)
                                prompt = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            key = sendIcon,
                            contentDescription = AgentMessageBundle.message("toolwindow.AgentToolWindow.send"),
                            modifier = Modifier.size(16.dp),
                            tint = sendContentColor
                        )
                    }
                }
            }
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
