package net.alkalines.radiumcode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text

internal object AgentConfigToolWindowContentModel {
    fun mockLabel(): String = "config"
}

@Composable
internal fun AgentConfigToolWindowContent() {
    val textColor = rememberThemeColor("Label.foreground", 0xFF1F2329.toInt(), 0xFFDFE1E5.toInt())

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            AgentMessageBundle.message("toolwindow.AgentToolWindow.configMock"),
            style = TextStyle(color = textColor, fontSize = 14.sp)
        )
    }
}
