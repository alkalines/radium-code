package net.alkalines.radiumcode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.intellij.ui.JBColor

@Composable
internal fun rememberThemeColor(name: String, light: Int, dark: Int): Color {
    val awtColor = remember(name, light, dark) { JBColor.namedColor(name, JBColor(light, dark)) }
    return remember(awtColor) { Color(awtColor.rgb) }
}
