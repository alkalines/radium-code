package net.alkalines.radiumcode

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import javax.swing.Icon as SwingIcon

internal enum class AgentToolWindowRoute {
    CHAT,
    CONFIG;

    companion object {
        fun initial(): AgentToolWindowRoute = CHAT

        fun showConfig(): AgentToolWindowRoute = CONFIG

        fun toggleConfig(current: AgentToolWindowRoute): AgentToolWindowRoute =
            if (current == CONFIG) CHAT else CONFIG
    }
}

@Composable
internal fun AgentToolWindowShell() {
    var route by remember { mutableStateOf(AgentToolWindowRoute.initial()) }
    val chatState = rememberAgentChatToolWindowState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(rememberThemeColor("Panel.background", 0xFFF7F8FA.toInt(), 0xFF2B2D30.toInt()))
    ) {
        AgentToolWindowTopToolbar(
            onConfigClick = { route = AgentToolWindowRoute.toggleConfig(route) }
        )
        Box(modifier = Modifier.weight(1f)) {
            when (route) {
                AgentToolWindowRoute.CHAT -> AgentChatToolWindowContent(chatState = chatState)
                AgentToolWindowRoute.CONFIG -> AgentConfigToolWindowContent()
            }
        }
    }
}

internal object AgentToolWindowToolbarModel {
    fun configTooltip(): String = "Config"

    fun showsConfigButtonBackground(isHovered: Boolean, isPressed: Boolean): Boolean =
        isHovered || isPressed

    fun configButtonBackground(isHovered: Boolean, isPressed: Boolean): AgentToolWindowButtonBackground =
        when {
            isPressed -> AgentToolWindowButtonBackground.PRESSED
            isHovered -> AgentToolWindowButtonBackground.HOVER
            else -> AgentToolWindowButtonBackground.NONE
        }
}

internal enum class AgentToolWindowButtonBackground {
    NONE,
    HOVER,
    PRESSED
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentToolWindowTopToolbar(onConfigClick: () -> Unit) {
    val borderColor = rememberThemeColor("Borders.ContrastBorderColor", 0xFFD8DCE3.toInt(), 0xFF454A4F.toInt())
    val iconColor = rememberThemeColor("Label.foreground", 0xFF1F2329.toInt(), 0xFFDFE1E5.toInt())
    val hoverColor = rememberThemeColor("ActionButton.hoverBackground", 0xFFE8EAEE.toInt(), 0xFF3A3D42.toInt())
    val pressedColor = rememberThemeColor("ActionButton.pressedBackground", 0xFFD8DCE3.toInt(), 0xFF4A4D52.toInt())
    val configIcon = remember { IntelliJIconKey.fromPlatformIcon(agentConfigToolbarIcon()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .width(1.dp)
                    .background(borderColor)
            )
            Spacer(Modifier.width(8.dp))
            val configInteractionSource = remember { MutableInteractionSource() }
            val isConfigHovered by configInteractionSource.collectIsHoveredAsState()
            val isConfigPressed by configInteractionSource.collectIsPressedAsState()
            Tooltip(
                tooltip = {
                    Text(AgentToolWindowToolbarModel.configTooltip())
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = when (AgentToolWindowToolbarModel.configButtonBackground(isConfigHovered, isConfigPressed)) {
                                AgentToolWindowButtonBackground.NONE -> Color.Transparent
                                AgentToolWindowButtonBackground.HOVER -> hoverColor
                                AgentToolWindowButtonBackground.PRESSED -> pressedColor
                            },
                            shape = RoundedCornerShape(4.dp)
                        )
                        .hoverable(configInteractionSource)
                        .clickable(
                            indication = null,
                            interactionSource = configInteractionSource,
                            onClick = onConfigClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        key = configIcon,
                        contentDescription = AgentToolWindowToolbarModel.configTooltip(),
                        modifier = Modifier.size(16.dp),
                        tint = iconColor
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(borderColor)
        )
    }
}

internal fun agentConfigToolbarIcon(): SwingIcon =
    runCatching {
        AllIcons.FileTypes::class.java.getField("EditorConfig").get(null) as SwingIcon
    }.getOrElse {
        IconLoader.getIcon("/expui/fileTypes/editorConfig.svg", AgentToolWindowFactory::class.java)
    }
