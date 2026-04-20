package net.alkalines.radiumcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginXmlRegistrationTest {

    @Test
    fun `registers the OpenRouter provider under the plugin extension namespace`() {
        val pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        assertTrue(
            pluginXml.contains("""<extensions defaultExtensionNs="net.alkalines.radiumcode">"""),
            "plugin.xml must register custom provider extensions under net.alkalines.radiumcode"
        )
        assertTrue(
            pluginXml.contains("""<agentProvider implementationClass="net.alkalines.radiumcode.agent.providers.OpenRouterProvider"/>"""),
            "plugin.xml must register OpenRouterProvider"
        )
    }
}
