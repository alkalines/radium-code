package net.alkalines.radiumcode.agent.providers

import com.intellij.openapi.extensions.AbstractExtensionPointBean
import com.intellij.util.xmlb.annotations.Attribute

class AgentProviderBean : AbstractExtensionPointBean() {
    @Attribute("implementationClass")
    lateinit var implementationClass: String

    fun instantiate(): AgentProvider {
        val clazz = Class.forName(implementationClass, true, javaClass.classLoader)
        return clazz.getDeclaredConstructor().newInstance() as AgentProvider
    }
}
