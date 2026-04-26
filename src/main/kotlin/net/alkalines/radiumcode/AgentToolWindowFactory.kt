package net.alkalines.radiumcode

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab

class AgentToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(AgentToolWindowChrome.composeTabTitle(), focusOnClickInside = true) {
            AgentToolWindowShell()
        }
    }
}

internal object AgentToolWindowChrome {
    fun composeTabTitle(): String? = null
}

internal object AgentToolWindowLayout {
    fun showConversationContainerChrome(): Boolean = false
}
